package com.payflow.core;

import com.payflow.entity.GatewayRoute;
import com.payflow.entity.RoutingConfig;
import com.payflow.domain.AttemptOutcome;
import com.payflow.entity.GatewayAttempt;
import com.payflow.entity.GatewayHourlyMetric;
import com.payflow.domain.TransactionState;
import com.payflow.entity.Transaction;
import com.payflow.repository.GatewayAttemptRepository;
import com.payflow.repository.GatewayHourlyMetricRepository;
import com.payflow.repository.GatewayRouteRepository;
import com.payflow.repository.RoutingConfigRepository;
import com.payflow.repository.TransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;

/**
 * Intelligent routing (spec A3): composite score with weights stored in the DB
 * (tunable without redeploy). Excludes circuit-open gateways and gateways that
 * cannot serve the payment method. Laplace-style prior until MIN_SAMPLES.
 */
@Component
public class RoutingEngine {

    public record RankedGateway(GatewayRoute route, double score) {}

    /** A3.3 circuit breaker states. */
    public enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    public static final int MIN_SAMPLES = 10;
    public static final double PRIOR_SUCCESS_RATE = 0.85;
    static final int FAILURE_THRESHOLD = 5;
    static final long CIRCUIT_OPEN_SECONDS = 30;
    static final long PROBE_LEASE_SECONDS = 5;
    /** Probes allowed through while HALF_OPEN before the state is decided. */
    static final int HALF_OPEN_PROBES = 1;

    private final GatewayRouteRepository routes;
    private final RoutingConfigRepository configRepo;
    private final GatewayAttemptRepository attempts;
    private final GatewayHourlyMetricRepository hourlyMetrics;
    private final TransactionRepository transactions;

    public RoutingEngine(GatewayRouteRepository routes, RoutingConfigRepository configRepo,
                         GatewayAttemptRepository attempts, GatewayHourlyMetricRepository hourlyMetrics,
                         TransactionRepository transactions) {
        this.routes = routes;
        this.configRepo = configRepo;
        this.attempts = attempts;
        this.hourlyMetrics = hourlyMetrics;
        this.transactions = transactions;
    }

    public RoutingConfig config() {
        return configRepo.findById(1L).orElseGet(() -> configRepo.save(new RoutingConfig()));
    }

