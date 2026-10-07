package com.payflow.web;

import com.payflow.domain.HealthStatus;
import com.payflow.domain.PaymentMethod;
import com.payflow.entity.CircuitBreakerConfig;
import com.payflow.entity.GatewayConfig;
import com.payflow.entity.GatewayHistoricalPerformance;
import com.payflow.ratelimit.GatewayRateLimiter;
import com.payflow.repository.CircuitBreakerConfigRepository;
import com.payflow.repository.GatewayHealthMetricRepository;
import com.payflow.routing.CircuitBreakerService;
import com.payflow.routing.GatewayConfigService;
import com.payflow.routing.GatewayMetricsService;
import com.payflow.routing.RoutingConfigService;
import com.payflow.web.dto.GatewayConfigUpdate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Gateway configuration, health and metrics (A7.1 #13-#16). */
@RestController
@RequestMapping("/api/v1/gateways")
@Tag(name = "Gateways", description = "Configured gateways, circuit breaker health and performance metrics")
public class GatewayController {

    private final GatewayConfigService gatewayConfigs;
    private final CircuitBreakerService circuits;
    private final CircuitBreakerConfigRepository circuitConfigs;
    private final GatewayMetricsService metrics;
    private final GatewayHealthMetricRepository healthMetrics;
    private final GatewayRateLimiter rateLimiter;
    private final RoutingConfigService routingConfig;

    public GatewayController(GatewayConfigService gatewayConfigs, CircuitBreakerService circuits,
                             CircuitBreakerConfigRepository circuitConfigs, GatewayMetricsService metrics,
                             GatewayHealthMetricRepository healthMetrics, GatewayRateLimiter rateLimiter,
                             RoutingConfigService routingConfig) {
        this.gatewayConfigs = gatewayConfigs;
        this.circuits = circuits;
        this.circuitConfigs = circuitConfigs;
        this.metrics = metrics;
        this.healthMetrics = healthMetrics;
        this.rateLimiter = rateLimiter;
        this.routingConfig = routingConfig;
    }

    @GetMapping
    @Operation(summary = "List all configured gateways (#13)")
    public List<Map<String, Object>> list() {
        return gatewayConfigs.all().stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gateway", g.getGatewayName());
            m.put("display_name", g.getDisplayName());
            m.put("enabled", g.isEnabled());
            m.put("supported_methods", g.getSupportedMethods());
            m.put("supported_currencies", g.getSupportedCurrencies());
            m.put("fee", g.getFeeBps() / 100.0 + "% + " + g.getFixedFeePaise() + " paise");
            m.put("health", overallHealth(g).name());
            return m;
        }).toList();
    }

    @GetMapping("/{name}/health")
    @Operation(summary = "Gateway health: circuit breaker state per payment method (#14)")
    public Map<String, Object> health(@PathVariable String name) {
        GatewayConfig g = gatewayConfigs.require(name);
        Map<String, Object> perMethod = new LinkedHashMap<>();
        for (PaymentMethod m : g.methods()) {
            CircuitBreakerConfig cfg = circuits.config(name, m);
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("circuit_state", circuits.state(name, m).name());
            c.put("health", circuits.health(name, m).name());
            c.put("health_score", circuits.health(name, m).score());
            c.put("failure_threshold", cfg.getFailureThreshold());
            c.put("open_timeout_ms", cfg.getOpenTimeoutMs());
            c.put("half_open_max_requests", cfg.getHalfOpenMaxRequests());
            circuits.statesFor(name).stream().filter(s -> s.getPaymentMethod().equals(m.name())).findFirst()
                    .ifPresent(s -> {
                        c.put("consecutive_failures", s.getConsecutiveFailures());
                        c.put("opened_at", s.getOpenedAt());
                        c.put("last_failure_at", s.getLastFailureAt());
                    });
            perMethod.put(m.name(), c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gateway", name);
        out.put("enabled", g.isEnabled());
        out.put("health", overallHealth(g).name());
        out.put("circuits", perMethod);
        return out;
    }

    @GetMapping("/{name}/metrics")
    @Operation(summary = "Gateway performance: sliding-window success rate and P95 latency, history (#15)")
    public Map<String, Object> metrics(@PathVariable String name) {
        GatewayConfig g = gatewayConfigs.require(name);
        RoutingConfigService.Snapshot cfg = routingConfig.current();
        GatewayMetricsService.WindowStats w = metrics.window(name, cfg.windowMinutes());
        GatewayMetricsService.Estimate est = metrics.estimate(name, cfg.windowMinutes(), cfg.minSamples());
        GatewayHistoricalPerformance band = metrics.historicalBand(name, Instant.now());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gateway", name);
        out.put("window_minutes", cfg.windowMinutes());
        out.put("live", Map.of("attempts", w.attempts(), "captured", w.successes(),
                "success_rate", w.attempts() == 0 ? 0.0 : (double) w.successes() / w.attempts(),
                "p95_latency_ms", w.p95LatencyMs(), "avg_latency_ms", w.avgLatencyMs()));
        out.put("router_estimate", Map.of("success_rate", est.successRate(), "p95_latency_ms", est.p95LatencyMs(),
                "source", est.source()));
        if (band != null) {
            out.put("historical_band", Map.of("hours", band.getBandStartHour() + ":00-" + band.getBandEndHour() + ":00 IST",
                    "success_rate", band.getSuccessRate(), "p95_latency_ms", band.getP95LatencyMs(),
                    "transactions", band.getTransactions()));
        }
        out.put("cost", Map.of("fee_bps", g.getFeeBps(), "fixed_fee_paise", g.getFixedFeePaise()));
        out.put("rate_limit", rateLimiter.utilisation().get(name));
        out.put("per_minute", healthMetrics.findByGatewayAndRecordedAtAfterOrderByRecordedAtDesc(name,
                Instant.now().minus(60, ChronoUnit.MINUTES)));
        return out;
    }

    @GetMapping("/{name}/config")
    @Operation(summary = "Current gateway configuration")
    public GatewayConfig getConfig(@PathVariable String name) {
        return gatewayConfigs.require(name);
    }

    @PutMapping("/{name}/config")
    @Operation(summary = "Update gateway configuration and circuit breaker settings without redeploying (#16)")
    public Map<String, Object> updateConfig(@PathVariable String name, @Valid @RequestBody GatewayConfigUpdate u) {
        GatewayConfig updated = gatewayConfigs.update(name, g -> {
            if (u.enabled() != null) g.setEnabled(u.enabled());
            if (u.supportedMethods() != null) {
                java.util.Arrays.stream(u.supportedMethods().split(",")).map(String::trim).forEach(PaymentMethod::parse);
                g.setSupportedMethods(u.supportedMethods().replace(" ", ""));
            }
            if (u.supportedCurrencies() != null) g.setSupportedCurrencies(u.supportedCurrencies().replace(" ", ""));
            if (u.feeBps() != null) g.setFeeBps(u.feeBps());
            if (u.fixedFeePaise() != null) g.setFixedFeePaise(u.fixedFeePaise());
            if (u.rateLimitPerSec() != null) g.setRateLimitPerSec(u.rateLimitPerSec());
            if (u.rateLimitStrategy() != null) {
                g.setRateLimitStrategy(GatewayConfig.RateLimitStrategy.valueOf(u.rateLimitStrategy()));
            }
            if (u.supportsPartialRefund() != null) g.setSupportsPartialRefund(u.supportsPartialRefund());
            if (u.authHoldDays() != null) g.setAuthHoldDays(u.authHoldDays());
        });
        if (u.circuitFailureThreshold() != null || u.circuitOpenTimeoutMs() != null
                || u.circuitHalfOpenMaxRequests() != null) {
            String method = u.circuitPaymentMethod() == null ? CircuitBreakerConfig.ANY : u.circuitPaymentMethod();
            CircuitBreakerConfig base = circuits.config(name, method.equals("*") ? PaymentMethod.CARD
                    : PaymentMethod.parse(method));
            CircuitBreakerConfig row = circuitConfigs.findByGatewayAndPaymentMethod(name, method)
                    .orElseGet(() -> new CircuitBreakerConfig(name, method, base.getFailureThreshold(),
                            base.getOpenTimeoutMs(), base.getHalfOpenMaxRequests()));
            row.update(u.circuitFailureThreshold(), u.circuitOpenTimeoutMs(), u.circuitHalfOpenMaxRequests());
            circuitConfigs.save(row);
            circuits.invalidateConfig();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", updated);
        out.put("health", health(name));
        return out;
    }

    private HealthStatus overallHealth(GatewayConfig g) {
        if (!g.isEnabled()) return HealthStatus.DOWN;
        HealthStatus worst = HealthStatus.HEALTHY;
        for (PaymentMethod m : g.methods()) {
            HealthStatus h = circuits.health(g.getGatewayName(), m);
            if (h.score() < worst.score()) worst = h;
        }
        return worst;
    }
}
