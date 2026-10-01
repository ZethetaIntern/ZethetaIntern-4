package com.payflow.web;

import com.payflow.core.RoutingEngine;
import com.payflow.core.RoutingEngine.RankedGateway;
import com.payflow.entity.RoutingConfig;
import com.payflow.repository.GatewayRouteRepository;
import com.payflow.repository.RoutingConfigRepository;
import com.payflow.repository.ReconciliationLogRepository;
import com.payflow.repository.TransactionRepository;
import com.payflow.service.ReconciliationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Admin + routing endpoints. */
@RestController
public class AdminController {

    public record WeightsRequest(Double success, Double latency, Double cost, Double health, Double methodFit) {}

    private final RoutingEngine routing;
    private final RoutingConfigRepository configRepo;
    private final GatewayRouteRepository routes;
    private final ReconciliationService reconciliation;
    private final ReconciliationLogRepository reconLogs;
    private final TransactionRepository transactions;

    public AdminController(RoutingEngine routing, RoutingConfigRepository configRepo,
                           GatewayRouteRepository routes, ReconciliationService reconciliation,
                           ReconciliationLogRepository reconLogs, TransactionRepository transactions) {
        this.routing = routing;
        this.configRepo = configRepo;
        this.routes = routes;
        this.reconciliation = reconciliation;
        this.reconLogs = reconLogs;
        this.transactions = transactions;
    }

    @GetMapping("/routing/preview")
    public List<RankedGateway> preview(@RequestParam(defaultValue = "card") String paymentMethod,
                                       @RequestParam(defaultValue = "100000") long amount) {
        return routing.rank(paymentMethod, amount);
    }

    @GetMapping("/routing/weights")
    public Map<String, Double> weights() {
        return routing.weights();
    }

    /** Routing weights are stored in the DB and changeable without redeploy (A3.3). */
    @PutMapping("/routing/weights")
    public Map<String, Double> updateWeights(@RequestBody WeightsRequest req) {
        RoutingConfig cfg = configRepo.findById(1L).orElseGet(() -> configRepo.save(new RoutingConfig()));
        if (req.success() != null) cfg.setWeightSuccess(req.success());
        if (req.latency() != null) cfg.setWeightLatency(req.latency());
        if (req.cost() != null) cfg.setWeightCost(req.cost());
        if (req.health() != null) cfg.setWeightHealth(req.health());
        if (req.methodFit() != null) cfg.setWeightMethodFit(req.methodFit());
        configRepo.save(cfg);
        return routing.weights();
    }

    @GetMapping("/admin/gateways")
    public List<com.payflow.entity.GatewayRoute> gateways() {
        return routes.findAll();
    }

    @PostMapping("/admin/gateways/{gateway}/health")
    public Map<String, Object> setHealth(@PathVariable String gateway, @RequestParam boolean healthy) {
        routing.setHealth(gateway, healthy);
        return Map.of("gateway", gateway, "healthy", healthy);
    }

    @PostMapping("/admin/reconciliation/run")
    public Map<String, String> runReconciliation() {
        return Map.of("runId", reconciliation.runOnce());
    }

    @GetMapping("/admin/reconciliation")
    public List<com.payflow.entity.ReconciliationLog> reconciliationLogs() {
        return reconLogs.findAll();
    }

    @GetMapping("/admin/stats")
    public Map<String, Object> stats() {
        return Map.of("transactions", transactions.count());
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
