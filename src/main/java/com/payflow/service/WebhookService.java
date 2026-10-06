package com.payflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.config.PayFlowProperties;
import com.payflow.domain.TransactionState;
import com.payflow.entity.ProcessedWebhookEvent;
import com.payflow.entity.Transaction;
import com.payflow.repository.ProcessedWebhookEventRepository;
import com.payflow.repository.TransactionRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Webhook ingestion pipeline (spec A5):
 * signature verification (constant-time) -> dedup via PK(gateway,event_id)
 * -> reconciliation onto the state machine, all in one DB transaction.
 */
@Service
public class WebhookService {

    public record Result(boolean accepted, boolean deduplicated, boolean reconciled,
                         String transactionId, String reason) {}

    private final ProcessedWebhookEventRepository processed;
    private final TransactionRepository transactions;
    private final StateService states;
    private final PayFlowProperties props;
    private final DeadLetterQueueService deadLetterQueue;
    private final EntityManager entityManager;
    private final DataSource dataSource;
    private volatile Boolean postgres;
    private static final ReentrantLock[] EVENT_LOCKS = new ReentrantLock[256];
    static {
        for (int i = 0; i < EVENT_LOCKS.length; i++) EVENT_LOCKS[i] = new ReentrantLock();
    }
    private final ObjectMapper mapper = new ObjectMapper();

    public WebhookService(ProcessedWebhookEventRepository processed,
                          TransactionRepository transactions, StateService states,
                          PayFlowProperties props, DeadLetterQueueService deadLetterQueue,
                          EntityManager entityManager, DataSource dataSource) {
        this.processed = processed;
        this.transactions = transactions;
        this.states = states;
        this.props = props;
        this.deadLetterQueue = deadLetterQueue;
        this.entityManager = entityManager;
        this.dataSource = dataSource;
    }

