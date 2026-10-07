package com.payflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.payflow.alert.AlertService;
import com.payflow.db.DatabaseGuard;
import com.payflow.domain.AuditEvent;
import com.payflow.domain.PaymentMethod;
import com.payflow.domain.TransactionState;
import com.payflow.entity.ReconciliationRun;
import com.payflow.entity.Transaction;
import com.payflow.gateway.GatewayStatus;
import com.payflow.jobs.MaintenanceService;
import com.payflow.reconciliation.ReconciliationService;
import com.payflow.repository.TransactionRepository;
import com.payflow.statemachine.Audit;
import com.payflow.statemachine.InvalidStateTransitionException;
import com.payflow.statemachine.TransactionStateMachine;
import com.payflow.support.IntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The 15 Payment Operations Board failure scenarios (Part B2). Each test sets up
 * the preconditions, triggers the failure through the public API (with the
 * B4.3 mock headers where needed) and asserts the expected system behaviour,
 * including the audit trail and the absence of duplicate money movement.
 */
class FailureScenarioTest extends IntegrationTest {

    @Autowired TransactionRepository transactions;
    @Autowired TransactionStateMachine machine;
    @Autowired ReconciliationService reconciliation;
    @Autowired MaintenanceService maintenance;
    @Autowired AlertService alerts;
    @Autowired DatabaseGuard dbGuard;

    @AfterEach
    void closeDbBreaker() {
        dbGuard.reset();
    }

    private void onlyGatewaysForCards(String... enabled) throws Exception {
        List<String> keep = List.of(enabled);
        for (String g : List.of("razorpay", "stripe", "payu")) {
            putJson("/api/v1/gateways/" + g + "/config", Map.of("enabled", keep.contains(g)));
        }
    }

