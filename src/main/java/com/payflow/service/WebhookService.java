package com.payflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.config.PayFlowProperties;
import com.payflow.domain.TransactionState;
import com.payflow.entity.ProcessedWebhookEvent;
import com.payflow.entity.Transaction;
import com.payflow.repository.ProcessedWebhookEventRepository;
import com.payflow.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

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
    private final ObjectMapper mapper = new ObjectMapper();

    public WebhookService(ProcessedWebhookEventRepository processed,
                          TransactionRepository transactions, StateService states,
                          PayFlowProperties props) {
        this.processed = processed;
        this.transactions = transactions;
        this.states = states;
        this.props = props;
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
        String eventType = payload.path("event_type").asText("unknown");
        String status = payload.path("status").asText("").toLowerCase();
        String gatewayRef = payload.path("gateway_reference").asText(null);
        String txnIdInPayload = payload.path("transaction_id").asText(null);

        // Insert is the dedup gate; PK (gateway:event_id) violation => duplicate.
        if (processed.existsByEventKey(gateway + ":" + eventId)) {
            return new Result(true, true, false, null, "duplicate_event");
        }
        processed.save(new ProcessedWebhookEvent(gateway, eventId, eventType,
                sha256Hex(body), txnIdInPayload));

        return reconcile(gateway, eventId, status, gatewayRef, txnIdInPayload);
    }

    private Result reconcile(String gateway, String eventId, String status,
                             String gatewayRef, String txnIdInPayload) {
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