    /** Constant-time HMAC verification (Razorpay/Stripe: HMAC-SHA256; PayU: SHA-512). */
    public boolean verifySignature(String gateway, String signatureHeader, byte[] body) {
        if (signatureHeader == null) return false;
        try {
            String algorithm = "payu".equals(gateway) ? "HmacSHA512" : "HmacSHA256";
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(props.webhookSecret().getBytes(StandardCharsets.UTF_8), algorithm));
            String expected = HexFormat.of().formatHex(mac.doFinal(body));
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    signatureHeader.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional
    public Result ingest(String gateway, String signatureHeader, byte[] body) {
        if (!verifySignature(gateway, signatureHeader, body)) {
            return new Result(false, false, false, null, "invalid_signature");
        }
        JsonNode payload;
        try {
            payload = mapper.readTree(body);
        } catch (Exception e) {
            return new Result(false, false, false, null, "malformed_json");
        }
        String eventId = payload.path("event_id").asText(null);
        if (eventId == null || eventId.isBlank()) {
            return new Result(false, false, false, null, "missing_event_id");
        }
        String eventKey = gateway + ":" + eventId;
        ReentrantLock lock = EVENT_LOCKS[Math.floorMod(eventKey.hashCode(), EVENT_LOCKS.length)];
        lock.lock();
        try {
            acquireDatabaseEventLock(eventKey);
            return ingestVerified(gateway, signatureHeader, body, payload, eventId);
        } finally {
            lock.unlock();
        }
    }

    private boolean isPostgres() {
        if (postgres == null) {
            synchronized (this) {
                if (postgres == null) {
                    try (java.sql.Connection connection = dataSource.getConnection()) {
                        postgres = connection.getMetaData().getDatabaseProductName()
                                .toLowerCase().contains("postgres");
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException("Unable to identify webhook database", e);
                    }
                }
            }
        }
        return postgres;
    }

    private void acquireDatabaseEventLock(String eventKey) {
        if (isPostgres()) {
            entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(hashtext(:event_key))")
                    .setParameter("event_key", "webhook_" + eventKey)
                    .getSingleResult();
        }
    }

    private Result ingestVerified(String gateway, String signatureHeader, byte[] body,
                                  JsonNode payload, String eventId) {
        String eventType = payload.path("event_type").asText("unknown");
        String status = payload.path("status").asText("").toLowerCase();
        String gatewayRef = payload.path("gateway_reference").asText(null);
        String txnIdInPayload = payload.path("transaction_id").asText(null);
        Long amount = payload.hasNonNull("amount") ? payload.get("amount").asLong() : null;
        String currency = payload.path("currency").asText(null);

        // The per-event lock makes the check-and-insert atomic across app instances.
        if (processed.existsByEventKey(gateway + ":" + eventId)) {
            return new Result(true, true, false, null, "duplicate_event");
        }
        processed.saveAndFlush(new ProcessedWebhookEvent(gateway, eventId, eventType,
                sha256Hex(body), txnIdInPayload));

        Result result = reconcile(gateway, eventId, status, gatewayRef, txnIdInPayload, amount, currency);
        if (!result.accepted()) {
            // Rejected after dedup (e.g. amount mismatch): park it in the DLQ.
            deadLetterQueue.recordFailure(gateway, eventId, new String(body, StandardCharsets.UTF_8),
                    signatureHeader == null ? "" : signatureHeader, result.reason());
        }
        return result;
    }

    private Result reconcile(String gateway, String eventId, String status,
                             String gatewayRef, String txnIdInPayload, Long amount, String currency) {
        Transaction t = null;
        if (gatewayRef != null) {
            t = transactions.findByGatewayReference(gatewayRef).stream().findFirst().orElse(null);
        }
        if (t == null && txnIdInPayload != null) {
            t = transactions.findById(txnIdInPayload).orElse(null);
        }
        if (t == null) {
            return new Result(true, false, false, null, "unmatched_event");
        }
        String txnId = t.getId();

        // --- Critical verification steps from case study C4 (webhook replay fraud) ---
        // Amount match: a tampered amount must never mark an order as paid.
        if (amount != null && amount != 0 && amount != t.getAmountPaise()
                && status.equals("succeeded") && t.getAmountPaise() != t.getCapturedPaise()) {
            return new Result(false, false, false, txnId,
                    "AMOUNT_MISMATCH: webhook amount " + amount + " != expected " + t.getAmountPaise());
        }
        // Currency match.
        if (currency != null && !currency.isBlank() && !currency.equalsIgnoreCase(t.getCurrency())) {
            return new Result(false, false, false, txnId,
                    "CURRENCY_MISMATCH: webhook currency " + currency + " != " + t.getCurrency());
        }
        // Transaction/reference match: the gateway reference must be the one we stored.
        if (gatewayRef != null && t.getGatewayReference() != null
                && !gatewayRef.equals(t.getGatewayReference())) {
            return new Result(false, false, false, txnId, "GATEWAY_REFERENCE_MISMATCH");
        }

        TransactionState current = t.getState();
        boolean reconciled = false;
        switch (status) {
            case "succeeded" -> {
                // Late success after a timeout: outcome was unknown; flip to AUTHORISED.
                if (current.isReconcilableFromUnknown()) {
                    states.applyTransition(txnId, TransactionState.AUTHORISED,
                            "webhook:" + gateway, "reconciled late success, event=" + eventId);
                    reconciled = true;
                }
            }
            case "failed" -> {
                if (current.canTransitionTo(TransactionState.AUTH_FAILED)) {
                    states.applyTransition(txnId, TransactionState.AUTH_FAILED,
                            "webhook:" + gateway, "reconciled failure, event=" + eventId);
                    reconciled = true;
                }
            }
            case "refunded" -> {
                if (current.canTransitionTo(TransactionState.REFUND_INITIATED)) {
                    states.applyTransition(txnId, TransactionState.REFUND_INITIATED,
                            "webhook:" + gateway, "reconciled refund, event=" + eventId);
                    states.applyTransition(txnId, TransactionState.REFUNDED,
                            "webhook:" + gateway, "refunded via webhook");
                    reconciled = true;
                }
            }
            default -> { /* capture events are handled by the capture flow */ }
        }
        return new Result(true, false, reconciled, txnId, reconciled ? "reconciled" : "no_action");
    }
}
