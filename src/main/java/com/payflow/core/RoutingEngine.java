package com.payflow.core;

import com.payflow.entity.GatewayRoute;
import com.payflow.entity.RoutingConfig;
import com.payflow.repository.GatewayRouteRepository;
import com.payflow.repository.RoutingConfigRepository;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Intelligent routing (spec A3): composite score with weights stored in the DB
 * (tunable without redeploy). Excludes circuit-open gateways and gateways that
 * cannot serve the payment method. Laplace-style prior until MIN_SAMPLES.
 */
@Component
public class RoutingEngine {

    public record RankedGateway(GatewayRoute route, double score) {}

    public static final int MIN_SAMPLES = 10;
    public static final double PRIOR_SUCCESS_RATE = 0.85;
    static final int FAILURE_THRESHOLD = 5;
    static final long CIRCUIT_OPEN_SECONDS = 30;

    private final GatewayRouteRepository routes;
    private final RoutingConfigRepository configRepo;

    public RoutingEngine(GatewayRouteRepository routes, RoutingConfigRepository configRepo) {
        this.routes = routes;
        this.configRepo = configRepo;
    }

    public RoutingConfig config() {
        return configRepo.findById(1L).orElseGet(() -> configRepo.save(new RoutingConfig()));
    }

    public List<RankedGateway> rank(String paymentMethod, long amountPaise) {
        RoutingConfig cfg = config();
        return routes.findAll().stream()
                .filter(r -> isEligible(r, paymentMethod))
                .map(r -> new RankedGateway(r, score(r, paymentMethod, amountPaise, cfg)))
                .sorted(Comparator.comparingDouble(RankedGateway::score).reversed())
                .toList();
    }

    private boolean isEligible(GatewayRoute r, String paymentMethod) {
        Instant now = Instant.now();
        return r.isHealthy() && now.isAfter(r.getCircuitOpenUntil())
                && !("upi".equals(paymentMethod) && !r.isSupportsUpi());
    }

    private double score(GatewayRoute r, String paymentMethod, long amountPaise, RoutingConfig cfg) {
        int total = r.getSuccessCount() + r.getFailureCount();
        double successRate = total >= MIN_SAMPLES
                ? (double) r.getSuccessCount() / total
                : (PRIOR_SUCCESS_RATE * MIN_SAMPLES + r.getSuccessCount()) / (MIN_SAMPLES + total);

        // Cost: percent-of-amount + fixed, normalised against a 5% ceiling.
        double costFraction = (r.getCostBps() / 10_000.0)
                + (amountPaise > 0 ? (double) r.getFixedCostPaise() / amountPaise : 0);
        double costNorm = Math.min(1.0, costFraction / 0.05);

        double latencyNorm = Math.min(1.0, r.getBaseLatencyMs() / 2000.0);

        // Method fit: card/netbanking universal; UPI requires supportsUpi.
        double methodFit = "upi".equals(paymentMethod) && !r.isSupportsUpi() ? 0.0 : 1.0;

        return cfg.getWeightSuccess() * successRate
                + cfg.getWeightLatency() * (1 - latencyNorm)
                + cfg.getWeightCost() * (1 - costNorm)
                + cfg.getWeightHealth() * (r.isHealthy() ? 1.0 : 0.0)
                + cfg.getWeightMethodFit() * methodFit;
    }

    /** Update rolling stats after an attempt; circuit-break after repeated failures. */
    public void recordResult(String gateway, boolean success, long latencyMs) {
        Optional<GatewayRoute> opt = routes.findById(gateway);
        if (opt.isEmpty()) return;
        GatewayRoute r = opt.get();
        if (success) {
            r.setSuccessCount(r.getSuccessCount() + 1);
            r.setConsecutiveFailures(0);
            r.setHealthy(true);
            r.setCircuitOpenUntil(Instant.EPOCH);
        } else {
            r.setFailureCount(r.getFailureCount() + 1);
            r.setConsecutiveFailures(r.getConsecutiveFailures() + 1);
            if (r.getConsecutiveFailures() >= FAILURE_THRESHOLD) {
                r.setHealthy(false);
                r.setCircuitOpenUntil(Instant.now().plusSeconds(CIRCUIT_OPEN_SECONDS));
            }
        }
        r.setTotalLatencyMs(r.getTotalLatencyMs() + latencyMs);
        routes.save(r);
    }

    public void setHealth(String gateway, boolean healthy) {
        routes.findById(gateway).ifPresent(r -> {
            r.setHealthy(healthy);
            r.setCircuitOpenUntil(Instant.EPOCH);
            if (healthy) r.setConsecutiveFailures(0);
            routes.save(r);
        });
    }

    public Map<String, Double> weights() {
        RoutingConfig c = config();
        return Map.of("success", c.getWeightSuccess(), "latency", c.getWeightLatency(),
                "cost", c.getWeightCost(), "health", c.getWeightHealth(),
                "methodFit", c.getWeightMethodFit());
    }
}