    public List<RankedGateway> rank(String paymentMethod, long amountPaise) {
        RoutingConfig cfg = config();
        Instant since = Instant.now().minus(15, ChronoUnit.MINUTES);
        return routes.findAll().stream()
                .filter(r -> isEligible(r, paymentMethod))
                .map(r -> new RankedGateway(r, score(r, paymentMethod, amountPaise, cfg,
                        recentMetrics(r, since))))
                .sorted(Comparator.comparingDouble(RankedGateway::score).reversed())
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toCollection(ArrayList::new),
                        this::preferHealthyOverHalfOpen));
    }

    private boolean isEligible(GatewayRoute r, String paymentMethod) {
        Instant now = Instant.now();
        boolean circuitAllows = r.isHealthy()
                || (!r.isProbeInFlight(now) && !now.isBefore(r.getCircuitOpenUntil()));
        // Gateways that do not support UPI rails cannot take UPI payments.
        return circuitAllows && !("upi".equals(paymentMethod) && !r.isSupportsUpi());
    }

    private record Metrics(int successes, int failures, int p95LatencyMs) {
        int total() { return successes + failures; }
    }

    private Metrics recentMetrics(GatewayRoute route, Instant since) {
        List<GatewayAttempt> recent = attempts.findByGatewayAndCreatedAtAfterOrderByCreatedAtDesc(
                route.getGateway(), since, PageRequest.of(0, 1000)).getContent();
        if (recent.isEmpty()) {
            Instant historicalSince = Instant.now().minus(24, ChronoUnit.HOURS);
            List<GatewayHourlyMetric> history = hourlyMetrics
                    .findByGatewayOrderByRecordedAtDesc(route.getGateway()).stream()
                    .filter(m -> !m.getRecordedAt().isBefore(historicalSince))
                    .toList();
            if (!history.isEmpty()) {
                long sampleCount = history.stream().mapToLong(GatewayHourlyMetric::getTransactionCount).sum();
                long successes = Math.round(history.stream()
                        .mapToDouble(m -> m.getSuccessRate() * m.getTransactionCount()).sum());
                int p95 = (int) Math.round(history.stream()
                        .mapToDouble(m -> (double) m.getP95LatencyMs() * m.getTransactionCount()).sum()
                        / Math.max(1, sampleCount));
                return new Metrics(Math.toIntExact(successes),
                        Math.toIntExact(sampleCount - successes), p95);
            }
            return new Metrics(route.getSuccessCount(), route.getFailureCount(), route.getBaseLatencyMs());
        }
        int successes = transactions.findByGatewayAndStateInAndUpdatedAtAfterOrderByUpdatedAtDesc(
                route.getGateway(),
                List.of(TransactionState.CAPTURED, TransactionState.SETTLED),
                since, PageRequest.of(0, 1000)).getContent().size();
        successes = Math.min(successes, recent.size());
        List<Long> latencies = recent.stream().map(GatewayAttempt::getLatencyMs).sorted().toList();
        int p95Index = (int) Math.ceil(latencies.size() * 0.95) - 1;
        int p95 = Math.toIntExact(Math.min(Integer.MAX_VALUE, latencies.get(Math.max(0, p95Index))));
        return new Metrics(successes, recent.size() - successes, p95);
    }

    private List<RankedGateway> preferHealthyOverHalfOpen(List<RankedGateway> ranked) {
        if (ranked.size() < 2 || ranked.get(0).route().isHealthy()) return List.copyOf(ranked);
        RankedGateway recovering = ranked.get(0);
        RankedGateway alternative = ranked.get(1);
        if (!alternative.route().isHealthy()) return List.copyOf(ranked);
        double difference = (recovering.score() - alternative.score()) / Math.max(alternative.score(), 0.0001);
        if (difference <= 0.20) {
            ranked.set(0, alternative);
            ranked.set(1, recovering);
        }
        return List.copyOf(ranked);
    }

    private double score(GatewayRoute r, String paymentMethod, long amountPaise,
                         RoutingConfig cfg, Metrics metrics) {
        int total = metrics.total();
        double successRate = total >= MIN_SAMPLES
                ? (double) metrics.successes() / total
                : (PRIOR_SUCCESS_RATE * MIN_SAMPLES + metrics.successes()) / (MIN_SAMPLES + total);

        // Cost: percent-of-amount + fixed, normalised against a 5% ceiling.
        double costFraction = (r.getCostBps() / 10_000.0)
                + (amountPaise > 0 ? (double) r.getFixedCostPaise() / amountPaise : 0);
        double costNorm = Math.min(1.0, costFraction / 0.05);

        double latencyNorm = Math.min(1.0, metrics.p95LatencyMs() / 2000.0);

        // Method fit: card/netbanking universal; UPI requires supportsUpi.
        double methodFit = "upi".equals(paymentMethod) && !r.isSupportsUpi() ? 0.0 : 1.0;

        return cfg.getWeightSuccess() * successRate
                + cfg.getWeightLatency() * (1 - latencyNorm)
                + cfg.getWeightCost() * (1 - costNorm)
                + cfg.getWeightHealth() * (r.isHealthy() ? 1.0 : 0.0)
                + cfg.getWeightMethodFit() * methodFit;
    }

    /** Current circuit state for a gateway (A3.3). */
    public CircuitState circuitState(String gateway) {
        return routes.findById(gateway).map(r -> {
            Instant now = Instant.now();
            if (!r.isHealthy() && now.isBefore(r.getCircuitOpenUntil())) return CircuitState.OPEN;
            if (!r.isHealthy()) return CircuitState.HALF_OPEN; // cool-down elapsed, probe allowed
            return CircuitState.CLOSED;
        }).orElse(CircuitState.OPEN);
    }

    /**
     * A3.3: a HALF_OPEN gateway still receives a small, bounded share of traffic
     * as probes. It is otherwise excluded, so a recovering gateway can prove
     * itself without a traffic surge.
     */
    public boolean allowProbe(String gateway) {
        return circuitState(gateway) != CircuitState.OPEN;
    }

    /** Reserves the single recovery probe in the database so replicas cannot flood a recovering gateway. */
    public boolean tryAcquireProbe(String gateway) {
        CircuitState state = circuitState(gateway);
        if (state == CircuitState.CLOSED) return true;
        if (state == CircuitState.OPEN) return false;
        return routes.claimHalfOpenProbe(gateway, Instant.now().plusSeconds(PROBE_LEASE_SECONDS)) == 1;
    }

    /** Update authorisation health and latency after an attempt. */
    public void recordResult(String gateway, boolean success, long latencyMs) {
        Optional<GatewayRoute> opt = routes.findById(gateway);
        if (opt.isEmpty()) return;
        GatewayRoute r = opt.get();
        r.setProbeLeaseUntil(Instant.EPOCH);
        if (success) {
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

    /** Success-rate numerator is a payment that actually reached capture, not just authorization. */
    public void recordCaptureSuccess(String gateway) {
        routes.findById(gateway).ifPresent(r -> {
            r.setSuccessCount(r.getSuccessCount() + 1);
            routes.save(r);
        });
    }

    public void setHealth(String gateway, boolean healthy) {
        routes.findById(gateway).ifPresent(r -> {
            r.setHealthy(healthy);
            if (healthy) {
                r.setCircuitOpenUntil(Instant.EPOCH);
                r.setConsecutiveFailures(0);
                r.setProbeLeaseUntil(Instant.EPOCH);
            } else {
                // A3.3: mark OPEN for the cool-down window; it becomes HALF_OPEN
                // (probe-eligible) once the window elapses.
                r.setCircuitOpenUntil(Instant.now().plusSeconds(CIRCUIT_OPEN_SECONDS));
                r.setProbeLeaseUntil(Instant.EPOCH);
            }
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
