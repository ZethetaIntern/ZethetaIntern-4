package com.payflow.webhook;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.fasterxml.jackson.databind.JsonNode;
import com.payflow.config.PayFlowProperties;
import com.payflow.db.DatabaseGuard;
import com.payflow.entity.SecurityAuditLog;
import com.payflow.entity.Transaction;
import com.payflow.entity.WebhookQueueItem;
import com.payflow.error.ErrorResponse;
import com.payflow.gateway.GatewayRegistry;
import com.payflow.repository.ProcessedWebhookEventRepository;
import com.payflow.repository.TransactionRepository;
import com.payflow.repository.WebhookQueueRepository;
import com.payflow.security.SecurityAuditService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Webhook ingestion (spec A5.2):
 *
 * <pre>
 * [Gateway Webhook]
 *   -> Signature Verification   (401 + security audit log with source IP on failure, FS-10)
 *   -> Deduplication            (INSERT ... ON CONFLICT DO NOTHING on processed_webhook_events, FS-02)
 *   -> Event Queue              (webhook_queue row, same DB transaction as the dedup insert)
 *   -> Event Processor          (inline fast path, or the queue worker under load / on retry)
 *   -> Audit Logger             (transaction_state_log rows written by the state machine)
 *   -> Notification Dispatcher  (notification_outbox)
 * </pre>
 *
 * A duplicate delivery is acknowledged with 200 and never re-processed.
 */
