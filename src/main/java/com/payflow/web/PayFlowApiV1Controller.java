package com.payflow.web;

import com.payflow.core.RoutingEngine;
import com.payflow.entity.GatewayRoute;
import com.payflow.entity.Refund;
import com.payflow.entity.RoutingConfig;
import com.payflow.entity.Transaction;
import com.payflow.repository.*;
import com.payflow.service.PaymentService;
import com.payflow.service.ReconciliationService;
import com.payflow.service.StateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API under /api/v1 as required by spec A7.1 (minimum 20 endpoints).
 */
@RestController
@RequestMapping("/api/v1")
public class PayFlowApiV1Controller {

    public record CreatePaymentRequest(@NotBlank String merchantOrderId, @Positive long amount,
                                       String currency, @NotBlank String paymentMethod) {}

    private final PaymentService payments;
    private final TransactionRepository transactions;
    private final GatewayAttemptRepository attempts;
    private final TransactionStateLogRepository logs;
    private final RefundRepository refunds;
    private final ReconciliationLogRepository reconLogs;
    private final GatewayRouteRepository routes;
    private final RoutingConfigRepository routingConfig;
    private final RoutingEngine routing;
    private final ReconciliationService reconciliation;
    private final StateService states;

    public PayFlowApiV1Controller(PaymentService payments, TransactionRepository transactions,
                                  GatewayAttemptRepository attempts, TransactionStateLogRepository logs,
                                  RefundRepository refunds, ReconciliationLogRepository reconLogs,
                                  GatewayRouteRepository routes, RoutingConfigRepository routingConfig,
                                  RoutingEngine routing, ReconciliationService reconciliation,
                                  StateService states) {
        this.payments = payments;
        this.transactions = transactions;
        this.attempts = attempts;
        this.logs = logs;
        this.refunds = refunds;
        this.reconLogs = reconLogs;
        this.routes = routes;
        this.routingConfig = routingConfig;
        this.routing = routing;
        this.reconciliation = reconciliation;
        this.states = states;
    }

    // 1. POST /api/v1/payments
    @PostMapping("/payments")
    public ResponseEntity<?> createPayment(@RequestHeader(value = "Idempotency-Key", required = false) String idem,
                                           @Valid @RequestBody CreatePaymentRequest req) {
        if (idem == null || idem.isBlank()) {
            return ResponseEntity.badRequest().body(GlobalExceptionHandler.error(
                    "MISSING_IDEMPOTENCY_KEY", "Idempotency-Key header is required"));
        }
        var txn = payments.create(idem, req.merchantOrderId(), req.amount(),
                req.currency() == null ? "INR" : req.currency(), req.paymentMethod());
        return ResponseEntity.status(201).body(Map.of("replayed", false, "payment",
                payments.process(txn.getId())));
    }

    // 2. GET /api/v1/payments/{id}
    @GetMapping("/payments/{id}")
    public PaymentService.TransactionView getPayment(@PathVariable String id) {
        return payments.view(id);
    }

    // 3. GET /api/v1/payments?merchant_order_id={id}
    @GetMapping("/payments")
    public List<Transaction> getByOrder(@RequestParam("merchant_order_id") String merchantOrderId) {
        return transactions.findByMerchantOrderId(merchantOrderId);
    }

    // 4. POST /api/v1/payments/{id}/capture
    @PostMapping("/payments/{id}/capture")
    public Transaction capture(@PathVariable String id,
                               @RequestParam(defaultValue = "0") long amount) {
        Transaction t = payments.getTransaction(id);
        return payments.captureFlow(id, t.getGateway(), t.getGatewayReference(), t.getAttemptsMade() + 1);
    }

    // 5. POST /api/v1/payments/{id}/void — release an uncaptured authorisation
    @PostMapping("/payments/{id}/void")
    public Transaction voidAuthorisation(@PathVariable String id) {
        return payments.voidAuthorisation(id);
    }

    // 6. POST /api/v1/payments/{id}/refund
    @PostMapping("/payments/{id}/refund")
    public Transaction refund(@PathVariable String id, @RequestParam(defaultValue = "0") long amount) {
        Transaction t = payments.getTransaction(id);
        long amt = amount > 0 ? amount
                : t.getCapturedPaise() > 0 ? t.getCapturedPaise() : t.getAmountPaise();
        return payments.refund(id, amt, "api");
    }

    // 7. GET /api/v1/payments/{id}/refunds
    @GetMapping("/payments/{id}/refunds")
    public List<Refund> listRefunds(@PathVariable String id) {
        return refunds.findByTransactionId(id);
    }

    // 8. GET /api/v1/payments/{id}/timeline
    @GetMapping("/payments/{id}/timeline")
    public List<?> timeline(@PathVariable String id) {
        return logs.findByTransactionIdOrderByCreatedAtAsc(id);
    }

    // 13. GET /api/v1/gateways
    @GetMapping("/gateways")
    public List<GatewayRoute> gateways() {
        return routes.findAll();
    }

