package com.payflow.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.entity.ReconciliationRun;
import com.payflow.support.IntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A5.5 reconciliation: stale overrides, settlement matching, and the B3 performance target. */
class ReconciliationIntegrationTest extends IntegrationTest {

    @Autowired ReconciliationService reconciliation;

    private Map<String, Object> manual(long amount) throws Exception {
        Map<String, Object> req = paymentRequest(amount, "CARD");
        req.put("capture_mode", "MANUAL");
        return body(createPayment(UUID.randomUUID().toString(), req));
    }

    private void makeStale(Object id, String state) {
        jdbc.update("UPDATE transactions SET state = ?, updated_at = NOW() - INTERVAL '10 minutes' WHERE id = ?::uuid",
                state, id.toString());
    }

    @Test
    void staleAuthInitiatedFailedAtGatewayBecomesFailed() throws Exception {
        Map<String, Object> p = manual(10_000);
        makeStale(p.get("id"), "AUTH_INITIATED");
        postJson("/api/v1/mock/gateways/" + p.get("gateway") + "/status", Map.of("reference", p.get("gateway_reference"),
                "status", "FAILED"));
        ReconciliationRun run = reconciliation.run(ReconciliationRun.Trigger.MANUAL);
        assertThat(run.getOverridden()).isEqualTo(1);
        assertThat(state(p.get("id"))).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_state_log WHERE transaction_id = ?::uuid "
                + "AND event = 'RECONCILIATION_OVERRIDE'", Integer.class, p.get("id").toString())).isEqualTo(2);
    }

    @Test
    void staleCaptureInitiatedThatTheGatewayCapturedBecomesCaptured() throws Exception {
        Map<String, Object> p = manual(10_000);
        makeStale(p.get("id"), "CAPTURE_INITIATED");
        postJson("/api/v1/mock/gateways/" + p.get("gateway") + "/status", Map.of("reference", p.get("gateway_reference"),
                "status", "CAPTURED"));
        reconciliation.run(ReconciliationRun.Trigger.MANUAL);
        assertThat(state(p.get("id"))).isIn("CAPTURED", "SETTLED");
        assertThat(getPayment(p.get("id"))).containsEntry("captured_paise", 10_000);
    }

    @Test
    void staleCaptureInitiatedNotCapturedBecomesCaptureFailed() throws Exception {
        Map<String, Object> p = manual(10_000);
        makeStale(p.get("id"), "CAPTURE_INITIATED");
        reconciliation.run(ReconciliationRun.Trigger.MANUAL);
        assertThat(state(p.get("id"))).isEqualTo("CAPTURE_FAILED");
    }

    @Test
    void staleUpiCollectThatExpiredBecomesAuthExpired() throws Exception {
        Map<String, Object> req = paymentRequest(10_000, "UPI");
        req.put("upi_flow", "COLLECT");
        Map<String, Object> p = body(createPayment(UUID.randomUUID().toString(), req));
        makeStale(p.get("id"), "AUTH_INITIATED");
        postJson("/api/v1/mock/gateways/upi/status", Map.of("reference", p.get("gateway_reference"), "status", "EXPIRED"));
        reconciliation.run(ReconciliationRun.Trigger.MANUAL);
        assertThat(state(p.get("id"))).isEqualTo("AUTH_EXPIRED");
    }

    @Test
    void staleWithoutGatewayKnowledgeIsLoggedNotChanged() throws Exception {
        Map<String, Object> p = manual(10_000);
        makeStale(p.get("id"), "AUTH_INITIATED");
        ledger.reset(); // the gateway has no record of the payment
        ReconciliationRun run = reconciliation.run(ReconciliationRun.Trigger.MANUAL);
        assertThat(run.getStale()).isEqualTo(1);
        assertThat(run.getOverridden()).isZero();
        assertThat(state(p.get("id"))).isEqualTo("AUTH_INITIATED");
        assertThat(jdbc.queryForObject("SELECT discrepancy_type FROM reconciliation_log WHERE transaction_id = ?::uuid",
                String.class, p.get("id").toString())).isEqualTo("STALE_NO_GATEWAY_STATUS");
    }

    @Test
    void staleRefundInitiatedResolvedFromGateway() throws Exception {
        Map<String, Object> p = createOk(10_000, "CARD");
        postJson("/api/v1/payments/" + p.get("id") + "/refund", null);
        makeStale(p.get("id"), "REFUND_INITIATED");
        jdbc.update("UPDATE transactions SET refunded_paise = 0 WHERE id = ?::uuid", p.get("id").toString());
        reconciliation.run(ReconciliationRun.Trigger.MANUAL);
        assertThat(state(p.get("id"))).isEqualTo("REFUNDED");
    }

    @Test
    void settlementPendingLeavesThePaymentCaptured() throws Exception {
        Map<String, Object> p = createOk(10_000, "CARD");
        postJson("/api/v1/mock/gateways/" + p.get("gateway") + "/settlement",
                Map.of("reference", p.get("gateway_reference"), "status", "PENDING"));
        ReconciliationRun run = reconciliation.run(ReconciliationRun.Trigger.MANUAL);
        assertThat(run.getSettled()).isZero();
        assertThat(state(p.get("id"))).isEqualTo("CAPTURED");
    }

    @Test
    void settledPaymentReportedReversedLaterIsFlagged() throws Exception {
        Map<String, Object> p = createOk(10_000, "CARD");
        reconciliation.run(ReconciliationRun.Trigger.MANUAL);
        assertThat(state(p.get("id"))).isEqualTo("SETTLED");
        assertThat(getPayment(p.get("id")).get("settlement_batch_id")).isNotNull();
        postJson("/api/v1/mock/gateways/" + p.get("gateway") + "/settlement",
                Map.of("reference", p.get("gateway_reference"), "status", "REVERSED"));
        ReconciliationRun run = reconciliation.run(ReconciliationRun.Trigger.MANUAL);
        assertThat(run.getAnomalies()).isEqualTo(1);
        assertThat(state(p.get("id"))).isEqualTo("RECONCILIATION_MISMATCH");
    }

    @Test
    void tenThousandTransactionsReconcileWithinThirtySeconds() {
        int n = 10_000;
        List<Object[]> rows = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            UUID id = UUID.randomUUID();
            String ref = "pay_perf" + i;
            rows.add(new Object[]{id, "k" + i, "ORD-" + i, ref, UUID.randomUUID()});
            ledger.overrideSettlement("razorpay", ref, com.payflow.gateway.GatewayStatus.SETTLED);
        }
        jdbc.batchUpdate("INSERT INTO transactions (id, merchant_id, idempotency_key, merchant_order_id, amount_paise, "
                + "currency, payment_method, state, gateway, gateway_reference, captured_paise, trace_id) "
                + "VALUES (?, 'default', ?, ?, 10000, 'INR', 'CARD', 'CAPTURED', 'razorpay', ?, 10000, ?)", rows);

        long start = System.nanoTime();
        ReconciliationRun run = reconciliation.run(ReconciliationRun.Trigger.MANUAL);
        long ms = (System.nanoTime() - start) / 1_000_000;

        assertThat(run.getSettled()).isEqualTo(n);
        assertThat(ms).as("B3: reconciliation of 10,000 transactions < 30 s").isLessThan(30_000);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions WHERE state = 'SETTLED'", Integer.class)).isEqualTo(n);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_state_log WHERE event = 'SETTLEMENT_CONFIRMED'",
                Integer.class)).isEqualTo(n);
        System.out.println("[benchmark] reconciliation of " + n + " transactions: " + ms + " ms"
                + " (run duration " + Duration.ofMillis(run.getDurationMs()) + ")");
    }
}
