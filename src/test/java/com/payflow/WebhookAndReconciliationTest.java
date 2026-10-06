package com.payflow;

import com.payflow.domain.TransactionState;
import com.payflow.service.PaymentService;
import com.payflow.service.WebhookService;
import com.payflow.service.WebhookService.Result;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class WebhookAndReconciliationTest {

    @Autowired WebhookService webhooks;
    @Autowired PaymentService payments;
    @Autowired com.payflow.service.ReconciliationService reconciliation;

    private String signed(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("whsec_test_secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Result postEvent(String id, String status, String txnId) {
        String body = "{\"event_id\":\"" + id + "\",\"event_type\":\"payment." + status + "\""
                + (txnId == null ? "" : ",\"transaction_id\":\"" + txnId + "\"") + ",\"status\":\"" + status + "\"}";
        return webhooks.ingest("razorpay", signed(body), body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void invalidSignatureRejected() {
        Result r = webhooks.ingest("razorpay", "deadbeef",
                "{\"event_id\":\"e1\"}".getBytes(StandardCharsets.UTF_8));
        assertFalse(r.accepted());
        assertEquals("invalid_signature", r.reason());
    }

    @Test
    void duplicateEventDeduplicated() {
        Result first = postEvent("dedup-java-1", "succeeded", "nonexistent");
        Result second = postEvent("dedup-java-1", "succeeded", "nonexistent");
        assertTrue(first.accepted());
        assertTrue(second.accepted());
        assertTrue(second.deduplicated());
    }

    @Test
    void concurrentDuplicateDeliveriesReconcileOnlyOnce() throws Exception {
        var txn = payments.create("wh-concurrent-1", "ORD-WH-CONCURRENT", 50000, "INR", "upi");
        states(txn.getId());
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> postEvent("wh-concurrent-event", "succeeded", txn.getId()));
            var second = pool.submit(() -> postEvent("wh-concurrent-event", "succeeded", txn.getId()));
            Result a = first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            Result b = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, (a.reconciled() ? 1 : 0) + (b.reconciled() ? 1 : 0));
            assertEquals(1, (a.deduplicated() ? 1 : 0) + (b.deduplicated() ? 1 : 0));
            assertEquals(TransactionState.AUTHORISED, payments.getTransaction(txn.getId()).getState());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void missingEventIdRejected() {
        Result r = webhooks.ingest("razorpay", signed("{\"x\":1}"), "{\"x\":1}".getBytes(StandardCharsets.UTF_8));
        assertFalse(r.accepted());
        assertEquals("missing_event_id", r.reason());
    }

    @Test
    void unmatchedEventRecorded() {
        Result r = postEvent("unmatched-java-1", "succeeded", "txn_missing");
        assertTrue(r.accepted());
        assertFalse(r.reconciled());
        assertEquals("unmatched_event", r.reason());
    }

    @Test
    void lateSuccessWebhookReconcilesStuckTransaction() {
        var txn = payments.create("wh-java-1", "ORD-WH", 50000, "INR", "upi");
        String txnId = txn.getId();
        states(txnId); // CREATED -> ROUTING -> AUTH_INITIATED (outcome unknown)
        Result r = postEvent("recon-java-1", "succeeded", txnId);
        assertTrue(r.reconciled());
        assertEquals(TransactionState.AUTHORISED, payments.getTransaction(txnId).getState());
    }

    private void states(String txnId) {
        com.payflow.service.StateService s = statesService;
        s.applyTransition(txnId, TransactionState.ROUTING, "test", "");
        s.applyTransition(txnId, TransactionState.AUTH_INITIATED, "test", "");
    }

    @Autowired com.payflow.service.StateService statesService;

    @Test
    void reconciliationRunExecutes() {
        String runId = reconciliation.runOnce();
        assertTrue(runId.startsWith("recon_"));
    }

    @Test
    void payuUsesHmacSha512() {
        String body = "{\"event_id\":\"payu-1\"}";
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec("whsec_test_secret".getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            String sig = HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            Result r = webhooks.ingest("payu", sig, body.getBytes(StandardCharsets.UTF_8));
            assertTrue(r.accepted());
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }
}
