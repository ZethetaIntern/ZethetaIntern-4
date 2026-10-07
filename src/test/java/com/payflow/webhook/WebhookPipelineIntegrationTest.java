package com.payflow.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.jobs.MaintenanceService;
import com.payflow.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** The A5.2 pipeline end to end, including the C4.3 verification steps and the A8.3 DLQ. */
class WebhookPipelineIntegrationTest extends IntegrationTest {

    @Autowired MaintenanceService maintenance;

    private Map<String, Object> manualPayment(long amount) throws Exception {
        Map<String, Object> req = paymentRequest(amount, "CARD");
        req.put("capture_mode", "MANUAL");
        return body(createPayment(UUID.randomUUID().toString(), req));
    }

    @Test
    void validWebhookIsProcessedAndAudited() throws Exception {
        Map<String, Object> p = manualPayment(30_000);
        String gw = (String) p.get("gateway");
        MvcResult r = webhook(gw, genericEvent("evt_ok", "captured", p.get("id"), (String) p.get("gateway_reference"), 30_000L));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(r)).containsEntry("status", "processed").containsEntry("result", "APPLIED");
        assertThat(state(p.get("id"))).isEqualTo("CAPTURED");
        Map<String, Object> audit = jdbc.queryForMap("SELECT created_by, metadata->>'event_id' AS ev, "
                + "metadata->>'trace_id' AS trace FROM transaction_state_log WHERE transaction_id = ?::uuid "
                + "AND to_state = 'CAPTURED'", p.get("id").toString());
        assertThat(audit).containsEntry("created_by", "webhook_processor").containsEntry("ev", "evt_ok");
        // A8.5 correlation: the webhook's audit row carries the payment's original trace id
        assertThat(audit.get("trace")).isEqualTo(p.get("trace_id"));
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_queue WHERE event_id = 'evt_ok'", String.class))
                .isEqualTo("COMPLETED");
    }

    @Test
    void amountMismatchIsRejectedDeadLetteredAndLogged() throws Exception {
        Map<String, Object> p = manualPayment(50_000_00L);
        MvcResult r = webhook((String) p.get("gateway"), genericEvent("evt_fraud", "captured", p.get("id"),
                (String) p.get("gateway_reference"), 1_000L));
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(r).toString()).contains("WEBHOOK_VERIFICATION_FAILED").contains("AMOUNT_MISMATCH");
        assertThat(state(p.get("id"))).isEqualTo("AUTHORISED");
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_queue WHERE event_id = 'evt_fraud'", String.class))
                .isEqualTo("DLQ");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM security_audit_log WHERE event_type = "
                + "'WEBHOOK_VERIFICATION_FAILED'", Integer.class)).isEqualTo(1);
        assertThat(body(getJson("/api/v1/admin/webhooks/dlq"))).containsEntry("depth", 1);
    }

    @Test
    void currencyAndReferenceMismatchesAreRejected() throws Exception {
        Map<String, Object> p = manualPayment(20_000);
        String gw = (String) p.get("gateway");
        String wrongCurrency = genericEvent("evt_cur", "captured", p.get("id"), null, 20_000L).replace("\"INR\"", "\"USD\"");
        assertThat(body(webhook(gw, wrongCurrency)).toString()).contains("CURRENCY_MISMATCH");
        String wrongRef = genericEvent("evt_ref", "captured", p.get("id"), "pay_someoneelse", 20_000L);
        assertThat(body(webhook(gw, wrongRef)).toString()).contains("GATEWAY_REFERENCE_MISMATCH");
        assertThat(state(p.get("id"))).isEqualTo("AUTHORISED");
    }

    @Test
    void outOfOrderSettlementIsDeferredThenAppliedAfterCapture() throws Exception {
        Map<String, Object> p = manualPayment(15_000);
        String gw = (String) p.get("gateway");
        MvcResult settledFirst = webhook(gw, genericEvent("evt_settle", "settled", p.get("id"),
                (String) p.get("gateway_reference"), null));
        assertThat(body(settledFirst)).containsEntry("status", "queued").containsEntry("result", "DEFERRED");
        postJson("/api/v1/payments/" + p.get("id") + "/capture", null);
        assertThat(state(p.get("id"))).isEqualTo("CAPTURED");

        jdbc.update("UPDATE webhook_queue SET next_retry_at = NOW() WHERE event_id = 'evt_settle'");
        assertThat(maintenance.processWebhookQueue()).isEqualTo(1);
        assertThat(state(p.get("id"))).isEqualTo("SETTLED");
    }

    @Test
    void eventThatNeverBecomesApplicableEndsInTheDlqAndCanBeReplayed() throws Exception {
        Map<String, Object> p = manualPayment(15_000);
        String gw = (String) p.get("gateway");
        webhook(gw, genericEvent("evt_stuck", "settled", p.get("id"), (String) p.get("gateway_reference"), null));
        for (int i = 0; i < 3; i++) {
            jdbc.update("UPDATE webhook_queue SET next_retry_at = NOW() WHERE event_id = 'evt_stuck'");
            maintenance.processWebhookQueue();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_queue WHERE event_id = 'evt_stuck'", String.class))
                .isEqualTo("DLQ");
        Long queueId = jdbc.queryForObject("SELECT id FROM webhook_queue WHERE event_id = 'evt_stuck'", Long.class);

        postJson("/api/v1/payments/" + p.get("id") + "/capture", null); // root cause fixed
        Map<String, Object> replay = body(postJson("/api/v1/admin/webhooks/dlq/" + queueId + "/replay", null));
        assertThat(replay).containsEntry("result", "APPLIED");
        assertThat(state(p.get("id"))).isEqualTo("SETTLED");
        assertThat(postJson("/api/v1/admin/webhooks/dlq/" + queueId + "/replay", null).getResponse().getStatus())
                .isEqualTo(422);
    }

    @Test
    void postCaptureReversalRaisesAnAnomalyWithoutRefunding() throws Exception {
        Map<String, Object> p = createOk(42_000, "CARD");
        webhook((String) p.get("gateway"), genericEvent("evt_rev", "reversed", p.get("id"),
                (String) p.get("gateway_reference"), null));
        assertThat(state(p.get("id"))).isEqualTo("RECONCILIATION_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT anomaly_type FROM anomalies", String.class)).isEqualTo("POST_CAPTURE_REVERSAL");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refunds", Integer.class)).isZero();
    }

    @Test
    void refundStartedFromTheGatewayDashboardIsRecorded() throws Exception {
        Map<String, Object> p = createOk(42_000, "CARD");
        String body = json.writeValueAsString(Map.of("event_id", "evt_dash_refund", "status", "refunded",
                "gateway_reference", p.get("gateway_reference"), "amount", 12_000, "refund_id", "rfnd_dash"));
        webhook((String) p.get("gateway"), body);
        assertThat(state(p.get("id"))).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(getPayment(p.get("id"))).containsEntry("refunded_paise", 12_000);
        // a second delivery of the same refund under a new event id is not double counted
        webhook((String) p.get("gateway"), body.replace("evt_dash_refund", "evt_dash_refund_2"));
        assertThat(getPayment(p.get("id"))).containsEntry("refunded_paise", 12_000);
    }

    @Test
    void disputeLifecycle() throws Exception {
        Map<String, Object> p = createOk(9_900, "CARD");
        String gw = (String) p.get("gateway");
        String ref = (String) p.get("gateway_reference");
        webhook(gw, genericEvent("evt_d1", "dispute_opened", null, ref, null));
        assertThat(state(p.get("id"))).isEqualTo("DISPUTE_OPENED");
        webhook(gw, genericEvent("evt_d2", "dispute_resolved", null, ref, null));
        assertThat(state(p.get("id"))).isEqualTo("DISPUTE_RESOLVED");
    }

    @Test
    void lateSuccessFromAnAbandonedGatewayIsTreatedAsAnOrphanCharge() throws Exception {
        Map<String, Object> p = createOk(33_000, "CARD");
        String other = List.of("razorpay", "stripe", "payu").stream().filter(g -> !g.equals(p.get("gateway")))
                .findFirst().orElseThrow();
        MvcResult r = webhook(other, genericEvent("evt_orphan", "authorized", p.get("id"), "ref_orphan", 33_000L));
        assertThat(body(r).get("reason").toString()).startsWith("orphan_charge_on_");
        assertThat(jdbc.queryForObject("SELECT anomaly_type FROM anomalies", String.class)).isEqualTo("ORPHAN_GATEWAY_CHARGE");
        assertThat(state(p.get("id"))).isEqualTo("CAPTURED");
    }

    @Test
    void unmatchedAndMalformedWebhooks() throws Exception {
        MvcResult unmatched = webhook("stripe", genericEvent("evt_none", "captured", UUID.randomUUID(), "pi_none", 1L));
        assertThat(body(unmatched)).containsEntry("reason", "unmatched_event");
        MvcResult malformed = webhook("payu", "not json at all");
        assertThat(malformed.getResponse().getStatus()).isEqualTo(400);
        MvcResult unknown = webhook("razorpay", "{\"event_id\":\"x\"}");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void nativeGatewayPayloadsFlowThroughThePipeline() throws Exception {
        Map<String, Object> req = paymentRequest(25_000, "UPI");
        req.put("upi_flow", "COLLECT");
        Map<String, Object> p = body(createPayment(UUID.randomUUID().toString(), req));
        String callback = "{\"txnId\":\"" + p.get("gateway_reference") + "\",\"merchantTxnRef\":\"" + p.get("id")
                + "\",\"status\":\"SUCCESS\",\"amount\":\"250.00\"}";
        assertThat(body(webhook("upi", callback))).containsEntry("result", "APPLIED");
        assertThat(state(p.get("id"))).isEqualTo("CAPTURED");
    }

    @Test
    void upiCollectDeclinedByCustomer() throws Exception {
        Map<String, Object> req = paymentRequest(25_000, "UPI");
        req.put("upi_flow", "COLLECT");
        Map<String, Object> p = body(createPayment(UUID.randomUUID().toString(), req));
        postJson("/api/v1/mock/upi/" + p.get("id") + "/callback", Map.of("status", "FAILURE"));
        assertThat(state(p.get("id"))).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE template = 'PAYMENT_FAILED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void queueStatusEndpointReportsCounts() throws Exception {
        Map<String, Object> dlq = body(getJson("/api/v1/admin/webhooks/dlq"));
        assertThat(dlq).containsKeys("depth", "queue", "items");
    }
}
