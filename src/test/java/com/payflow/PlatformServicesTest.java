package com.payflow;

import com.payflow.core.RoutingEngine;
import com.payflow.core.RoutingEngine.CircuitState;
import com.payflow.repository.GatewayHourlyMetricRepository;
import com.payflow.service.DeadLetterQueueService;
import com.payflow.service.GatewayRateLimiter;
import com.payflow.util.PiiSanitizer;
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
}
