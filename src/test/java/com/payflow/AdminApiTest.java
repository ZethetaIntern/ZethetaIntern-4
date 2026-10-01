package com.payflow;

import com.payflow.repository.RoutingConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Coverage for the admin/routing/webhook HTTP layer and the generated OpenAPI document. */
@SpringBootTest
@AutoConfigureMockMvc
class AdminApiTest {

    private static final String KEY = "pk_test_payflow";

    @Autowired MockMvc mvc;
    @Autowired RoutingConfigRepository configs;

    @Test
    void healthEndpointIsOpen() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void routingPreviewListsRankedGateways() throws Exception {
        mvc.perform(get("/routing/preview").param("paymentMethod", "card").param("amount", "100000")
                        .header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(4)))
                .andExpect(jsonPath("$[0].route.gateway").exists())
                .andExpect(jsonPath("$[0].score").exists());
    }

    @Test
    void weightsAreUpdatableAtRuntime() throws Exception {
        mvc.perform(get("/routing/weights").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").exists());
        mvc.perform(put("/routing/weights").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"success\":0.5,\"cost\":0.2,\"latency\":0.15,\"health\":0.1,\"methodFit\":0.05}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(0.5));
        // restore defaults for other tests
        mvc.perform(put("/routing/weights").header("X-API-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"success\":0.35,\"latency\":0.20,\"cost\":0.20,\"health\":0.15,\"methodFit\":0.10}"))
                .andExpect(status().isOk());
    }

    @Test
    void gatewayHealthCanBeToggled() throws Exception {
        mvc.perform(post("/admin/gateways/payu/health").param("healthy", "false").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.healthy").value(false));
        mvc.perform(get("/routing/preview").param("paymentMethod", "upi").header("X-API-Key", KEY))
                .andExpect(jsonPath("$[*].route.gateway",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("payu"))));
        mvc.perform(post("/admin/gateways/payu/health").param("healthy", "true").header("X-API-Key", KEY))
                .andExpect(status().isOk());
    }

    @Test
    void gatewayListAndStatsEndpoints() throws Exception {
        mvc.perform(get("/admin/gateways").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(4)));
        mvc.perform(get("/admin/stats").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions").isNumber());
    }

    @Test
    void reconciliationCanBeTriggeredAndListed() throws Exception {
        mvc.perform(post("/admin/reconciliation/run").header("X-API-Key", KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").exists());
        mvc.perform(get("/admin/reconciliation").header("X-API-Key", KEY))
                .andExpect(status().isOk());
    }

    @Test
    void webhookEndpointValidatesSignature() throws Exception {
        String body = "{\"event_id\":\"admin-test-evt\",\"status\":\"succeeded\"}";
        mvc.perform(post("/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Payflow-Signature", "deadbeef"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void webhookEndpointAcceptsSignedPayload() throws Exception {
        String body = "{\"event_id\":\"admin-test-evt-2\",\"status\":\"succeeded\"}";
        mvc.perform(post("/webhooks/stripe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("X-Payflow-Signature", hmac(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true));
    }

    @Test
    void unknownTransactionReturnsNotFound() throws Exception {
        mvc.perform(get("/payments/does-not-exist").header("X-API-Key", KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void validationFailureReturnsBadRequest() throws Exception {
        mvc.perform(post("/payments").header("X-API-Key", KEY).header("Idempotency-Key", "v1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantOrderId\":\"O\",\"amount\":-5,\"paymentMethod\":\"card\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void openApiDocumentIsServed() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.0.1"))
                .andExpect(jsonPath("$.paths['/payments'].post").exists())
                .andExpect(jsonPath("$.paths['/webhooks/{gateway}'].post").exists());
    }

    private String hmac(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("whsec_test_secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
