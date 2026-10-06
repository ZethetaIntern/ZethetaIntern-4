package com.payflow;

import com.payflow.core.RoutingEngine;
import com.payflow.core.RoutingEngine.CircuitState;
import com.payflow.repository.GatewayHourlyMetricRepository;
import com.payflow.service.DeadLetterQueueService;
import com.payflow.service.GatewayRateLimiter;
import com.payflow.util.PiiSanitizer;
import com.payflow.entity.Anomaly;
import com.payflow.entity.GatewayRouteSelection;
import com.payflow.entity.WebhookQueueItem;
import com.payflow.entity.ProcessedWebhookEvent;
import com.payflow.entity.Refund;
import com.payflow.entity.ReconciliationLog;
import com.payflow.entity.GatewayHourlyMetric;
import com.payflow.gateway.GatewayClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/** Coverage for the spec-driven components added in A3.3/A3.4/A8.3/A8.4 and PII handling. */
@SpringBootTest
class PlatformServicesTest {

    @Autowired RoutingEngine routing;
    @Autowired GatewayRateLimiter rateLimiter;
    @Autowired DeadLetterQueueService dlq;
    @Autowired GatewayHourlyMetricRepository hourly;

    // --- A3.3 circuit breaker states ------------------------------------------------

    @Test
    void circuitStartsClosed() {
        assertEquals(CircuitState.CLOSED, routing.circuitState("razorpay"));
    }

    @Test
    void circuitOpensThenRecoversThroughHalfOpen() {
        String gw = "razorpay";
        try {
            routing.setHealth(gw, false);
            assertEquals(CircuitState.OPEN, routing.circuitState(gw));
            // While OPEN the gateway is excluded from routing.
            assertTrue(routing.rank("card", 100000).stream()
                    .noneMatch(r -> r.route().getGateway().equals(gw)));
        } finally {
            routing.setHealth(gw, true);
        }
        assertEquals(CircuitState.CLOSED, routing.circuitState(gw));
    }

    // --- A3.4 historical hourly dataset -------------------------------------------

    @Test
    void historicalDatasetIsSeeded() {
        assertTrue(hourly.count() >= 96, "expected 24 hourly buckets x 4 gateways");
        var payu = hourly.findByGatewayOrderByRecordedAtDesc("payu");
        assertFalse(payu.isEmpty());
        assertTrue(payu.get(0).getP95LatencyMs() > 0);
        assertTrue(payu.get(0).getSuccessRate() > 0 && payu.get(0).getSuccessRate() <= 1.0);
    }

    // --- A8.4 rate limiting --------------------------------------------------------

    @Test
    void rateLimiterReportsUtilisationPerGateway() {
        var util = rateLimiter.utilisation();
        assertTrue(util.containsKey("razorpay"));
        assertTrue(util.get("razorpay").contains("/200 req/sec"));
    }

    @Test
    void rateLimiterAllowsBurstWithinBucket() {
        long start = System.currentTimeMillis();
        for (int i = 0; i < 5; i++) {
            rateLimiter.acquire("stripe"); // far below the 100/s bucket
        }
        assertTrue(System.currentTimeMillis() - start < 500, "in-budget calls must not be delayed");
    }

    @Test
    void nonBlockingRateLimitAdmissionRejectsExcessTraffic() {
        for (int i = 0; i < 100; i++) {
            assertTrue(rateLimiter.tryAcquire("test-gateway"));
        }
        assertFalse(rateLimiter.tryAcquire("test-gateway"));
    }

    // --- A8.3 dead letter queue ----------------------------------------------------

    @Test
    void webhookFailuresLandInTheDeadLetterQueue() {
        long before = dlq.depth();
        // The same event failing repeatedly exhausts its retries (maxRetries = 3).
        for (int attempt = 1; attempt <= 3; attempt++) {
            dlq.recordFailure("razorpay", "dlq-evt-1", "{\"event_id\":\"dlq-evt-1\"}",
                    "sig", "amount mismatch attempt " + attempt);
        }
        assertEquals(before + 1, dlq.depth(), "an item past maxRetries must be parked in the DLQ");
        var parked = dlq.deadLettered();
        assertFalse(parked.isEmpty());
        assertEquals(3, parked.get(parked.size() - 1).getRetryCount());
        assertTrue(parked.get(parked.size() - 1).getErrorMessage().contains("amount mismatch"));
    }

    // --- PII sanitisation (Part D Day 3-4) ----------------------------------------

    @Test
    void piiSanitizerRedactsSensitiveData() {
        String log = "charge for card 4111 1111 1111 1111 cvv=123 upi=rahul@okhdfcbank "
                + "email=buyer@example.com phone=9876543210 account no: 0011223344";
        String clean = PiiSanitizer.sanitize(log);
        assertNotEquals(log, clean);
        assertFalse(clean.contains("4111 1111 1111 1111"), "card number must be redacted");
        assertFalse(clean.contains("rahul@okhdfcbank"), "UPI id must be redacted");
        assertFalse(clean.contains("buyer@example.com"), "email must be redacted");
        assertFalse(clean.contains("9876543210"), "phone must be redacted");
    }