    // 14. GET /api/v1/gateways/{name}/health
    @GetMapping("/gateways/{name}/health")
    public Map<String, Object> gatewayHealth(@PathVariable String name) {
        GatewayRoute g = routes.findById(name)
                .orElseThrow(() -> new IllegalArgumentException("unknown gateway: " + name));
        return Map.of("gateway", g.getGateway(), "healthy", g.isHealthy(),
                "consecutiveFailures", g.getConsecutiveFailures(),
                "circuitOpenUntil", g.getCircuitOpenUntil().toString());
    }

    // 15. GET /api/v1/gateways/{name}/metrics
    @GetMapping("/gateways/{name}/metrics")
    public Map<String, Object> gatewayMetrics(@PathVariable String name) {
        GatewayRoute g = routes.findById(name)
                .orElseThrow(() -> new IllegalArgumentException("unknown gateway: " + name));
        int total = g.getSuccessCount() + g.getFailureCount();
        double successRate = total == 0 ? 0 : (double) g.getSuccessCount() / total;
        double avgLatency = total == 0 ? 0 : (double) g.getTotalLatencyMs() / total;
        return Map.of("gateway", g.getGateway(), "successCount", g.getSuccessCount(),
                "failureCount", g.getFailureCount(), "successRate", Math.round(successRate * 10000) / 10000.0,
                "avgLatencyMs", avgLatency, "costBps", g.getCostBps());
    }

    // 16. PUT /api/v1/gateways/{name}/config
    @PutMapping("/gateways/{name}/config")
    public GatewayRoute updateGatewayConfig(@PathVariable String name,
                                           @RequestParam(required = false) Integer costBps,
                                           @RequestParam(required = false) Integer baseLatencyMs,
                                           @RequestParam(required = false) Boolean supportsUpi) {
        GatewayRoute g = routes.findById(name)
                .orElseThrow(() -> new IllegalArgumentException("unknown gateway: " + name));
        if (costBps != null) g.setCostBps(costBps);
        if (baseLatencyMs != null) g.setBaseLatencyMs(baseLatencyMs);
        if (supportsUpi != null) g.setSupportsUpi(supportsUpi);
        return routes.save(g);
    }

    // 17. GET /api/v1/routing/config
    @GetMapping("/routing/config")
    public Map<String, Double> getRoutingConfig() {
        return routing.weights();
    }

    // 18. PUT /api/v1/routing/config
    @PutMapping("/routing/config")
    public Map<String, Double> updateRoutingConfig(@RequestBody RoutingWeights req) {
        RoutingConfig cfg = routingConfig.findById(1L).orElseGet(() -> routingConfig.save(new RoutingConfig()));
        if (req.success() != null) cfg.setWeightSuccess(req.success());
        if (req.latency() != null) cfg.setWeightLatency(req.latency());
        if (req.cost() != null) cfg.setWeightCost(req.cost());
        if (req.health() != null) cfg.setWeightHealth(req.health());
        if (req.methodFit() != null) cfg.setWeightMethodFit(req.methodFit());
        routingConfig.save(cfg);
        return routing.weights();
    }

    public record RoutingWeights(Double success, Double latency, Double cost,
                                 Double health, Double methodFit) {}

    // 19. POST /api/v1/reconciliation/trigger
    @PostMapping("/reconciliation/trigger")
    public Map<String, String> triggerReconciliation() {
        return Map.of("run_id", reconciliation.runOnce());
    }

    // 20. GET /api/v1/reconciliation/reports/{run_id}
    @GetMapping("/reconciliation/reports/{runId}")
    public Map<String, Object> reconciliationReport(@PathVariable String runId) {
        var entries = reconLogs.findAll().stream().filter(l -> runId.equals(l.getRunId())).toList();
        return Map.of("run_id", runId, "discrepancy_count", entries.size(), "discrepancies", entries);
    }

    // 21. GET /api/v1/analytics/success-rate
    @GetMapping("/analytics/success-rate")
    public List<Map<String, Object>> successRateAnalytics() {
        return routes.findAll().stream().map(g -> {
            int total = g.getSuccessCount() + g.getFailureCount();
            double rate = total == 0 ? 0.0 : (double) g.getSuccessCount() / total;
            return Map.<String, Object>of("gateway", g.getGateway(),
                    "successRate", Math.round(rate * 10000) / 10000.0, "attempts", total);
        }).toList();
    }

    // 22. GET /api/v1/analytics/volume
    @GetMapping("/analytics/volume")
    public Map<String, Object> volumeAnalytics() {
        long total = transactions.count();
        Map<String, Long> byState = transactions.findAll().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        t -> t.getState().name(), java.util.stream.Collectors.counting()));
        return Map.of("totalTransactions", total, "byState", byState);
    }

    // 23. GET /api/v1/health
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "transactions", transactions.count(),
                "gateways", routes.count());
    }
}
