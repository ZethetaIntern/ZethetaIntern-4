package com.payflow.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.db.DatabaseGuard;
import com.payflow.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Background jobs and the database schema requirements (A6.1, A6.2). */
class MaintenanceAndSchemaIntegrationTest extends IntegrationTest {

    @Autowired MaintenanceService maintenance;
    @Autowired DatabaseGuard dbGuard;

    @AfterEach
    void reset() {
        dbGuard.reset();
    }

    @Test
    void authorisationHoldExpiresAfterTheHoldPeriod() throws Exception {
        Map<String, Object> req = paymentRequest(10_000, "CARD");
        req.put("capture_mode", "MANUAL");
        Map<String, Object> p = body(createPayment(UUID.randomUUID().toString(), req));
        assertThat(p.get("auth_expires_at")).isNotNull();
        jdbc.update("UPDATE transactions SET auth_expires_at = NOW() - INTERVAL '1 minute' WHERE id = ?::uuid",
                p.get("id").toString());
        assertThat(maintenance.expireAuthorisationHolds()).isEqualTo(1);
        assertThat(state(p.get("id"))).isEqualTo("AUTH_EXPIRED");
        assertThat(getPayment(p.get("id"))).containsEntry("released_paise", 10_000);
    }

    @Test
    void unroutedPaymentsAreAbandoned() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO transactions (id, merchant_id, idempotency_key, merchant_order_id, amount_paise, currency, "
                + "payment_method, state, trace_id, created_at) VALUES (?, 'default', 'k', 'o', 100, 'INR', 'CARD', "
                + "'CREATED', ?, NOW() - INTERVAL '1 hour')", id, UUID.randomUUID());
        assertThat(maintenance.abandonStalePayments()).isEqualTo(1);
        assertThat(state(id)).isEqualTo("ABANDONED");
    }

    @Test
    void healthMetricsAreFlushedPerMinute() {
        long lastMinute = com.payflow.routing.GatewayMetricsService.minuteOf(java.time.Instant.now()) - 1;
        // record into the previous minute's bucket by reading back through the window API
        metrics.recordAuthAttempt("upi", com.payflow.domain.AttemptOutcome.SUCCESS, 120);
        assertThat(metrics.window("upi", lastMinute + 1, lastMinute + 1).attempts()).isEqualTo(1);
        // the flush writes the minute that just closed; nothing was recorded there yet
        assertThat(maintenance.flushHealthMetrics()).isZero();
        java.time.Instant previous = java.time.Instant.ofEpochSecond(lastMinute * 60 + 30);
        metrics.recordAuthAttempt("upi", com.payflow.domain.AttemptOutcome.SUCCESS, 200, previous);
        metrics.recordAuthAttempt("upi", com.payflow.domain.AttemptOutcome.TIMEOUT, 900, previous);
        assertThat(maintenance.flushHealthMetrics()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT total_count, p95_latency_ms, health_status FROM gateway_health_metrics "
                + "WHERE gateway = 'upi'")).containsEntry("total_count", 2).containsEntry("p95_latency_ms", 900)
                .containsEntry("health_status", "HEALTHY");
        assertThat(maintenance.flushHealthMetrics()).as("idempotent per minute").isZero();
    }

    @Test
    void notificationsAreDispatched() throws Exception {
        createPayment(UUID.randomUUID().toString(), paymentRequest(1_000, "UPI"), "X-Mock-Response", "decline");
        assertThat(maintenance.dispatchNotifications()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM notification_outbox", String.class)).isEqualTo("SENT");
        assertThat(maintenance.dispatchNotifications()).isZero();
    }

    @Test
    void idempotencyKeysArePurged() throws Exception {
        createOk(1_000, "UPI");
        jdbc.update("UPDATE idempotency_keys SET expires_at = NOW() - INTERVAL '1 day'");
        assertThat(maintenance.purgeIdempotencyKeys()).isEqualTo(1);
    }

    @Test
    void parkedPaymentRetriesUntilMaxRetriesThenFails() throws Exception {
        Map<String, Object> p = body(createPayment(UUID.randomUUID().toString(), paymentRequest(5_000, "UPI"),
                "X-Mock-Response", "upi=server-error"));
        assertThat(p).containsEntry("state", "ROUTE_SELECTED").containsEntry("retry_count", 1);
        jdbc.update("UPDATE transactions SET retry_count = 3, next_retry_at = NOW() - INTERVAL '1 second' "
                + "WHERE id = ?::uuid", p.get("id").toString());
        // the gateway is still failing (simulated by tripping its circuit) when the last retry runs
        circuits.forceOpen("upi", com.payflow.domain.PaymentMethod.UPI);
        assertThat(maintenance.retryParkedPayments()).isEqualTo(1);
        assertThat(state(p.get("id"))).isEqualTo("FAILED");
        assertThat(getPayment(p.get("id"))).containsEntry("failure_code", "NO_ELIGIBLE_GATEWAY");
    }

    @Test
    void webhookQueueWorkerHasNothingToDoWhenEmpty() {
        assertThat(maintenance.processWebhookQueue()).isZero();
        assertThat(maintenance.expireUpiCollects()).isZero();
    }

    @Test
    void databaseGuardOpensAfterRepeatedConnectionFailures() {
        assertThat(dbGuard.allowRequest()).isTrue();
        for (int i = 0; i < 5; i++) dbGuard.onConnectionFailure();
        assertThat(dbGuard.isOpen()).isTrue();
        assertThat(dbGuard.allowRequest()).isFalse();
        assertThat(dbGuard.poolStats()).containsEntry("circuit", "OPEN").containsKeys("active", "max");
        assertThat(dbGuard.underPressure()).isFalse();
        dbGuard.reset();
        assertThat(dbGuard.allowRequest()).isTrue();
    }

    // ------------------------------------------------------------ schema checks

    @Test
    void allTablesRequiredByA61Exist() {
        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains("transactions", "transaction_state_log", "gateway_routes", "idempotency_keys",
                "processed_webhook_events", "gateway_health_metrics", "reconciliation_log", "refunds",
                "gateway_config", "routing_config");
    }

    @Test
    void moneyIsNeverStoredAsFloatingPoint() {
        List<Map<String, Object>> money = jdbc.queryForList("SELECT table_name, column_name, data_type FROM "
                + "information_schema.columns WHERE table_schema = 'public' AND column_name LIKE '%paise%'");
        assertThat(money).isNotEmpty().allSatisfy(c -> assertThat(c.get("data_type")).isEqualTo("bigint"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = 'public' "
                + "AND (column_name LIKE '%amount%' OR column_name LIKE '%fee%') "
                + "AND data_type IN ('real', 'double precision')", Integer.class)).isZero();
    }

    @Test
    void auditColumnsMatchA23() {
        List<String> cols = jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE "
                + "table_name = 'transaction_state_log'", String.class);
        assertThat(cols).contains("id", "transaction_id", "from_state", "to_state", "event", "gateway_reference",
                "gateway_response", "metadata", "created_at", "created_by");
        assertThat(jdbc.queryForObject("SELECT data_type FROM information_schema.columns WHERE table_name = "
                + "'transaction_state_log' AND column_name = 'gateway_response'", String.class)).isEqualTo("jsonb");
    }

    @Test
    void historicalDatasetFromA34IsSeeded() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gateway_historical_performance", Integer.class)).isEqualTo(16);
        assertThat(jdbc.queryForObject("SELECT p95_latency_ms FROM gateway_historical_performance WHERE gateway = 'payu' "
                + "AND band_start_hour = 12", Integer.class)).isEqualTo(950);
    }
}
