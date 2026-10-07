package com.payflow.routing;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.payflow.domain.HealthStatus;
import com.payflow.domain.PaymentMethod;
import com.payflow.entity.GatewayConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Multi-criteria gateway router (spec A3.2):
 *
 * <pre>
 * Score = W_success * SuccessRate
 *       + W_latency * (1 - NormalizedLatency)
 *       + W_cost    * (1 - NormalizedCost)
 *       + W_health  * HealthScore          (1.0 healthy / 0.5 degraded / 0.0 down)
 *       + W_fit     * FitScore             (1.0 supported / 0.0 not supported)
 *
 * NormalizedLatency = (p95 - min_p95) / (max_p95 - min_p95)      over eligible gateways
 * NormalizedCost    = (fee - min_fee) / (max_fee - min_fee)      fee = % + fixed for this amount
 * </pre>
 *
 * <p>Gateways that cannot serve the payment (method or currency unsupported,
 * disabled, circuit OPEN) are excluded <em>before</em> min-max normalisation,
 * so an unusable gateway cannot distort the scale of the others.</p>
 *
 * <p>Degraded rule: if the best gateway is DEGRADED (circuit half-open or
 * success rate under threshold) the runner-up is preferred unless the best
 * leads by more than {@code routing.degraded_margin} (20%). When it does keep
 * the lead it is admitted with probability equal to its health score, so a
 * degraded gateway receives proportionally reduced traffic (FS-07).</p>
 */
@Service
public class GatewayRouter {

    private static final Logger log = LoggerFactory.getLogger(GatewayRouter.class);

    /** One scored candidate with its per-factor breakdown. */
    public record Candidate(String gateway, double score, HealthStatus health, Map<String, Object> breakdown) {}

    /** Why a gateway was left out of the ranking. */
    public record Exclusion(String gateway, String reason) {}

    /** Ranked candidates (best first) plus exclusions. */
    public record Decision(List<Candidate> ranked, List<Exclusion> excluded, String note) {
        public boolean isEmpty() {
            return ranked.isEmpty();
        }
    }

    private final GatewayConfigService gatewayConfigs;
    private final RoutingConfigService routingConfig;
    private final GatewayMetricsService metrics;
    private final CircuitBreakerService circuits;
    private final RandomGenerator random;

    @org.springframework.beans.factory.annotation.Autowired
    public GatewayRouter(GatewayConfigService gatewayConfigs, RoutingConfigService routingConfig,
                         GatewayMetricsService metrics, CircuitBreakerService circuits) {
        // ThreadLocalRandom: thread-safe and present in every runtime image; RandomGenerator.getDefault()
        // needs the jdk.random module, which slim JRE images (eclipse-temurin:21-jre) do not ship.
        this(gatewayConfigs, routingConfig, metrics, circuits,
                () -> java.util.concurrent.ThreadLocalRandom.current().nextLong());
    }

    GatewayRouter(GatewayConfigService gatewayConfigs, RoutingConfigService routingConfig,
                  GatewayMetricsService metrics, CircuitBreakerService circuits, RandomGenerator random) {
        this.gatewayConfigs = gatewayConfigs;
        this.routingConfig = routingConfig;
        this.metrics = metrics;
        this.circuits = circuits;
        this.random = random;
    }

    public Decision decide(PaymentMethod method, String currency, long amountPaise) {
        return decide(method, currency, amountPaise, Set.of());
    }

