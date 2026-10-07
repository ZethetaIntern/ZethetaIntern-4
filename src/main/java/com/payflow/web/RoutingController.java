package com.payflow.web;

import com.payflow.domain.PaymentMethod;
import com.payflow.entity.RoutingConfigEntry;
import com.payflow.error.ApiException;
import com.payflow.routing.GatewayRouter;
import com.payflow.routing.RoutingConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Routing weights and thresholds (A7.1 #17-#18), plus a decision preview. */
@RestController
@RequestMapping("/api/v1/routing")
@Validated
@Tag(name = "Routing", description = "Routing algorithm weights and thresholds, stored in routing_config")
public class RoutingController {

    /** Short aliases accepted in PUT bodies, e.g. {"success_rate": 0.4}. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("success_rate", RoutingConfigService.W_SUCCESS),
            Map.entry("success", RoutingConfigService.W_SUCCESS),
            Map.entry("latency", RoutingConfigService.W_LATENCY),
            Map.entry("cost", RoutingConfigService.W_COST),
            Map.entry("health", RoutingConfigService.W_HEALTH),
            Map.entry("method_fit", RoutingConfigService.W_FIT),
            Map.entry("fit", RoutingConfigService.W_FIT),
            Map.entry("window_minutes", RoutingConfigService.WINDOW_MINUTES),
            Map.entry("min_samples", RoutingConfigService.MIN_SAMPLES),
            Map.entry("degraded_success_rate", RoutingConfigService.DEGRADED_SUCCESS_RATE),
            Map.entry("degraded_margin", RoutingConfigService.DEGRADED_MARGIN));

    private final RoutingConfigService routingConfig;
    private final GatewayRouter router;

    public RoutingController(RoutingConfigService routingConfig, GatewayRouter router) {
        this.routingConfig = routingConfig;
        this.router = router;
    }

    @GetMapping("/config")
    @Operation(summary = "Current routing weights and thresholds (#17)")
    public Map<String, Object> get() {
        return render(routingConfig.entries());
    }

    @PutMapping("/config")
    @Operation(summary = "Update routing weights / thresholds (#18)",
            description = "Accepts {\"weights\": {\"success_rate\": 0.4, ...}} or flat keys. "
                    + "The five weights must sum to 1.0.")
    public Map<String, Object> update(@RequestBody
                                      @Schema(example = "{\"weights\":{\"success_rate\":0.40,\"latency\":0.20,"
                                              + "\"cost\":0.15,\"health\":0.15,\"method_fit\":0.10}}")
                                      Map<String, Object> body) {
        Map<String, BigDecimal> changes = new LinkedHashMap<>();
        flatten(body, changes);
        if (changes.isEmpty()) throw ApiException.badRequest("VALIDATION_FAILED", "no routing config keys supplied");
        routingConfig.update(changes, "api");
        return get();
    }

    @GetMapping("/preview")
    @Operation(summary = "Explain how a payment would be routed right now, with per-factor scores")
    public GatewayRouter.Decision preview(@RequestParam("payment_method") String paymentMethod,
                                          @RequestParam(value = "amount_paise", defaultValue = "100000") @Positive long amount,
                                          @RequestParam(value = "currency", defaultValue = "INR") String currency) {
        return router.decide(PaymentMethod.parse(paymentMethod), currency.toUpperCase(java.util.Locale.ROOT), amount);
    }

    private static void flatten(Map<String, Object> body, Map<String, BigDecimal> out) {
        body.forEach((k, v) -> {
            if (v instanceof Map<?, ?> nested) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) nested;
                flatten(m, out);
            } else {
                String key = ALIASES.getOrDefault(k, k);
                try {
                    out.put(key, new BigDecimal(String.valueOf(v)));
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("VALIDATION_FAILED", k + " must be a number");
                }
            }
        });
    }

    private static Map<String, Object> render(List<RoutingConfigEntry> entries) {
        Map<String, Object> weights = new LinkedHashMap<>();
        Map<String, Object> thresholds = new LinkedHashMap<>();
        for (RoutingConfigEntry e : entries) {
            String k = e.getConfigKey();
            if (k.startsWith("weight.")) weights.put(k.substring(7), e.getConfigValue());
            else thresholds.put(k, e.getConfigValue());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("weights", weights);
        out.put("thresholds", thresholds);
        out.put("entries", entries);
        return out;
    }
}
