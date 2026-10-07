package com.payflow.routing;

import com.payflow.entity.RoutingConfigEntry;
import com.payflow.error.ApiException;
import com.payflow.repository.RoutingConfigRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Routing weights and thresholds from {@code routing_config} (A3.1: "stored in
 * the database, not hardcoded"). Values are cached for a few seconds so the hot
 * path does not query them per payment; an update through the API takes effect
 * immediately on this instance and within the cache TTL on others.
 */
@Service
public class RoutingConfigService {

    public static final String W_SUCCESS = "weight.success_rate";
    public static final String W_LATENCY = "weight.latency";
    public static final String W_COST = "weight.cost";
    public static final String W_HEALTH = "weight.health";
    public static final String W_FIT = "weight.method_fit";
    public static final String WINDOW_MINUTES = "window.minutes";
    public static final String MIN_SAMPLES = "window.min_samples";
    public static final String DEGRADED_SUCCESS_RATE = "health.degraded_success_rate";
    public static final String DEGRADED_MARGIN = "routing.degraded_margin";

    public static final List<String> WEIGHT_KEYS = List.of(W_SUCCESS, W_LATENCY, W_COST, W_HEALTH, W_FIT);

    private static final Duration TTL = Duration.ofSeconds(5);

    /** Snapshot of the routing parameters used for one decision. */
    public record Weights(double success, double latency, double cost, double health, double fit) {}

    public record Snapshot(Weights weights, int windowMinutes, int minSamples, double degradedSuccessRate,
                           double degradedMargin) {}

    private final RoutingConfigRepository repo;
    private volatile Snapshot cached;
    private volatile Instant cachedAt = Instant.EPOCH;

    public RoutingConfigService(RoutingConfigRepository repo) {
        this.repo = repo;
    }

    public Snapshot current() {
        Snapshot s = cached;
        if (s == null || Instant.now().isAfter(cachedAt.plus(TTL))) {
            s = load();
            cached = s;
            cachedAt = Instant.now();
        }
        return s;
    }

    public List<RoutingConfigEntry> entries() {
        return repo.findAll().stream()
                .sorted(java.util.Comparator.comparing(RoutingConfigEntry::getConfigKey)).toList();
    }

    /**
     * Updates any subset of keys. Weights must each be within [0, 1] and the
     * five weights must sum to 1.0 so scores stay on a 0-1 scale.
     */
    @Transactional
    public Snapshot update(Map<String, BigDecimal> changes, String updatedBy) {
        Map<String, RoutingConfigEntry> all = new LinkedHashMap<>();
        repo.findAll().forEach(e -> all.put(e.getConfigKey(), e));
        for (Map.Entry<String, BigDecimal> c : changes.entrySet()) {
            RoutingConfigEntry e = all.get(c.getKey());
            if (e == null) {
                throw ApiException.unprocessable("UNKNOWN_CONFIG_KEY", "unknown routing config key: " + c.getKey());
            }
            if (c.getValue() == null || c.getValue().signum() < 0) {
                throw ApiException.unprocessable("INVALID_CONFIG_VALUE", c.getKey() + " must be a non-negative number");
            }
            if (WEIGHT_KEYS.contains(c.getKey()) && c.getValue().compareTo(BigDecimal.ONE) > 0) {
                throw ApiException.unprocessable("INVALID_CONFIG_VALUE", c.getKey() + " must be between 0 and 1");
            }
        }
        BigDecimal sum = WEIGHT_KEYS.stream()
                .map(k -> changes.containsKey(k) ? changes.get(k) : all.get(k).getConfigValue())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sum.subtract(BigDecimal.ONE).abs().compareTo(new BigDecimal("0.001")) > 0) {
            throw new ApiException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "ROUTING_WEIGHTS_INVALID",
                    "routing weights must sum to 1.0", Map.of("sum", sum.setScale(4, RoundingMode.HALF_UP)));
        }
        changes.forEach((k, v) -> all.get(k).update(v, updatedBy));
        repo.saveAll(all.values());
        cached = null;
        return current();
    }

    public void invalidate() {
        cached = null;
    }

    private Snapshot load() {
        Map<String, Double> v = new LinkedHashMap<>();
        repo.findAll().forEach(e -> v.put(e.getConfigKey(), e.getConfigValue().doubleValue()));
        Weights w = new Weights(v.getOrDefault(W_SUCCESS, 0.35), v.getOrDefault(W_LATENCY, 0.20),
                v.getOrDefault(W_COST, 0.20), v.getOrDefault(W_HEALTH, 0.15), v.getOrDefault(W_FIT, 0.10));
        return new Snapshot(w, v.getOrDefault(WINDOW_MINUTES, 15.0).intValue(),
                v.getOrDefault(MIN_SAMPLES, 20.0).intValue(), v.getOrDefault(DEGRADED_SUCCESS_RATE, 0.90),
                v.getOrDefault(DEGRADED_MARGIN, 0.20));
    }
}
