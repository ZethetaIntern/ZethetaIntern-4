package com.payflow.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.payflow.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Every A7.1 endpoint: happy path and error cases, all errors in the A7.2 format. */
class ApiEndpointsIntegrationTest extends IntegrationTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> error(Map<String, Object> body) {
        return (Map<String, Object>) body.get("error");
    }

    private void assertA72(MvcResult r, int status, String code) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        Map<String, Object> e = error(body(r));
        assertThat(e).containsEntry("code", code).containsKeys("message", "details", "request_id", "timestamp");
        assertThat((String) e.get("request_id")).startsWith("req_");
    }

    // #1 POST /payments
    @Test
    void createPaymentReturns201WithPaymentResource() throws Exception {
        MvcResult r = createPayment(UUID.randomUUID().toString(), paymentRequest(120_000, "CARD"));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> p = body(r);
        assertThat(p).containsEntry("state", "CAPTURED").containsEntry("amount_paise", 120_000)
                .containsEntry("amount", "1200.00").containsEntry("currency", "INR").containsKeys("id", "trace_id", "gateway");
        assertThat(r.getResponse().getHeader("X-Trace-Id")).isNotBlank();
    }

    @Test
    void createPaymentValidatesTheBody() throws Exception {
        Map<String, Object> bad = paymentRequest(-5, "CARD");
        assertA72(createPayment(UUID.randomUUID().toString(), bad), 400, "VALIDATION_FAILED");
        Map<String, Object> noMethod = paymentRequest(100, "CARD");
        noMethod.remove("payment_method");
        assertA72(createPayment(UUID.randomUUID().toString(), noMethod), 400, "VALIDATION_FAILED");
        Map<String, Object> weirdOrder = paymentRequest(100, "CARD");
        weirdOrder.put("merchant_order_id", "<script>");
        assertA72(createPayment(UUID.randomUUID().toString(), weirdOrder), 400, "VALIDATION_FAILED");
        assertA72(createPayment(UUID.randomUUID().toString(), paymentRequest(100, "BITCOIN")), 400, "INVALID_REQUEST");
    }

    @Test
    void createPaymentAcceptsCamelCaseAliasesAndAmountField() throws Exception {
        Map<String, Object> camel = Map.of("merchantOrderId", "ORD-camel", "amount", 5_000, "paymentMethod", "upi");
        MvcResult r = createPayment(UUID.randomUUID().toString(), camel);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(body(r)).containsEntry("payment_method", "UPI").containsEntry("amount_paise", 5_000);
    }

    @Test
    void unsupportedCurrencyHasNoEligibleGateway() throws Exception {
        Map<String, Object> req = paymentRequest(1_000, "UPI");
        req.put("currency", "USD");
        assertA72(createPayment(UUID.randomUUID().toString(), req), 422, "NO_ELIGIBLE_GATEWAY");
    }

    @Test
    void usdCardPaymentsRouteToStripe() throws Exception {
        Map<String, Object> req = paymentRequest(1_000, "CARD");
        req.put("currency", "USD");
        assertThat(body(createPayment(UUID.randomUUID().toString(), req))).containsEntry("gateway", "stripe");
    }

    @Test
    void apiKeyIsRequiredAndFailuresAreLogged() throws Exception {
        MvcResult r = perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertA72(r, 401, "UNAUTHORIZED");
        MvcResult wrong = perform(get("/api/v1/gateways").header("X-API-Key", "nope"));
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(perform(get("/api/v1/gateways").header("Authorization", "Bearer " + API_KEY))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM security_audit_log WHERE event_type = 'API_KEY_INVALID'",
                Integer.class)).isEqualTo(2);
    }

    // #2 GET /payments/{id}
    @Test
    void getPaymentByIdAndNotFound() throws Exception {
        Map<String, Object> p = createOk(10_000, "UPI");
        assertThat(getPayment(p.get("id"))).containsEntry("id", p.get("id"));
        assertA72(getJson("/api/v1/payments/" + UUID.randomUUID()), 404, "NOT_FOUND");
        assertA72(getJson("/api/v1/payments/not-a-uuid"), 400, "INVALID_PARAMETER");
        // payments are scoped to their merchant
        assertThat(perform(authed(get("/api/v1/payments/" + p.get("id"))).header("X-Merchant-Id", "other"))
                .getResponse().getStatus()).isEqualTo(404);
    }

    // #3 GET /payments?merchant_order_id=
    @Test
    void getPaymentsByMerchantOrderId() throws Exception {
        Map<String, Object> req = paymentRequest(10_000, "CARD");
        createPayment(UUID.randomUUID().toString(), req);
        createPayment(UUID.randomUUID().toString(), req);
        List<Map<String, Object>> found = list(getJson("/api/v1/payments?merchant_order_id=" + req.get("merchant_order_id")));
        assertThat(found).hasSize(2);
        assertA72(getJson("/api/v1/payments"), 400, "MISSING_PARAMETER");
    }

    // #4 capture
    @Test
    void captureErrors() throws Exception {
        Map<String, Object> req = paymentRequest(10_000, "CARD");
        req.put("capture_mode", "MANUAL");
        Map<String, Object> p = body(createPayment(UUID.randomUUID().toString(), req));
        assertA72(postJson("/api/v1/payments/" + p.get("id") + "/capture", Map.of("amount_paise", 20_000)), 422,
                "INVALID_CAPTURE_AMOUNT");
        assertThat(body(postJson("/api/v1/payments/" + p.get("id") + "/capture?amount_paise=4000", null)))
                .containsEntry("captured_paise", 4_000);
        Map<String, Object> upi = createOk(10_000, "UPI");
        assertA72(postJson("/api/v1/payments/" + upi.get("id") + "/capture", null), 422, "CAPTURE_NOT_SUPPORTED");
    }

    @Test
    void captureOnAlreadyCapturedPaymentIsAnInvalidTransition() throws Exception {
        Map<String, Object> p = createOk(10_000, "CARD");
        assertA72(postJson("/api/v1/payments/" + p.get("id") + "/capture", null), 409, "INVALID_STATE_TRANSITION");
    }

    // #5 void
    @Test
    void voidAuthorisedPayment() throws Exception {
        Map<String, Object> req = paymentRequest(10_000, "CARD");
        req.put("capture_mode", "MANUAL");
        Map<String, Object> p = body(createPayment(UUID.randomUUID().toString(), req));
        Map<String, Object> voided = body(postJson("/api/v1/payments/" + p.get("id") + "/void", null));
        assertThat(voided).containsEntry("state", "VOIDED").containsEntry("released_paise", 10_000);
        assertThat(states(p.get("id"))).endsWith("AUTHORISED", "VOID_INITIATED", "VOIDED");
        assertA72(postJson("/api/v1/payments/" + p.get("id") + "/void", null), 409, "INVALID_STATE_TRANSITION");
    }

    @Test
    void voidFailureAtTheGatewayIsReported() throws Exception {
        Map<String, Object> req = paymentRequest(10_000, "CARD");
        req.put("capture_mode", "MANUAL");
        Map<String, Object> p = body(createPayment(UUID.randomUUID().toString(), req));
        assertA72(postJson("/api/v1/payments/" + p.get("id") + "/void", null, "X-Mock-Response", "void=decline"),
                502, "VOID_FAILED");
    }

    // #6 refund
    @Test
    void partialAndFullRefunds() throws Exception {
        Map<String, Object> p = createOk(50_000, "CARD");
        Map<String, Object> partial = body(postJson("/api/v1/payments/" + p.get("id") + "/refund",
                Map.of("amount_paise", 20_000)));
        assertThat(partial).containsEntry("state", "PARTIALLY_REFUNDED").containsEntry("refunded_paise", 20_000);
        Map<String, Object> rest = body(postJson("/api/v1/payments/" + p.get("id") + "/refund", null));
        assertThat(rest).containsEntry("state", "REFUNDED").containsEntry("refunded_paise", 50_000);
        assertA72(postJson("/api/v1/payments/" + p.get("id") + "/refund", null), 409, "INVALID_STATE_TRANSITION");
    }

    @Test
    void refundValidation() throws Exception {
        Map<String, Object> p = createOk(50_000, "CARD");
        assertA72(postJson("/api/v1/payments/" + p.get("id") + "/refund", Map.of("amount_paise", 60_000)), 422,
                "INVALID_REFUND_AMOUNT");
        Map<String, Object> upi = createOk(50_000, "UPI");
        assertA72(postJson("/api/v1/payments/" + upi.get("id") + "/refund", Map.of("amount_paise", 1_000)), 422,
                "PARTIAL_REFUND_NOT_SUPPORTED");
        assertThat(body(postJson("/api/v1/payments/" + upi.get("id") + "/refund", null))).containsEntry("state", "REFUNDED");
        jdbc.update("UPDATE transactions SET created_at = NOW() - INTERVAL '400 days', "
                + "authorised_at = NOW() - INTERVAL '400 days' WHERE id = ?::uuid",
                p.get("id").toString());
        assertA72(postJson("/api/v1/payments/" + p.get("id") + "/refund", null), 422, "REFUND_WINDOW_EXPIRED");
    }

    @Test
    void refundFailureAtTheGatewayMovesToRefundFailedAndCanBeRetried() throws Exception {
        Map<String, Object> p = createOk(50_000, "CARD");
        assertA72(postJson("/api/v1/payments/" + p.get("id") + "/refund", null, "X-Mock-Response", "refund=server-error"),
                502, "REFUND_FAILED");
        assertThat(state(p.get("id"))).isEqualTo("REFUND_FAILED");
        assertThat(body(postJson("/api/v1/payments/" + p.get("id") + "/refund", null))).containsEntry("state", "REFUNDED");
        List<Map<String, Object>> refunds = list(getJson("/api/v1/payments/" + p.get("id") + "/refunds"));
        assertThat(refunds).extracting(r -> r.get("state")).containsExactly("FAILED", "PROCESSED");
    }

    // #7 refunds, #8 timeline, routing, attempts
    @Test
    void timelineShowsTheFullLifecycle() throws Exception {
        Map<String, Object> p = createOk(10_000, "CARD");
        List<Map<String, Object>> tl = timeline(p.get("id"));
        assertThat(tl.get(0)).containsEntry("event", "PAYMENT_CREATED").containsEntry("to_state", "CREATED");
        assertThat(states(p.get("id"))).containsExactly("CREATED", "ROUTE_SELECTED", "AUTH_INITIATED", "AUTHORISED",
                "CAPTURE_INITIATED", "CAPTURED");
        assertThat(tl).allSatisfy(e -> assertThat(e).containsKeys("id", "transaction_id", "event", "created_at",
                "created_by", "metadata"));
        assertThat(list(getJson("/api/v1/payments/" + p.get("id") + "/refunds"))).isEmpty();
        assertThat(list(getJson("/api/v1/payments/" + p.get("id") + "/attempts"))).hasSize(2); // auth + capture
    }

    // #9-#12 webhooks are covered in WebhookPipelineIntegrationTest; signature failures here
    @Test
    void everyWebhookReceiverRejectsUnsignedRequests() throws Exception {
        for (String g : List.of("razorpay", "stripe", "payu", "upi")) {
            MvcResult r = perform(post("/api/v1/webhooks/" + g).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"event_id\":\"e\"}"));
            assertA72(r, 401, "INVALID_SIGNATURE");
        }
    }

    // #13-#16 gateways
    @Test
    void gatewayListHealthMetricsAndConfig() throws Exception {
        List<Map<String, Object>> gateways = list(getJson("/api/v1/gateways"));
        assertThat(gateways).extracting(g -> g.get("gateway")).containsExactly("payu", "razorpay", "stripe", "upi");
        Map<String, Object> health = body(getJson("/api/v1/gateways/razorpay/health"));
        assertThat(health.get("circuits").toString()).contains("CARD").contains("CLOSED");
        createOk(10_000, "UPI");
        Map<String, Object> metricsBody = body(getJson("/api/v1/gateways/upi/metrics"));
        assertThat(metricsBody).containsKeys("live", "router_estimate", "historical_band", "rate_limit", "cost");
        assertThat(metricsBody.get("live").toString()).contains("attempts=1");
        assertThat(body(getJson("/api/v1/gateways/upi/config"))).containsEntry("gateway_name", "upi");
        assertA72(getJson("/api/v1/gateways/paypal/health"), 404, "NOT_FOUND");
    }

    @Test
    void updateGatewayConfig() throws Exception {
        Map<String, Object> updated = body(putJson("/api/v1/gateways/razorpay/config", Map.of("fee_bps", 175,
                "rate_limit_per_sec", 250, "rate_limit_strategy", "SLIDING_WINDOW", "supported_methods", "CARD,WALLET")));
        assertThat(updated.get("config").toString()).contains("fee_bps=175").contains("rate_limit_per_sec=250");
        assertThat(body(getJson("/api/v1/admin/rate-limits")).get("razorpay").toString()).contains("/250 req/sec");
        assertThat(putJson("/api/v1/gateways/razorpay/config", Map.of("fee_bps", -1)).getResponse().getStatus()).isEqualTo(400);
        assertThat(putJson("/api/v1/gateways/razorpay/config", Map.of("supported_methods", "CHEQUE")).getResponse()
                .getStatus()).isEqualTo(400);
    }

    @Test
    void disablingAGatewayTakesItOutOfRouting() throws Exception {
        putJson("/api/v1/gateways/upi/config", Map.of("enabled", false));
        assertA72(createPayment(UUID.randomUUID().toString(), paymentRequest(1_000, "UPI")), 422, "NO_ELIGIBLE_GATEWAY");
    }

    // #17-#18 routing
    @Test
    void routingConfigAndPreview() throws Exception {
        Map<String, Object> cfg = body(getJson("/api/v1/routing/config"));
        assertThat(cfg.get("weights").toString()).contains("success_rate=0.35").contains("method_fit=0.1");
        Map<String, Object> preview = body(getJson("/api/v1/routing/preview?payment_method=CARD&amount_paise=150000"));
        assertThat((List<?>) preview.get("ranked")).hasSize(3);
        assertThat(putJson("/api/v1/routing/config", Map.of("latency", "abc")).getResponse().getStatus()).isEqualTo(400);
        assertThat(putJson("/api/v1/routing/config", Map.of()).getResponse().getStatus()).isEqualTo(400);
    }

    // #19-#20 reconciliation
    @Test
    void reconciliationTriggerAndReport() throws Exception {
        createOk(10_000, "CARD");
        MvcResult r = postJson("/api/v1/reconciliation/trigger?stale_threshold_seconds=0", null);
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> run = body(r);
        assertThat(run).containsEntry("status", "COMPLETED").containsEntry("settled", 1).containsKey("duration_ms");
        Map<String, Object> report = body(getJson("/api/v1/reconciliation/reports/" + run.get("run_id")));
        assertThat((List<?>) report.get("entries")).hasSize(1);
        assertA72(getJson("/api/v1/reconciliation/reports/recon_missing"), 404, "NOT_FOUND");
    }

    // #21-#22 analytics
    @Test
    void analytics() throws Exception {
        createOk(10_000, "CARD");
        createOk(20_000, "UPI");
        createPayment(UUID.randomUUID().toString(), paymentRequest(5_000, "UPI"), "X-Mock-Response", "decline");
        Map<String, Object> sr = body(getJson("/api/v1/analytics/success-rate"));
        assertThat(sr).containsKeys("overall_success_rate", "gateways");
        assertThat(sr.get("gateways").toString()).contains("upi");
        Map<String, Object> vol = body(getJson("/api/v1/analytics/volume?window_hours=1"));
        assertThat(vol).containsEntry("total_count", 3).containsEntry("total_amount_paise", 35_000);
        assertThat(vol.get("by_state").toString()).contains("FAILED");
        assertThat(getJson("/api/v1/analytics/volume?window_hours=0").getResponse().getStatus()).isEqualTo(400);
    }

    // #23 health
    @Test
    void healthIsPublicAndReportsComponents() throws Exception {
        MvcResult r = perform(get("/api/v1/health"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> h = body(r);
        assertThat(h).containsEntry("status", "UP").containsKeys("database", "gateways", "webhook_dlq_depth");
        circuits.forceOpen("payu", com.payflow.domain.PaymentMethod.CARD);
        assertThat(body(perform(get("/api/v1/health")))).containsEntry("status", "DEGRADED");
    }

    // operations
    @Test
    void operationsEndpoints() throws Exception {
        assertThat(body(getJson("/api/v1/admin/circuits")).keySet()).contains("razorpay", "upi");
        assertThat(body(getJson("/api/v1/admin/db-pool"))).containsKeys("active", "max", "circuit");
        assertThat(list(getJson("/api/v1/admin/anomalies"))).isEmpty();
        assertThat(list(getJson("/api/v1/admin/security-events"))).isEmpty();
        assertThat(getJson("/api/v1/admin/alerts").getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> p = createOk(10_000, "CARD");
        assertThat(list(getJson("/api/v1/admin/notifications?transaction_id=" + p.get("id")))).isEmpty();
        assertThat(body(getJson("/api/v1/mock/gateways/" + p.get("gateway") + "/charges"))).containsEntry("charges_created", 1);
        assertThat(body(postJson("/api/v1/mock/reset", null))).containsEntry("reset", true);
        Map<String, Object> signed = body(postJson("/api/v1/mock/webhooks/stripe/sign", Map.of("a", 1)));
        assertThat(signed).containsKey("Stripe-Signature");
    }

    @Test
    void openApiSpecificationIsServed() throws Exception {
        MvcResult r = perform(get("/v3/api-docs"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        String spec = r.getResponse().getContentAsString();
        assertThat(spec).contains("\"openapi\":\"3.0").contains("/api/v1/payments/{id}/capture")
                .contains("/api/v1/webhooks/upi").contains("\"Error\"");
        // Keep docs/api-specification.yaml in sync with the running code (Day 11-12 deliverable)
        String yaml = perform(get("/v3/api-docs.yaml")).getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(yaml).startsWith("openapi: 3.0");
        java.nio.file.Files.writeString(java.nio.file.Path.of("docs", "api-specification.yaml"), yaml);
    }

    @Test
    void unknownEndpointIsA404InTheStandardFormat() throws Exception {
        assertA72(getJson("/api/v1/does-not-exist"), 404, "NOT_FOUND");
    }

    @Test
    void malformedJsonIsRejected() throws Exception {
        MvcResult r = perform(authed(post("/api/v1/payments")).header("Idempotency-Key", "k").content("{not json"));
        assertA72(r, 400, "MALFORMED_REQUEST");
    }
}