@Service
public class WebhookIngestionService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestionService.class);

    /** HTTP response for the gateway. */
    public record IngestResult(int status, Map<String, Object> body) {}

    private final GatewayRegistry gateways;
    private final WebhookSignatureVerifier verifier;
    private final WebhookPayloadParser parser;
    private final ProcessedWebhookEventRepository processed;
    private final WebhookQueueRepository queue;
    private final TransactionRepository transactions;
    private final WebhookEventProcessor processor;
    private final SecurityAuditService security;
    private final DatabaseGuard dbGuard;
    private final PayFlowProperties props;
    private final TransactionTemplate tx;

    public WebhookIngestionService(GatewayRegistry gateways, WebhookSignatureVerifier verifier,
                                   WebhookPayloadParser parser, ProcessedWebhookEventRepository processed,
                                   WebhookQueueRepository queue, TransactionRepository transactions,
                                   WebhookEventProcessor processor, SecurityAuditService security,
                                   DatabaseGuard dbGuard, PayFlowProperties props, PlatformTransactionManager txManager) {
        this.gateways = gateways;
        this.verifier = verifier;
        this.parser = parser;
        this.processed = processed;
        this.queue = queue;
        this.transactions = transactions;
        this.processor = processor;
        this.security = security;
        this.dbGuard = dbGuard;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * @param headers   request headers with lower-case names
     * @param rawBody   exact bytes received (signatures are over these bytes)
     */
    public IngestResult ingest(String gateway, Map<String, String> headers, byte[] rawBody, String sourceIp,
                               String userAgent, String path) {
        if (!gateways.names().contains(gateway)) {
            return new IngestResult(404, ErrorResponse.body("NOT_FOUND", "unknown gateway " + gateway, Map.of()));
        }
        String payloadHash = sha256(rawBody);

        // 1. Signature verification
        WebhookSignatureVerifier.Verification v = verifier.verify(gateway, headers, rawBody);
        if (!v.valid()) {
            String type = "timestamp_outside_tolerance".equals(v.reason())
                    ? SecurityAuditLog.WEBHOOK_TIMESTAMP_OUT_OF_TOLERANCE : SecurityAuditLog.WEBHOOK_SIGNATURE_INVALID;
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("reason", v.reason());
            detail.put("payload_sha256", payloadHash);
            detail.put("payload_bytes", rawBody.length);
            security.record(type, gateway, sourceIp, userAgent, path, detail);
            return new IngestResult(401, ErrorResponse.body("INVALID_SIGNATURE",
                    "Webhook signature verification failed.", Map.of("gateway", gateway, "reason", v.reason())));
        }

        JsonNode body;
        NormalizedWebhookEvent ev;
        try {
            body = parser.readTree(rawBody);
            ev = parser.parse(gateway, headers, body);
        } catch (WebhookPayloadParser.MalformedWebhookException e) {
            return new IngestResult(400, ErrorResponse.body("MALFORMED_WEBHOOK", e.getMessage(),
                    Map.of("gateway", gateway)));
        }

        // 2 + 3. Dedup and enqueue atomically
        UUID transactionId = resolveTransactionId(gateway, ev);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("normalized", ev.toMap());
        payload.put("raw", parser.toMap(body));
        String signature = headers.getOrDefault(signatureHeader(gateway), "");
        boolean inline = props.webhooks().inlineProcessing() && !dbGuard.underPressure();
        Long queueId = tx.execute(status -> {
            int inserted = processed.insertIfAbsent(gateway, ev.eventId(), ev.eventType() == null ? "unknown"
                    : ev.eventType(), payloadHash, transactionId);
            if (inserted == 0) return null;
            WebhookQueueItem item = new WebhookQueueItem(gateway, ev.eventId(), ev.eventType() == null ? "unknown"
                    : ev.eventType(), transactionId, payload, signature, sourceIp);
            // An inline-processed item is only picked up by the worker if the fast path fails.
            item.setNextRetryAt(inline ? Instant.now().plusSeconds(5) : Instant.now());
            return queue.save(item).getId();
        });
        if (queueId == null) {
            log.info("duplicate webhook acknowledged {} {} {}", kv("component", "webhook_ingestion"),
                    kv("gateway", gateway), kv("event_id", ev.eventId()));
            return new IngestResult(200, Map.of("status", "duplicate", "event_id", ev.eventId()));
        }

        // 4. Event processor (fast path); the queue worker covers retries and load shedding
        if (!inline) {
            return new IngestResult(200, Map.of("status", "queued", "event_id", ev.eventId(), "queue_id", queueId));
        }
        WebhookEventProcessor.Outcome outcome = processor.processSafely(queueId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", outcome.result() == WebhookEventProcessor.Result.DEFERRED ? "queued" : "processed");
        response.put("event_id", ev.eventId());
        response.put("queue_id", queueId);
        response.putAll(WebhookEventProcessor.describe(outcome));
        if (outcome.result() == WebhookEventProcessor.Result.REJECTED) {
            return new IngestResult(HttpStatus.UNPROCESSABLE_ENTITY.value(), ErrorResponse.body(
                    "WEBHOOK_VERIFICATION_FAILED", "Webhook rejected: " + outcome.reason(), response));
        }
        return new IngestResult(200, response);
    }

    private UUID resolveTransactionId(String gateway, NormalizedWebhookEvent ev) {
        if (ev.gatewayReference() != null) {
            UUID byRef = transactions.findByGatewayAndGatewayReference(gateway, ev.gatewayReference()).stream()
                    .findFirst().map(Transaction::getId).orElse(null);
            if (byRef != null) return byRef;
        }
        if (ev.transactionId() != null && transactions.existsById(ev.transactionId())) return ev.transactionId();
        return null;
    }

    private static String signatureHeader(String gateway) {
        return switch (gateway) {
            case "razorpay" -> WebhookSignatureVerifier.RAZORPAY_HEADER.toLowerCase(java.util.Locale.ROOT);
            case "stripe" -> WebhookSignatureVerifier.STRIPE_HEADER.toLowerCase(java.util.Locale.ROOT);
            case "payu" -> WebhookSignatureVerifier.PAYU_HEADER.toLowerCase(java.util.Locale.ROOT);
            default -> WebhookSignatureVerifier.UPI_HEADER.toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Re-processes an item reset for replay (A8.3 manual replay). */
    public WebhookEventProcessor.Outcome replay(long queueId) {
        return processor.processSafely(queueId);
    }
}