    @Test
    void piiSanitizerKeepsNonSensitiveText() {
        String log = "transaction txn_123 state CAPTURED gateway=razorpay latency=320ms";
        assertEquals(log, PiiSanitizer.sanitize(log));
    }

    @Test
    void maskCardKeepsLastFour() {
        assertEquals("************1111", PiiSanitizer.maskCard("4111111111111111"));
    }

    @Test
    void immutableRecordModelsExposeTheirState() {
        var anomaly = new Anomaly("run-1", "txn-1", "CAPTURED", "FAILED",
                Anomaly.Severity.CRITICAL, "status mismatch");
        anomaly.setAlerted(true);
        assertNotNull(anomaly.getId());
        assertEquals("run-1", anomaly.getRunId());
        assertEquals("txn-1", anomaly.getTransactionId());
        assertEquals("CAPTURED", anomaly.getInternalState());
        assertEquals("FAILED", anomaly.getGatewayStatus());
        assertEquals(Anomaly.Severity.CRITICAL, anomaly.getSeverity());
        assertEquals("status mismatch", anomaly.getDetail());
        assertTrue(anomaly.isAlerted());
        assertNotNull(anomaly.getCreatedAt());

        var selection = new GatewayRouteSelection("txn-1", "stripe", 0.9, 1, 0);
        assertNotNull(selection.getId());
        assertEquals("txn-1", selection.getTransactionId());
        assertEquals("stripe", selection.getGateway());
        assertEquals(0.9, selection.getScore());
        assertEquals(1, selection.getRank());
        assertEquals(0, selection.getAttemptNo());
        assertNotNull(selection.getCreatedAt());

        var queued = new WebhookQueueItem("razorpay", "evt-1", "{}", "signature");
        assertNotNull(queued.getId());
        assertEquals("razorpay", queued.getGateway());
        assertEquals("evt-1", queued.getEventId());
        assertEquals("{}", queued.getPayload());
        assertEquals("signature", queued.getSignature());
        assertEquals(WebhookQueueItem.Status.PENDING, queued.getStatus());
        assertEquals(0, queued.getRetryCount());
        assertEquals(3, queued.getMaxRetries());
        queued.incrementRetries();
        queued.setErrorMessage("retry");
        queued.setStatus(WebhookQueueItem.Status.FAILED);
        queued.setProcessedAt(java.time.Instant.now());
        assertEquals(1, queued.getRetryCount());
        assertEquals("retry", queued.getErrorMessage());
        assertNotNull(queued.getProcessedAt());

        var error = new GatewayClient.GatewayException("stripe", "DECLINED", "declined");
        assertEquals("stripe", error.gateway);
        assertEquals("DECLINED", error.code);
        assertEquals("declined", error.getMessage());
        assertEquals("TIMEOUT", new GatewayClient.GatewayTimeout("upi").code);
    }

    @Test
    void sanitizerHandlesNullAndShortCardValues() {
        assertNull(PiiSanitizer.sanitize(null));
        assertEquals("[CARD_REDACTED]", PiiSanitizer.maskCard("123"));
    }

    @Test
    void auditAndMetricEntitiesRetainTheirFields() {
        var event = new ProcessedWebhookEvent("stripe", "evt-2", "payment.succeeded",
                "payload-hash", "txn-2");
        assertEquals("stripe:evt-2", event.getEventKey());
        assertEquals("stripe", event.getGateway());
        assertEquals("evt-2", event.getEventId());
        assertEquals("payment.succeeded", event.getEventType());
        assertEquals("payload-hash", event.getPayloadHash());
        assertEquals("txn-2", event.getTransactionId());
        assertNotNull(event.getProcessedAt());

        var refund = new Refund("txn-2", 1500, "stripe", "PROCESSED");
        assertNotNull(refund.getId());
        assertEquals("txn-2", refund.getTransactionId());
        assertEquals(1500, refund.getAmountPaise());
        assertEquals("stripe", refund.getGateway());
        assertEquals("PROCESSED", refund.getStatus());
        assertNotNull(refund.getCreatedAt());

        var reconciliation = new ReconciliationLog("recon-2", "txn-2", "MISMATCH", "manual review");
        assertNotNull(reconciliation.getId());
        assertEquals("recon-2", reconciliation.getRunId());
        assertEquals("txn-2", reconciliation.getTransactionId());
        assertEquals("MISMATCH", reconciliation.getDiscrepancyType());
        assertEquals("manual review", reconciliation.getDetail());
        assertNotNull(reconciliation.getCreatedAt());

        var metric = new GatewayHourlyMetric("stripe", java.time.Instant.now(), 0.99, 200, 100);
        assertEquals("stripe", metric.getGateway());
        assertNotNull(metric.getId());
        assertNotNull(metric.getRecordedAt());
        assertEquals(0.99, metric.getSuccessRate());
        assertEquals(200, metric.getP95LatencyMs());
        assertEquals(100, metric.getTransactionCount());
    }
}
