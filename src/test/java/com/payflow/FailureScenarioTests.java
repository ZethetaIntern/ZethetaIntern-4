package com.payflow;

import com.payflow.domain.TransactionState;
import com.payflow.gateway.GatewayClient;
import com.payflow.service.PaymentService;
import com.payflow.service.StateService;
import com.payflow.service.WebhookService;
import com.payflow.service.WebhookService.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end failure scenario tests (Part B, FS-01..FS-08). Each test sets up
 * preconditions, triggers the failure and asserts expected system behaviour.
 * The simulated gateway client is stubbed so outcomes are deterministic.
 */
@SpringBootTest
@Import(FailureScenarioTests.TestConfig.class)
class FailureScenarioTests {

    @TestConfiguration
    static class TestConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        GatewayClient stubGatewayClient() {
            return new StubGatewayClient();
        }
    }

    /** Deterministic stub: behaviour is set per-test via static controls. */
    static class StubGatewayClient implements GatewayClient {
        static final AtomicReference<Mode> authMode = new AtomicReference<>(Mode.OK);
        static final AtomicReference<Mode> captureMode = new AtomicReference<>(Mode.OK);

        /** When set, auth failures apply only to this gateway (null = all gateways). */
        static final AtomicReference<String> failGateway = new AtomicReference<>(null);

        enum Mode { OK, TIMEOUT, DECLINE, PARTIAL }

        @Override
        public AuthResult authorize(String gateway, long amountPaise, String reference) throws GatewayException {
            String only = failGateway.get();
            if (only != null && !only.equals(gateway)) {
                return new AuthResult(true, reference + ":" + gateway, null, 10);
            }
            switch (authMode.get()) {
                case TIMEOUT -> throw new GatewayTimeout(gateway);
                case DECLINE -> throw new GatewayException(gateway, "DECLINED", "declined");
                default -> { return new AuthResult(true, reference + ":" + gateway, null, 10); }
            }
        }

        @Override
        public CaptureResult capture(String gateway, String reference, long amountPaise) throws GatewayException {
            switch (captureMode.get()) {
                case TIMEOUT -> throw new GatewayTimeout(gateway);
                case DECLINE -> throw new GatewayException(gateway, "CAPTURE_FAILED", "502 bad gateway");
                case PARTIAL -> { return new CaptureResult(true, amountPaise / 2, "PARTIAL", 10); }
                default -> { return new CaptureResult(true, amountPaise, null, 10); }
            }
        }

        @Override
        public boolean refund(String gateway, String reference, long amountPaise) { return true; }
    }

    @Autowired PaymentService payments;
    @Autowired StateService states;
    @Autowired WebhookService webhooks;
    @Autowired com.payflow.core.RoutingEngine routing;
    @Autowired com.payflow.repository.ReconciliationLogRepository reconLogs;
    @Autowired com.payflow.service.ReconciliationService reconciliation;
    private TransactionState process(String key) {
        var t = payments.create(key, "ORD-" + key, 250000, "INR", "card");
        return payments.process(t.getId()).getState();
    }

    private Result webhook(String gateway, String eventId, String status, String txnId) {
        String body = "{\"event_id\":\"" + eventId + "\",\"status\":\"" + status + "\""
                + (txnId == null ? "" : ",\"transaction_id\":\"" + txnId + "\"") + "}";
        try {
            String alg = "payu".equals(gateway) ? "HmacSHA512" : "HmacSHA256";
            Mac mac = Mac.getInstance(alg);
            mac.init(new SecretKeySpec("whsec_test_secret".getBytes(StandardCharsets.UTF_8), alg));
            String sig = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return webhooks.ingest(gateway, sig, body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void states(String txnId, TransactionState... path) {
        for (TransactionState s : path) {
            states.applyTransition(txnId, s, "test", "scenario setup");
        }
    }

    // FS-01: gateway timeout during authorisation -> failover to the next gateway
    @Test
    void fs01_gatewayTimeoutDuringAuthFailsOver() {
        String top = routing.rank("card", 250000).get(0).route().getGateway();
        StubGatewayClient.failGateway.set(top);
        StubGatewayClient.authMode.set(StubGatewayClient.Mode.TIMEOUT);
        StubGatewayClient.captureMode.set(StubGatewayClient.Mode.OK);
        try {
            long start = System.currentTimeMillis();
            var outcome = process("fs01");
            var view = payments.view(payments.getByKey("fs01"));
            String detail = "attempts=" + view.attempts().stream()
                    .map(a -> a.getGateway() + ":" + a.getOutcome()).toList()
                    + " failureReason=" + view.txn().getFailureReason();
            assertEquals(TransactionState.CAPTURED, outcome, detail);
            assertTrue(System.currentTimeMillis() - start < 2000, "failover must complete within 2s");
            assertTrue(view.attempts().size() >= 2, "must fail over after timeout: " + detail);
            assertEquals(com.payflow.domain.AttemptOutcome.TIMEOUT, view.attempts().get(0).getOutcome());
        } finally {
            StubGatewayClient.failGateway.set(null);
            StubGatewayClient.authMode.set(StubGatewayClient.Mode.OK);
            routing.setHealth(top, true);
        }
    }

    // FS-02: duplicate webhook delivered 3 times -> processed exactly once
    @Test
    void fs02_duplicateWebhookProcessedOnce() {
        var t = payments.create("fs02", "ORD-fs02", 50000, "INR", "upi");
        states(t.getId(), TransactionState.ROUTING, TransactionState.AUTH_INITIATED);
        Result r1 = webhook("razorpay", "fs02-evt", "succeeded", t.getId());
        Result r2 = webhook("razorpay", "fs02-evt", "succeeded", t.getId());
        Result r3 = webhook("razorpay", "fs02-evt", "succeeded", t.getId());
        assertTrue(r1.reconciled());
        assertTrue(r2.deduplicated() && r3.deduplicated());
        assertEquals(TransactionState.AUTHORISED, payments.getTransaction(t.getId()).getState());
    }

    // FS-03: customer double-submits -> single transaction via idempotency
    @Test
    void fs03_doubleSubmitIdempotent() {
        var t1 = payments.create("fs03", "ORD-fs03", 250000, "INR", "card");
        var ex = assertThrows(PaymentService.IdempotencyConflictException.class,
                () -> payments.create("fs03", "ORD-fs03", 250000, "INR", "card"));
        assertEquals(t1.getId(), ex.existingId, "second submit resolves to the original transaction");
    }

    // FS-04: gateway returns 5xx during capture -> CAPTURE_FAILED, audit intact
    @Test
    void fs04_captureFailureTracked() {
        var t = payments.create("fs04", "ORD-fs04", 250000, "INR", "card");
        String id = t.getId();
        states(id, TransactionState.ROUTING, TransactionState.AUTH_INITIATED,
                TransactionState.AUTHORISED, TransactionState.CAPTURE_INITIATED);
        StubGatewayClient.captureMode.set(StubGatewayClient.Mode.DECLINE);
        try {
            payments.captureFlow(id, "razorpay", id + ":raz", 1);
            assertEquals(TransactionState.CAPTURE_FAILED, payments.getTransaction(id).getState());
            assertTrue(payments.view(id).audit().stream()
                    .anyMatch(a -> a.getToState() == TransactionState.CAPTURE_FAILED));
        } finally {
            StubGatewayClient.captureMode.set(StubGatewayClient.Mode.OK);
        }
    }

    // FS-05: partial capture with remaining hold
    @Test
    void fs05_partialCaptureTracked() {
        var t = payments.create("fs05", "ORD-fs05", 120000, "INR", "card");
        String id = t.getId();
        states(id, TransactionState.ROUTING, TransactionState.AUTH_INITIATED,
                TransactionState.AUTHORISED, TransactionState.CAPTURE_INITIATED);
        StubGatewayClient.captureMode.set(StubGatewayClient.Mode.PARTIAL);
        try {
            payments.captureFlow(id, "stripe", id + ":str", 1);
            var txn = payments.getTransaction(id);
            assertEquals(TransactionState.PARTIALLY_CAPTURED, txn.getState());
            assertEquals(60000, txn.getCapturedPaise(), "captured portion must be tracked");
        } finally {
            StubGatewayClient.captureMode.set(StubGatewayClient.Mode.OK);
        }
    }

    // FS-06: webhook arrives before the API response (outcome unknown) -> reconciled
    @Test
    void fs06_webhookBeforeApiResponseReconciles() {
        var t = payments.create("fs06", "ORD-fs06", 50000, "INR", "upi");
        states(t.getId(), TransactionState.ROUTING, TransactionState.AUTH_INITIATED);
        Result r = webhook("stripe", "fs06-evt", "succeeded", t.getId());
        assertTrue(r.reconciled());
        assertEquals(TransactionState.AUTHORISED, payments.getTransaction(t.getId()).getState());
    }

    // FS-07: cascade gateway failure -> circuit breaker trips, gateway excluded
    @Test
    void fs07_circuitBreakerTripsOnCascadeFailure() {
        String gw = "razorpay";
        try {
            for (int i = 0; i < 5; i++) {
                routing.recordResult(gw, false, 2000);
            }
            var ranked = routing.rank("card", 100000);
            assertTrue(ranked.stream().noneMatch(r -> r.route().getGateway().equals(gw)),
                    "circuit-open gateway must be excluded");
        } finally {
            routing.setHealth(gw, true); // reset for other tests
        }
    }

    // FS-08: refund on an already-settled (captured) transaction
    @Test
    void fs08_refundOnSettledTransaction() {
        var t = payments.create("fs08", "ORD-fs08", 250000, "INR", "card");
        String id = t.getId();
        states(id, TransactionState.ROUTING, TransactionState.AUTH_INITIATED,
                TransactionState.AUTHORISED, TransactionState.CAPTURE_INITIATED, TransactionState.CAPTURED);
        var refunded = payments.refund(id, 250000, "test");
        assertEquals(TransactionState.REFUNDED, refunded.getState());
        var refundList = payments.view(id).refundList();
        assertTrue(refundList.stream().anyMatch(r -> "PROCESSED".equals(r.getStatus())),
                "refund must be recorded as processed");
        assertEquals(250000, refundList.get(refundList.size() - 1).getAmountPaise());
    }

    // FS-09: two servers receive the same request simultaneously -> one transaction
    @Test
    void fs09_concurrentIdempotencyRace() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var f1 = pool.submit(() -> payments.create("fs09", "ORD-fs09", 100000, "INR", "card").getId());
        var f2 = pool.submit(() -> payments.create("fs09", "ORD-fs09", 100000, "INR", "card").getId());
        try {
            // Exactly one thread wins; the other observes an idempotency conflict
            // (either at the pre-check or via the unique-constraint violation).
            String winner = null;
            String loser = null;
            for (var f : List.of(f1, f2)) {
                try {
                    String id = f.get(15, java.util.concurrent.TimeUnit.SECONDS);
                    assertNull(winner, "only one thread may create the transaction");
                    winner = id;
                } catch (java.util.concurrent.ExecutionException e) {
                    assertInstanceOf(PaymentService.IdempotencyConflictException.class, e.getCause());
                    loser = ((PaymentService.IdempotencyConflictException) e.getCause()).existingId;
                }
            }
            assertNotNull(winner, "one thread must succeed");
            assertNotNull(loser, "the other must be rejected as a replay");
            assertEquals(winner, loser, "the loser must resolve to the winner's transaction");
            assertEquals(winner, payments.getByKey("fs09"));
        } finally {
            pool.shutdownNow();
        }
    }

    // FS-10: replayed webhook with a tampered body -> signature verification fails
    @Test
    void fs10_webhookReplayAttackRejected() {
        String tampered = "{\"event_id\":\"fs10-evt\",\"status\":\"refunded\"}";
        Result r = webhooks.ingest("razorpay", "0".repeat(64),
                tampered.getBytes(StandardCharsets.UTF_8));
        assertFalse(r.accepted());
        assertEquals("invalid_signature", r.reason());
    }

    // FS-11: reconciliation detects missing settlement for stuck transactions
    @Test
    void fs11_reconciliationFlagsStuckTransactions() {
        var t = payments.create("fs11", "ORD-fs11", 50000, "INR", "upi");
        states(t.getId(), TransactionState.ROUTING, TransactionState.AUTH_INITIATED);
        long before = reconLogs.count();
        reconciliation.runOnce(java.time.Duration.ZERO);
        assertTrue(reconLogs.count() > before, "reconciliation run must log discrepancies");
        assertTrue(reconLogs.findAll().stream().anyMatch(l ->
                "STUCK_INTERMEDIATE_STATE".equals(l.getDiscrepancyType())
                        && t.getId().equals(l.getTransactionId())));
    }

    // FS-12: UPI collect flow timeout (customer never approves)
    @Test
    void fs12_upiCollectTimeout() {
        var t = payments.create("fs12", "ORD-fs12", 50000, "INR", "upi");
        states(t.getId(), TransactionState.ROUTING, TransactionState.AUTH_INITIATED);
        Result r = webhook("upi", "fs12-evt", "failed", t.getId());
        assertTrue(r.reconciled());
        assertEquals(TransactionState.AUTH_FAILED, payments.getTransaction(t.getId()).getState());
    }

    // FS-13: same idempotency key from a different merchant -> replay, no second txn
    @Test
    void fs13_idempotencyKeyCollision() {
        var t1 = payments.create("fs13-shared-key", "MERCHANT-A-ORDER", 100000, "INR", "card");
        var ex = assertThrows(PaymentService.IdempotencyConflictException.class,
                () -> payments.create("fs13-shared-key", "MERCHANT-B-ORDER", 100000, "INR", "card"));
        assertEquals(t1.getId(), ex.existingId);
    }

    // FS-14: traffic spike -> burst of concurrent payments completes without corruption
    @Test
    void fs14_burstThroughputSurvives() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
        for (int i = 0; i < 20; i++) {
            final int n = i;
            futures.add(pool.submit(() -> {
                var t = payments.create("fs14-" + n, "ORD-fs14-" + n, 10000, "INR", "card");
                return payments.process(t.getId()).getId();
            }));
        }
        try {
            for (var f : futures) {
                String id = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
                TransactionState s = payments.getTransaction(id).getState();
                assertTrue(s == TransactionState.CAPTURED
                                || s == TransactionState.PARTIALLY_CAPTURED
                                || s == TransactionState.CAPTURE_FAILED
                                || s == TransactionState.FAILED_TERMINAL,
                        "unexpected terminal-ish state " + s);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // FS-15: buggy handler attempts CREATED -> REFUNDED (state machine corruption)
    @Test
    void fs15_corruptionAttemptRejected() {
        var t = payments.create("fs15", "ORD-fs15", 100000, "INR", "card");
        assertThrows(StateService.IllegalTransitionException.class,
                () -> states.applyTransition(t.getId(), TransactionState.REFUNDED, "buggy-handler", "corrupt"));
        assertEquals(TransactionState.CREATED, payments.getTransaction(t.getId()).getState(),
                "state must be unchanged after a rejected transition");
    }
}