    public Decision decide(PaymentMethod method, String currency, long amountPaise, Set<String> skip) {
        long started = System.nanoTime();
        RoutingConfigService.Snapshot cfg = routingConfig.current();
        List<Exclusion> excluded = new ArrayList<>();
        List<Inputs> eligible = new ArrayList<>();

        for (GatewayConfig g : gatewayConfigs.all()) {
            String name = g.getGatewayName();
            if (skip.contains(name)) {
                excluded.add(new Exclusion(name, "ALREADY_ATTEMPTED"));
            } else if (!g.isEnabled()) {
                excluded.add(new Exclusion(name, "DISABLED"));
            } else if (!g.supports(method)) {
                excluded.add(new Exclusion(name, "METHOD_NOT_SUPPORTED"));
            } else if (!g.supportsCurrency(currency)) {
                excluded.add(new Exclusion(name, "CURRENCY_NOT_SUPPORTED"));
            } else {
                HealthStatus health = circuits.health(name, method);
                if (health == HealthStatus.DOWN) {
                    excluded.add(new Exclusion(name, "CIRCUIT_OPEN"));
                } else {
                    GatewayMetricsService.Estimate est = metrics.estimate(name, cfg.windowMinutes(), cfg.minSamples());
                    eligible.add(new Inputs(name, est, g.feeFor(amountPaise), health));
                }
            }
        }

        List<Candidate> ranked = score(eligible, cfg.weights());
        String note = null;
        if (ranked.size() >= 2 && ranked.get(0).health() == HealthStatus.DEGRADED) {
            Candidate top = ranked.get(0);
            Candidate runnerUp = ranked.get(1);
            double lead = (top.score() - runnerUp.score()) / Math.max(runnerUp.score(), 1e-9);
            if (lead <= cfg.degradedMargin()) {
                ranked.set(0, runnerUp);
                ranked.set(1, top);
                note = top.gateway() + " is DEGRADED and leads by only " + pct(lead) + "; preferring " + runnerUp.gateway();
            } else if (random.nextDouble() >= top.health().score()) {
                ranked.set(0, runnerUp);
                ranked.set(1, top);
                note = top.gateway() + " is DEGRADED; traffic throttled to its health score, routed to "
                        + runnerUp.gateway();
            } else {
                note = top.gateway() + " is DEGRADED but leads by " + pct(lead) + "; kept";
            }
        }
        if (!ranked.isEmpty()) {
            Candidate best = ranked.get(0);
            log.info("Selected {} with score {} {} {} {} {} {}",
                    best.gateway(), String.format(Locale.ROOT, "%.4f", best.score()),
                    kv("component", "gateway_router"), kv("action", "route_selected"), kv("gateway", best.gateway()),
                    kv("score", round(best.score())),
                    kv("duration_ms", (System.nanoTime() - started) / 1_000_000));
        } else {
            log.warn("no eligible gateway {} {} {}", kv("component", "gateway_router"),
                    kv("action", "route_failed"), kv("excluded", excluded));
        }
        return new Decision(ranked, excluded, note);
    }

    /** Raw per-gateway inputs to the formula (package-private for unit tests). */
    record Inputs(String gateway, GatewayMetricsService.Estimate estimate, long feePaise, HealthStatus health) {}

    /** Applies the A3.2 formula with min-max normalisation across the eligible set. */
    static List<Candidate> score(List<Inputs> eligible, RoutingConfigService.Weights w) {
        if (eligible.isEmpty()) return new ArrayList<>();
        int minLat = eligible.stream().mapToInt(i -> i.estimate().p95LatencyMs()).min().orElse(0);
        int maxLat = eligible.stream().mapToInt(i -> i.estimate().p95LatencyMs()).max().orElse(0);
        long minCost = eligible.stream().mapToLong(Inputs::feePaise).min().orElse(0);
        long maxCost = eligible.stream().mapToLong(Inputs::feePaise).max().orElse(0);

        List<Candidate> out = new ArrayList<>();
        for (Inputs i : eligible) {
            double successRate = i.estimate().successRate();
            double normLatency = normalise(i.estimate().p95LatencyMs(), minLat, maxLat);
            double normCost = normalise(i.feePaise(), minCost, maxCost);
            double healthScore = i.health().score();
            double fitScore = 1.0; // unsupported methods were excluded above
            double cSuccess = w.success() * successRate;
            double cLatency = w.latency() * (1 - normLatency);
            double cCost = w.cost() * (1 - normCost);
            double cHealth = w.health() * healthScore;
            double cFit = w.fit() * fitScore;
            double score = cSuccess + cLatency + cCost + cHealth + cFit;

            Map<String, Object> b = new LinkedHashMap<>();
            b.put("success_rate", round(successRate));
            b.put("success_rate_source", i.estimate().source());
            b.put("live_attempts", i.estimate().liveAttempts());
            b.put("p95_latency_ms", i.estimate().p95LatencyMs());
            b.put("normalized_latency", round(normLatency));
            b.put("cost_paise", i.feePaise());
            b.put("normalized_cost", round(normCost));
            b.put("health", i.health().name());
            b.put("health_score", healthScore);
            b.put("fit_score", fitScore);
            b.put("contribution", Map.of("success_rate", round(cSuccess), "latency", round(cLatency),
                    "cost", round(cCost), "health", round(cHealth), "fit", round(cFit)));
            out.add(new Candidate(i.gateway(), score, i.health(), b));
        }
        out.sort(Comparator.comparingDouble(Candidate::score).reversed().thenComparing(Candidate::gateway));
        return out;
    }

    static double normalise(double value, double min, double max) {
        return max == min ? 0.0 : (value - min) / (max - min);
    }

    private static double round(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v * 100);
    }
}
