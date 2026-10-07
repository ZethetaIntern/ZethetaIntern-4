package com.payflow.web;

import com.payflow.db.DatabaseGuard;
import com.payflow.domain.HealthStatus;
import com.payflow.domain.PaymentMethod;
import com.payflow.entity.GatewayConfig;
import com.payflow.routing.CircuitBreakerService;
import com.payflow.routing.GatewayConfigService;
import com.payflow.webhook.WebhookQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** System health check (A7.1 #23), used by docker-compose and the test harness. */
@RestController
@Tag(name = "Health")
public class HealthController {

    private final JdbcTemplate jdbc;
    private final DatabaseGuard dbGuard;
    private final GatewayConfigService gatewayConfigs;
    private final CircuitBreakerService circuits;
    private final WebhookQueueService webhookQueue;

    public HealthController(JdbcTemplate jdbc, DatabaseGuard dbGuard, GatewayConfigService gatewayConfigs,
                            CircuitBreakerService circuits, WebhookQueueService webhookQueue) {
        this.jdbc = jdbc;
        this.dbGuard = dbGuard;
        this.gatewayConfigs = gatewayConfigs;
        this.circuits = circuits;
        this.webhookQueue = webhookQueue;
    }

    @GetMapping("/api/v1/health")
    @Operation(summary = "System health check (#23)", description = "UP / DEGRADED answer 200, DOWN answers 503. "
            + "DEGRADED: some gateway circuit is open or the webhook DLQ is non-empty.")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean dbUp;
        try {
            dbUp = Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class));
        } catch (RuntimeException e) {
            dbUp = false;
        }
        Map<String, Object> gateways = new LinkedHashMap<>();
        boolean anyDown = false;
        if (dbUp) {
            for (GatewayConfig g : gatewayConfigs.all()) {
                HealthStatus worst = HealthStatus.HEALTHY;
                for (PaymentMethod m : g.methods()) {
                    HealthStatus h = circuits.health(g.getGatewayName(), m);
                    if (h.score() < worst.score()) worst = h;
                }
                anyDown |= worst != HealthStatus.HEALTHY;
                gateways.put(g.getGatewayName(), worst.name());
            }
        }
        long dlq = dbUp ? webhookQueue.depth() : -1;
        String status = !dbUp ? "DOWN" : (anyDown || dlq > 0 || dbGuard.isOpen()) ? "DEGRADED" : "UP";
        out.put("status", status);
        out.put("timestamp", Instant.now().toString());
        out.put("database", Map.of("status", dbUp ? "UP" : "DOWN", "pool", dbGuard.poolStats()));
        out.put("gateways", gateways);
        out.put("webhook_dlq_depth", dlq);
        return ResponseEntity.status(dbUp ? 200 : 503).body(out);
    }
}
