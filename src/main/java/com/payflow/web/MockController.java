package com.payflow.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.error.ApiException;
import com.payflow.gateway.GatewayRegistry;
import com.payflow.gateway.GatewayStatus;
import com.payflow.gateway.MockGatewayLedger;
import com.payflow.ratelimit.GatewayRateLimiter;
import com.payflow.repository.CircuitBreakerStateRepository;
import com.payflow.repository.TransactionRepository;
import com.payflow.routing.GatewayMetricsService;
import com.payflow.webhook.WebhookIngestionService;
import com.payflow.webhook.WebhookSignatureVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Control surface of the simulated gateways (B4.3) for the test harness. Lets
 * a scenario change what a gateway's status API or settlement report says,
 * deliver a signed UPI callback as the NPCI switch would, and reset in-memory
 * state between scenarios. Disabled with {@code payflow.mock.enabled=false}.
 */
@RestController
@RequestMapping("/api/v1/mock")
@ConditionalOnProperty(prefix = "payflow.mock", name = "enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Mock gateways", description = "Simulation controls used by the test harness (not for production)")
public class MockController {

    private final MockGatewayLedger ledger;
    private final GatewayRegistry gateways;
    private final WebhookSignatureVerifier signer;
    private final WebhookIngestionService ingestion;
    private final TransactionRepository transactions;
    private final GatewayMetricsService metrics;
    private final GatewayRateLimiter rateLimiter;
    private final CircuitBreakerStateRepository circuitStates;
    private final ObjectMapper mapper = new ObjectMapper();

    public MockController(MockGatewayLedger ledger, GatewayRegistry gateways, WebhookSignatureVerifier signer,
                          WebhookIngestionService ingestion, TransactionRepository transactions,
                          GatewayMetricsService metrics, GatewayRateLimiter rateLimiter,
                          CircuitBreakerStateRepository circuitStates) {
        this.ledger = ledger;
        this.gateways = gateways;
        this.signer = signer;
        this.ingestion = ingestion;
        this.transactions = transactions;
        this.metrics = metrics;
        this.rateLimiter = rateLimiter;
        this.circuitStates = circuitStates;
    }

    @PostMapping("/gateways/{gateway}/status")
    @Operation(summary = "Make the gateway's status API report a status for a reference",
            description = "Body: {\"reference\": \"pay_...\", \"status\": \"CAPTURED|FAILED|EXPIRED|...\"}")
    public Map<String, Object> overrideStatus(@PathVariable String gateway, @RequestBody Map<String, String> body) {
        gateways.get(gateway);
        GatewayStatus status = GatewayStatus.parse(body.get("status"));
        ledger.overrideStatus(gateway, requireReference(gateway, body), status);
        return Map.of("gateway", gateway, "reference", requireReference(gateway, body), "status", status.name());
    }

    @PostMapping("/gateways/{gateway}/settlement")
    @Operation(summary = "Make the gateway's settlement report show a status for a reference",
            description = "Body: {\"reference\": \"pay_...\", \"status\": \"SETTLED|FAILED|REVERSED|PENDING\"}")
    public Map<String, Object> overrideSettlement(@PathVariable String gateway, @RequestBody Map<String, String> body) {
        gateways.get(gateway);
        GatewayStatus status = GatewayStatus.parse(body.get("status"));
        ledger.overrideSettlement(gateway, requireReference(gateway, body), status);
        return Map.of("gateway", gateway, "reference", requireReference(gateway, body), "status", status.name());
    }

    @GetMapping("/gateways/{gateway}/charges")
    @Operation(summary = "How many distinct charges the mock gateway has created (verifies no double charge)")
    public Map<String, Object> charges(@PathVariable String gateway) {
        gateways.get(gateway);
        return Map.of("gateway", gateway, "charges_created", ledger.chargesCreated(gateway));
    }

    @PostMapping("/upi/{transactionId}/callback")
    @Operation(summary = "Simulate the NPCI switch sending a signed UPI callback",
            description = "Body: {\"status\": \"SUCCESS|FAILURE|EXPIRED\"}. The callback goes through the real "
                    + "webhook pipeline, including RSA signature verification.")
    public ResponseEntity<Map<String, Object>> upiCallback(@PathVariable UUID transactionId,
                                                           @RequestBody Map<String, String> body) throws Exception {
        var t = transactions.findById(transactionId).orElseThrow(() -> ApiException.notFound("payment " + transactionId));
        if (t.getGatewayReference() == null || !"upi".equals(t.getGateway())) {
            throw ApiException.unprocessable("NOT_A_UPI_PAYMENT", "payment has no UPI transaction reference");
        }
        String status = body.getOrDefault("status", "SUCCESS").toUpperCase(Locale.ROOT);
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("txnId", t.getGatewayReference());
        callback.put("merchantTxnRef", t.getId().toString());
        callback.put("status", status);
        callback.put("amount", BigDecimal.valueOf(t.getAmountPaise(), 2).toPlainString());
        if ("SUCCESS".equals(status) || "FAILURE".equals(status) || "EXPIRED".equals(status)) {
            ledger.find("upi", t.getGatewayReference(), t.getId()).ifPresent(c -> ledger.setStatus(c,
                    GatewayStatus.parse("SUCCESS".equals(status) ? "captured" : status)));
        }
        byte[] raw = mapper.writeValueAsBytes(callback);
        Map<String, String> headers = new HashMap<>();
        signer.sign("upi", raw).forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v));
        WebhookIngestionService.IngestResult r = ingestion.ingest("upi", headers, raw, "npci-switch-simulator",
                "mock-npci", "/api/v1/webhooks/upi");
        return ResponseEntity.status(r.status()).body(r.body());
    }

    @PostMapping("/webhooks/{gateway}/sign")
    @Operation(summary = "Return the signature headers a gateway would send for this exact body")
    public Map<String, String> sign(@PathVariable String gateway, @RequestBody byte[] body) {
        gateways.get(gateway);
        return signer.sign(gateway, body == null ? "".getBytes(StandardCharsets.UTF_8) : body);
    }

    @PostMapping("/reset")
    @Operation(summary = "Reset simulator state between scenarios: gateway books, live metrics, "
            + "rate limiters and circuit breakers")
    public Map<String, Object> reset() {
        ledger.reset();
        metrics.reset();
        rateLimiter.reset();
        circuitStates.deleteAllInBatch();
        return Map.of("reset", true);
    }

    private static String requireReference(String gateway, Map<String, String> body) {
        String ref = body.get("reference");
        if (ref == null || ref.isBlank()) {
            throw ApiException.badRequest("VALIDATION_FAILED", "reference is required for gateway " + gateway);
        }
        return ref;
    }
}