    // ------------------------------------------------------------------ FS-01
    @Test
    @DisplayName("FS-01 gateway timeout during authorisation fails over to the next gateway within 2 s")
    void fs01_timeoutFailsOver() throws Exception {
        String primary = topGateway("CARD", 250_000);
        String secondary = rankedGateways("CARD", 250_000).get(1);

        long start = System.nanoTime();
        Map<String, Object> p = createOk(250_000, "CARD", "X-Mock-Response", primary + "=timeout");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(p.get("state")).isEqualTo("CAPTURED");
        assertThat(p.get("gateway")).isEqualTo(secondary);
        // Timeout detection (attempt budget, 600 ms in tests) + alternate gateway response < 2 s
        assertThat(elapsedMs).isLessThan(2_000);

        Object id = p.get("id");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_state_log WHERE transaction_id = ?::uuid "
                + "AND from_state = 'AUTH_INITIATED' AND to_state = 'ROUTE_SELECTED' AND event = ?", Integer.class,
                id.toString(), AuditEvent.AUTH_TIMEOUT)).isEqualTo(1);
        List<Map<String, Object>> attempts = list(getJson("/api/v1/payments/" + id + "/attempts"));
        assertThat(attempts.get(0)).containsEntry("gateway", primary).containsEntry("outcome", "TIMEOUT");
        assertThat(jdbc.queryForObject("SELECT consecutive_failures FROM circuit_breaker_state "
                + "WHERE gateway = ? AND payment_method = 'CARD'", Integer.class, primary)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ FS-02
    @Test
    @DisplayName("FS-02 the same payment.captured webhook delivered 3 times is processed once")
    void fs02_duplicateWebhook() throws Exception {
        onlyGatewaysForCards("razorpay");
        Map<String, Object> req = paymentRequest(150_000, "CARD");
        req.put("capture_mode", "MANUAL");
        Map<String, Object> p = body(createPayment(UUID.randomUUID().toString(), req));
        assertThat(p.get("state")).isEqualTo("AUTHORISED");
        String body = "{\"entity\":\"event\",\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":"
                + "{\"id\":\"" + p.get("gateway_reference") + "\",\"amount\":150000,\"currency\":\"INR\","
                + "\"status\":\"captured\",\"notes\":{\"transaction_id\":\"" + p.get("id") + "\"}}}},"
                + "\"created_at\":1710842400}";

        List<Map<String, Object>> responses = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            MvcResult r = webhook("razorpay", body, "X-Razorpay-Event-Id", "evt_fs02_capture");
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            responses.add(body(r));
        }
        assertThat(responses.get(0)).containsEntry("result", "APPLIED");
        assertThat(responses.get(1)).containsEntry("status", "duplicate");
        assertThat(responses.get(2)).containsEntry("status", "duplicate");
        assertThat(state(p.get("id"))).isEqualTo("CAPTURED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_state_log WHERE transaction_id = ?::uuid "
                + "AND to_state = 'CAPTURED' AND from_state <> 'CAPTURED'", Integer.class, p.get("id").toString()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_webhook_events WHERE gateway = 'razorpay'",
                Integer.class)).isEqualTo(1);
        assertThat(getPayment(p.get("id")).get("captured_paise")).isEqualTo(150_000);
    }

    // ------------------------------------------------------------------ FS-03
    @Test
    @DisplayName("FS-03 a double click with the same idempotency key gets 409 and creates one charge")
    void fs03_doubleSubmit() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> req = paymentRequest(99_900, "CARD");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> first = pool.submit(() -> createPayment(key, req, "X-Mock-Delay-Ms", "400"));
            awaitIdempotencyKey(key);
            MvcResult second = createPayment(key, req);
            assertThat(second.getResponse().getStatus()).isEqualTo(409);
            assertThat(body(second)).extractingByKey("error").asString().contains("IDEMPOTENCY_CONFLICT");
            assertThat(first.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class)).isEqualTo(1);
        long charges = ledger.chargesCreated("razorpay") + ledger.chargesCreated("stripe")
                + ledger.chargesCreated("payu");
        assertThat(charges).isEqualTo(1);
    }

    private void awaitIdempotencyKey(String key) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE key = ?", Integer.class, key);
            if (n != null && n > 0) return;
            Thread.sleep(5);
        }
        throw new AssertionError("first request never claimed the key");
    }

    // ------------------------------------------------------------------ FS-04
    @Test
    @DisplayName("FS-04 PayU 502 on capture: retried with backoff, then CAPTURE_FAILED")
    void fs04_captureServerError() throws Exception {
        onlyGatewaysForCards("payu");
        MvcResult r = createPayment(UUID.randomUUID().toString(), paymentRequest(200_000, "CARD"),
                "X-Mock-Response", "payu.capture=server-error");
        Map<String, Object> p = body(r);
        assertThat(p.get("state")).isEqualTo("CAPTURE_FAILED");
        Object id = p.get("id");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_attempts WHERE transaction_id = ?::uuid "
                + "AND operation = 'CAPTURE' AND outcome = 'SERVER_ERROR'", Integer.class, id.toString()))
                .isEqualTo(4); // first try + 3 retries
        assertThat(jdbc.queryForList("SELECT metadata->>'backoff_ms' FROM transaction_state_log WHERE "
                + "transaction_id = ?::uuid AND event = 'CAPTURE_RETRY' ORDER BY created_at", String.class,
                id.toString())).containsExactly("20", "40", "80"); // 1x, 2x, 4x the base (1 s in production)
        assertThat(states(id)).endsWith("CAPTURE_INITIATED", "CAPTURE_FAILED");
    }

    @Test
    @DisplayName("FS-04 late success: the status poll after CAPTURE_FAILED finds the capture was processed")
    void fs04_lateSuccess() throws Exception {
        onlyGatewaysForCards("payu");
        Map<String, Object> req = paymentRequest(200_000, "CARD");
        req.put("capture_mode", "MANUAL");
        Map<String, Object> p = body(createPayment(UUID.randomUUID().toString(), req));
        // The gateway processed the capture server-side but every HTTP response was a 502.
        postJson("/api/v1/mock/gateways/payu/status", Map.of("reference", p.get("gateway_reference"), "status", "CAPTURED"));
        MvcResult r = postJson("/api/v1/payments/" + p.get("id") + "/capture", null,
                "X-Mock-Response", "payu.capture=server-error");
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).containsEntry("state", "CAPTURED").containsEntry("captured_paise", 200_000);
        assertThat(states(p.get("id"))).endsWith("CAPTURE_INITIATED", "CAPTURE_FAILED", "CAPTURED");
        assertThat(timeline(p.get("id"))).anyMatch(e -> AuditEvent.LATE_CAPTURE_SUCCESS.equals(e.get("event")));
    }

    // ------------------------------------------------------------------ FS-05
    @Test
    @DisplayName("FS-05 capture 800 of 1200: PARTIALLY_CAPTURED with 400 held, then capture the remainder")
    void fs05_partialCaptureThenRemainder() throws Exception {
        Map<String, Object> p = manualAuthorised(120_000);
        Map<String, Object> partial = body(postJson("/api/v1/payments/" + p.get("id") + "/capture",
                Map.of("amount_paise", 80_000)));
        assertThat(partial).containsEntry("state", "PARTIALLY_CAPTURED").containsEntry("captured_paise", 80_000)
                .containsEntry("remaining_hold_paise", 40_000);
        Map<String, Object> audit = timeline(p.get("id")).stream()
                .filter(e -> AuditEvent.GATEWAY_PARTIAL_CAPTURE.equals(e.get("event"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) audit.get("metadata");
        assertThat(meta).containsEntry("captured_paise", 80_000).containsEntry("remaining_hold_paise", 40_000);

        Map<String, Object> rest = body(postJson("/api/v1/payments/" + p.get("id") + "/capture", Map.of()));
        assertThat(rest).containsEntry("state", "CAPTURED").containsEntry("captured_paise", 120_000)
                .containsEntry("remaining_hold_paise", 0);
    }

    @Test
    @DisplayName("FS-05 capture 800 of 1200, then void the remaining 400 hold")
    void fs05_partialCaptureThenVoidRemainder() throws Exception {
        Map<String, Object> p = manualAuthorised(120_000);
        postJson("/api/v1/payments/" + p.get("id") + "/capture", Map.of("amount_paise", 80_000));
        Map<String, Object> released = body(postJson("/api/v1/payments/" + p.get("id") + "/void", null));
        assertThat(released).containsEntry("state", "PARTIALLY_CAPTURED").containsEntry("captured_paise", 80_000)
                .containsEntry("released_paise", 40_000).containsEntry("remaining_hold_paise", 0);
        assertThat(timeline(p.get("id"))).anyMatch(e -> AuditEvent.REMAINING_HOLD_RELEASED.equals(e.get("event")));
    }

    private Map<String, Object> manualAuthorised(long amount) throws Exception {
        Map<String, Object> req = paymentRequest(amount, "CARD");
        req.put("capture_mode", "MANUAL");
        Map<String, Object> p = body(createPayment(UUID.randomUUID().toString(), req));
        assertThat(p.get("state")).isEqualTo("AUTHORISED");
        return p;
    }

    // ------------------------------------------------------------------ FS-06
    @Test
    @DisplayName("FS-06 Stripe's payment_intent.succeeded webhook arrives before the API response")
    void fs06_webhookBeforeApiResponse() throws Exception {
        onlyGatewaysForCards("stripe");
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<MvcResult> api = pool.submit(() -> createPayment(key, paymentRequest(75_000, "CARD"),
                    "X-Mock-Delay-Ms", "stripe=450"));
            UUID txnId = awaitTransaction(key);
            String reference = awaitGatewayReference("stripe", txnId);
            String event = "{\"id\":\"evt_fs06\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{"
                    + "\"id\":\"" + reference + "\",\"object\":\"payment_intent\",\"amount\":75000,\"currency\":\"inr\","
                    + "\"status\":\"succeeded\",\"metadata\":{\"transaction_id\":\"" + txnId + "\"}}}}";
            MvcResult hook = webhook("stripe", event);
            assertThat(body(hook)).containsEntry("result", "APPLIED").containsEntry("state", "CAPTURED");

            MvcResult response = api.get(10, TimeUnit.SECONDS);
            assertThat(response.getResponse().getStatus()).isEqualTo(201);
            assertThat(body(response)).containsEntry("state", "CAPTURED");
            assertThat(states(txnId)).containsExactly("CREATED", "ROUTE_SELECTED", "AUTH_INITIATED", "CAPTURED");
            assertThat(timeline(txnId)).anyMatch(e -> AuditEvent.DUPLICATE_TRANSITION_IGNORED.equals(e.get("event")));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_attempts WHERE transaction_id = ?::uuid "
                    + "AND operation = 'CAPTURE'", Integer.class, txnId.toString())).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    private UUID awaitTransaction(String key) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            List<String> ids = jdbc.queryForList("SELECT id::text FROM transactions WHERE idempotency_key = ?",
                    String.class, key);
            if (!ids.isEmpty()) return UUID.fromString(ids.get(0));
            Thread.sleep(5);
        }
        throw new AssertionError("transaction not created");
    }

    private String awaitGatewayReference(String gateway, UUID txnId) throws InterruptedException {
        for (int i = 0; i < 400; i++) {
            var charge = ledger.find(gateway, null, txnId);
            if (charge.isPresent()) return charge.get().reference();
            Thread.sleep(5);
        }
        throw new AssertionError("gateway never created the charge");
    }

    // ------------------------------------------------------------------ FS-07
    @Test
    @DisplayName("FS-07 cascade failure: UPI keeps flowing, card payments queue with backoff, Razorpay gets nothing")
    void fs07_cascadeFailure() throws Exception {
        // Razorpay down: circuit tripped for cards
        circuits.forceOpen("razorpay", PaymentMethod.CARD);
        // PayU degrading: 80% success rate in the live window
        for (int i = 0; i < 25; i++) {
            metrics.recordAuthAttempt("payu", com.payflow.domain.AttemptOutcome.SUCCESS, 400);
            if (i % 5 != 0) metrics.recordCaptured("payu");
        }
        assertThat(circuits.health("payu", PaymentMethod.CARD).name()).isEqualTo("DEGRADED");

        // UPI traffic is unaffected
        Map<String, Object> upi = createOk(50_000, "UPI");
        assertThat(upi).containsEntry("gateway", "upi").containsEntry("state", "CAPTURED");

        // Card payment: Stripe at capacity (429 + Retry-After) and PayU failing -> queued, not failed
        MvcResult card = createPayment(UUID.randomUUID().toString(), paymentRequest(80_000, "CARD"),
                "X-Mock-Response", "stripe=rate-limit,payu=server-error", "X-Mock-Retry-After", "1");
        assertThat(card.getResponse().getStatus()).isEqualTo(202);
        Map<String, Object> parked = body(card);
        assertThat(parked).containsEntry("state", "ROUTE_SELECTED").containsKey("next_retry_at");
        assertThat(parked).extractingByKey("notice").asString().contains("PAYMENT_QUEUED_FOR_RETRY");
        Object id = parked.get("id");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_attempts WHERE gateway = 'razorpay'",
                Integer.class)).isZero();
        @SuppressWarnings("unchecked")
        Map<String, Object> stripeLimiter = (Map<String, Object>) body(getJson("/api/v1/admin/rate-limits")).get("stripe");
        assertThat(stripeLimiter).containsEntry("throttled", true);

        // The router never offers Razorpay while its circuit is open
        assertThat(rankedGateways("CARD", 80_000)).doesNotContain("razorpay");

        // After Retry-After the queued payment is retried asynchronously and succeeds
        jdbc.update("UPDATE transactions SET next_retry_at = NOW() - INTERVAL '1 second' WHERE id = ?::uuid", id.toString());
        rateLimiter.reset();
        assertThat(maintenance.retryParkedPayments()).isEqualTo(1);
        assertThat(state(id)).isEqualTo("CAPTURED");
    }

    // ------------------------------------------------------------------ FS-08
    @Test
    @DisplayName("FS-08 refund of an already-settled payment: SETTLED -> REFUND_INITIATED -> REFUNDED")
    void fs08_refundSettled() throws Exception {
        Map<String, Object> p = createOk(300_000, "CARD");
        reconciliation.run(ReconciliationRun.Trigger.MANUAL);
        assertThat(state(p.get("id"))).isEqualTo("SETTLED");

        MvcResult r = postJson("/api/v1/payments/" + p.get("id") + "/refund", Map.of("reason", "returned"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).containsEntry("state", "REFUNDED").containsEntry("refunded_paise", 300_000);
        assertThat(states(p.get("id"))).endsWith("SETTLED", "REFUND_INITIATED", "REFUNDED");
        List<Map<String, Object>> refunds = list(getJson("/api/v1/payments/" + p.get("id") + "/refunds"));
        assertThat(refunds).singleElement().satisfies(rf -> {
            assertThat(rf).containsEntry("state", "PROCESSED").containsEntry("amount_paise", 300_000);
            assertThat(rf.get("gateway_refund_id")).isNotNull();
        });
    }

    // ------------------------------------------------------------------ FS-09
    @Test
    @DisplayName("FS-09 concurrent identical requests on load-balanced servers: one proceeds, the rest get 409")
    void fs09_concurrentRace() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> req = paymentRequest(55_500, "CARD");
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return createPayment(key, req, "X-Mock-Delay-Ms", "300").getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) statuses.add(f.get(20, TimeUnit.SECONDS));
            assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
            assertThat(statuses).filteredOn(s -> s != 201).allMatch(s -> s == 409 || s == 200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class)).isEqualTo(1);
        long charges = ledger.chargesCreated("razorpay") + ledger.chargesCreated("stripe") + ledger.chargesCreated("payu");
        assertThat(charges).isEqualTo(1);
    }

    // ------------------------------------------------------------------ FS-10
    @Test
    @DisplayName("FS-10 replayed Razorpay webhook with a modified amount is rejected with 401 and logged with the IP")
    void fs10_replayAttack() throws Exception {
        Map<String, Object> p = createOk(10_000, "CARD");
        String original = genericEvent("evt_fs10", "captured", p.get("id"), null, 10_000L);
        Map<String, String> sig = signer.sign("razorpay", original.getBytes(StandardCharsets.UTF_8));
        String tampered = original.replace("10000", "1000000");

        var request = post("/api/v1/webhooks/razorpay").contentType(MediaType.APPLICATION_JSON)
                .content(tampered).header("X-Forwarded-For", "203.0.113.77");
        sig.forEach(request::header);
        MvcResult r = perform(request);

        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(r)).extractingByKey("error").asString().contains("INVALID_SIGNATURE");
        Map<String, Object> logged = jdbc.queryForMap("SELECT event_type, gateway, source_ip FROM security_audit_log "
                + "ORDER BY id DESC LIMIT 1");
        assertThat(logged).containsEntry("event_type", "WEBHOOK_SIGNATURE_INVALID")
                .containsEntry("gateway", "razorpay").containsEntry("source_ip", "203.0.113.77");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_webhook_events", Integer.class)).isZero();
        assertThat(getPayment(p.get("id")).get("captured_paise")).isEqualTo(10_000);
    }

    // ------------------------------------------------------------------ FS-11
    @Test
    @DisplayName("FS-11 settlement report shows 5 captured payments failed/reversed: anomalies, mismatch, alert, no refund")
    void fs11_reconciliationMismatch() throws Exception {
        List<Map<String, Object>> broken = new ArrayList<>();
        for (int i = 0; i < 5; i++) broken.add(createOk(40_000 + i, "CARD"));
        Map<String, Object> healthy = createOk(41_000, "CARD");
        for (int i = 0; i < 5; i++) {
            Map<String, Object> p = broken.get(i);
            postJson("/api/v1/mock/gateways/" + p.get("gateway") + "/settlement",
                    Map.of("reference", p.get("gateway_reference"), "status", i % 2 == 0 ? "FAILED" : "REVERSED"));
        }
        int alertsBefore = alerts.recent().size();

        Map<String, Object> run = body(postJson("/api/v1/reconciliation/trigger", null));
        assertThat(run).containsEntry("anomalies", 5).containsEntry("status", "COMPLETED");

        for (Map<String, Object> p : broken) {
            assertThat(state(p.get("id"))).isEqualTo("RECONCILIATION_MISMATCH");
        }
        assertThat(state(healthy.get("id"))).isEqualTo("SETTLED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM anomalies WHERE severity = 'CRITICAL' "
                + "AND anomaly_type = 'SETTLEMENT_MISMATCH'", Integer.class)).isEqualTo(5);
        assertThat(alerts.recent().size() - alertsBefore).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(refunded_paise), 0) FROM transactions", Long.class)).isZero();

        Map<String, Object> report = body(getJson("/api/v1/reconciliation/reports/" + run.get("run_id")));
        assertThat((List<?>) report.get("anomalies")).hasSize(5);

        // Human review: one is confirmed settled, one is refunded deliberately
        String anomaly = jdbc.queryForObject("SELECT id::text FROM anomalies WHERE transaction_id = ?::uuid",
                String.class, broken.get(0).get("id").toString());
        assertThat(body(postJson("/api/v1/admin/anomalies/" + anomaly + "/resolve?resolution=SETTLED", null)))
                .containsEntry("status", "RESOLVED");
        assertThat(state(broken.get(0).get("id"))).isEqualTo("SETTLED");
        assertThat(body(postJson("/api/v1/payments/" + broken.get(1).get("id") + "/refund", null)))
                .containsEntry("state", "REFUNDED");
    }

    // ------------------------------------------------------------------ FS-12
    @Test
    @DisplayName("FS-12 UPI collect not approved within the mandate window: polled, AUTH_EXPIRED, notified, no retry")
    void fs12_upiCollectTimeoutPolled() throws Exception {
        Map<String, Object> req = paymentRequest(25_000, "UPI");
        req.put("upi_flow", "COLLECT");
        MvcResult r = createPayment(UUID.randomUUID().toString(), req);
        assertThat(r.getResponse().getStatus()).isEqualTo(202);
        Map<String, Object> p = body(r);
        assertThat(p).containsEntry("state", "AUTH_INITIATED");
        assertThat(p.get("auth_expires_at")).isNotNull();

        // 5 minutes pass without approval
        jdbc.update("UPDATE transactions SET auth_expires_at = NOW() - INTERVAL '1 second' WHERE id = ?::uuid",
                p.get("id").toString());
        assertThat(maintenance.expireUpiCollects()).isEqualTo(1);

        assertThat(state(p.get("id"))).isEqualTo("AUTH_EXPIRED");
        assertThat(states(p.get("id"))).endsWith("AUTH_INITIATED", "AUTH_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE transaction_id = ?::uuid "
                + "AND template = 'UPI_COLLECT_EXPIRED'", Integer.class, p.get("id").toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_attempts WHERE transaction_id = ?::uuid "
                + "AND operation = 'AUTH'", Integer.class, p.get("id").toString())).isEqualTo(1);
        assertThat(maintenance.retryParkedPayments()).isZero();
    }

    @Test
    @DisplayName("FS-12 UPI callback confirms EXPIRED: AUTH_INITIATED -> AUTH_EXPIRED")
    void fs12_upiCallbackExpired() throws Exception {
        Map<String, Object> req = paymentRequest(25_000, "UPI");
        req.put("upi_flow", "COLLECT");
        Map<String, Object> p = body(createPayment(UUID.randomUUID().toString(), req));
        MvcResult cb = postJson("/api/v1/mock/upi/" + p.get("id") + "/callback", Map.of("status", "EXPIRED"));
        assertThat(cb.getResponse().getStatus()).isEqualTo(200);
        assertThat(state(p.get("id"))).isEqualTo("AUTH_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE transaction_id = ?::uuid",
                Integer.class, p.get("id").toString())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ FS-13
    @Test
    @DisplayName("FS-13 the same idempotency key from two merchants creates two payments")
    void fs13_merchantScopedKeys() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> req = paymentRequest(12_300, "CARD");
        MvcResult a = perform(authed(post("/api/v1/payments")).header("Idempotency-Key", key)
                .header("X-Merchant-Id", "merchant_a").content(json.writeValueAsString(req)));
        MvcResult b = perform(authed(post("/api/v1/payments")).header("Idempotency-Key", key)
                .header("X-Merchant-Id", "merchant_b").content(json.writeValueAsString(req)));
        assertThat(a.getResponse().getStatus()).isEqualTo(201);
        assertThat(b.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(a).get("id")).isNotEqualTo(body(b).get("id"));
        assertThat(body(a)).containsEntry("merchant_id", "merchant_a");
        assertThat(body(b)).containsEntry("merchant_id", "merchant_b");

        MvcResult replay = perform(authed(post("/api/v1/payments")).header("Idempotency-Key", key)
                .header("X-Merchant-Id", "merchant_a").content(json.writeValueAsString(req)));
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(body(replay).get("id")).isEqualTo(body(a).get("id"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_keys WHERE key = ?", Integer.class, key))
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ FS-14
    @Test
    @DisplayName("FS-14 database connection exhaustion: internal circuit breaker fails fast with 503")
    void fs14_databaseCircuitBreaker() throws Exception {
        for (int i = 0; i < 5; i++) dbGuard.onConnectionFailure();
        MvcResult r = getJson("/api/v1/gateways");
        assertThat(r.getResponse().getStatus()).isEqualTo(503);
        assertThat(r.getResponse().getHeader("Retry-After")).isEqualTo("2");
        assertThat(body(r)).extractingByKey("error").asString().contains("SERVICE_UNAVAILABLE");
        // health stays reachable so orchestrators can see the degraded state
        assertThat(perform(get("/api/v1/health")).getResponse().getStatus()).isEqualTo(200);
        dbGuard.reset();
        assertThat(getJson("/api/v1/gateways").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("FS-14 traffic spike: a burst larger than the pool completes without corruption")
    void fs14_burst() throws Exception {
        int n = 60;
        ExecutorService pool = Executors.newFixedThreadPool(30);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> createPayment(UUID.randomUUID().toString(), paymentRequest(10_000, "UPI"))));
            }
            int ok = 0;
            for (Future<MvcResult> f : futures) {
                int s = f.get(60, TimeUnit.SECONDS).getResponse().getStatus();
                assertThat(s).isIn(201, 503);
                if (s == 201) ok++;
            }
            assertThat(ok).isPositive();
        } finally {
            pool.shutdownNow();
        }
        // Integrity: every CAPTURED payment has exactly one CAPTURED transition and its full amount
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions t WHERE state = 'CAPTURED' AND "
                + "(captured_paise <> amount_paise OR (SELECT COUNT(*) FROM transaction_state_log l WHERE "
                + "l.transaction_id = t.id AND l.to_state = 'CAPTURED' AND l.from_state <> 'CAPTURED') <> 1)",
                Integer.class)).isZero();
    }

    // ------------------------------------------------------------------ FS-15
    @Test
    @DisplayName("FS-15 a buggy handler moving CREATED -> REFUNDED is rejected and logged as REJECTED_TRANSITION")
    void fs15_corruptionAttempt() throws Exception {
        Transaction t = new Transaction();
        t.setMerchantId("default");
        t.setIdempotencyKey("fs15");
        t.setMerchantOrderId("ORD-FS15");
        t.setAmountPaise(10_000);
        t.setCurrency("INR");
        t.setPaymentMethod(PaymentMethod.CARD);
        t.setTraceId(UUID.randomUUID());
        UUID id = transactions.save(t).getId();

        InvalidStateTransitionException e = assertThrows(InvalidStateTransitionException.class,
                () -> machine.transition(id, TransactionState.REFUNDED, Audit.of("REFUND_COMPLETED", "buggy_refund_handler")));
        assertThat(e.getMessage()).contains("CREATED -> REFUNDED")
                .contains("Valid transitions from CREATED: [ABANDONED, FAILED, ROUTE_SELECTED]");
        assertThat(state(id)).isEqualTo("CREATED");
        Map<String, Object> rejected = jdbc.queryForMap("SELECT from_state, to_state, created_by, "
                + "metadata->>'valid_transitions' AS valid FROM transaction_state_log WHERE transaction_id = ? "
                + "AND event = 'REJECTED_TRANSITION'", id);
        assertThat(rejected).containsEntry("from_state", "CREATED").containsEntry("to_state", "REFUNDED")
                .containsEntry("created_by", "buggy_refund_handler");
        assertThat((String) rejected.get("valid")).contains("ROUTE_SELECTED");

        // The same attempt through the API returns a clear 409 listing the valid transitions
        MvcResult r = postJson("/api/v1/payments/" + id + "/refund", null);
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) body(r).get("error");
        assertThat(error).containsEntry("code", "INVALID_STATE_TRANSITION");
        assertThat(error.get("details").toString()).contains("valid_transitions").contains("ROUTE_SELECTED");
    }

    @Test
    @DisplayName("FS-01 + reconciliation: a payment stuck in AUTH_INITIATED is resolved from the gateway status")
    void staleAuthResolvedByReconciliation() throws Exception {
        Map<String, Object> p = createOk(70_000, "CARD");
        // Simulate a crash after the gateway authorised but before we recorded it
        jdbc.update("UPDATE transactions SET state = 'AUTH_INITIATED', captured_paise = 0, "
                + "updated_at = NOW() - INTERVAL '10 minutes' WHERE id = ?::uuid", p.get("id").toString());
        ReconciliationRun run = reconciliation.run(ReconciliationRun.Trigger.MANUAL, Duration.ofMinutes(5));
        assertThat(run.getOverridden()).isEqualTo(1);
        assertThat(state(p.get("id"))).isIn("CAPTURED", "SETTLED");
        assertThat(timeline(p.get("id"))).anyMatch(e -> AuditEvent.RECONCILIATION_OVERRIDE.equals(e.get("event")));
        assertThat(ledger.chargesCreated((String) p.get("gateway"))).isEqualTo(1);
        assertThat(GatewayStatus.parse("captured")).isEqualTo(GatewayStatus.CAPTURED);
    }
}
