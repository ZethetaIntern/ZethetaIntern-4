package com.payflow.web;

import com.payflow.alert.AlertService;
import com.payflow.db.DatabaseGuard;
import com.payflow.domain.PaymentMethod;
import com.payflow.entity.Anomaly;
import com.payflow.entity.GatewayConfig;
import com.payflow.entity.NotificationOutbox;
import com.payflow.entity.SecurityAuditLog;
import com.payflow.entity.WebhookQueueItem;
import com.payflow.notification.NotificationService;
import com.payflow.ratelimit.GatewayRateLimiter;
import com.payflow.repository.AnomalyRepository;
import com.payflow.routing.CircuitBreakerService;
import com.payflow.routing.GatewayConfigService;
import com.payflow.security.SecurityAuditService;
import com.payflow.webhook.WebhookEventProcessor;
import com.payflow.webhook.WebhookIngestionService;
import com.payflow.webhook.WebhookQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operator endpoints: DLQ (A8.3), rate limits (A8.4), circuits (A3.3), anomalies (A5.5), security log (FS-10). */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Operations", description = "DLQ, rate limiter, circuit breakers, anomalies, security events")
public class AdminController {

    private final WebhookQueueService webhookQueue;
    private final WebhookIngestionService ingestion;
    private final GatewayRateLimiter rateLimiter;
    private final CircuitBreakerService circuits;
    private final GatewayConfigService gatewayConfigs;
    private final AnomalyRepository anomalies;
    private final SecurityAuditService security;
    private final DatabaseGuard dbGuard;
    private final AlertService alerts;
    private final NotificationService notifications;
    private final com.payflow.statemachine.TransactionStateMachine machine;

    public AdminController(WebhookQueueService webhookQueue, WebhookIngestionService ingestion,
                           GatewayRateLimiter rateLimiter, CircuitBreakerService circuits,
                           GatewayConfigService gatewayConfigs, AnomalyRepository anomalies,
                           SecurityAuditService security, DatabaseGuard dbGuard, AlertService alerts,
                           NotificationService notifications,
                           com.payflow.statemachine.TransactionStateMachine machine) {
        this.machine = machine;
        this.webhookQueue = webhookQueue;
        this.ingestion = ingestion;
        this.rateLimiter = rateLimiter;
        this.circuits = circuits;
        this.gatewayConfigs = gatewayConfigs;
        this.anomalies = anomalies;
        this.security = security;
        this.dbGuard = dbGuard;
        this.alerts = alerts;
        this.notifications = notifications;
    }

    @GetMapping("/webhooks/dlq")
    @Operation(summary = "Dead letter queue depth and items (any non-zero depth raises an alert)")
    public Map<String, Object> dlq() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("depth", webhookQueue.depth());
        out.put("queue", webhookQueue.statusCounts());
        out.put("items", webhookQueue.deadLettered());
        return out;
    }

    @PostMapping("/webhooks/dlq/{id}/replay")
    @Operation(summary = "Replay one dead-lettered webhook after the root cause is fixed")
    public Map<String, Object> replay(@PathVariable long id) {
        WebhookQueueItem item = webhookQueue.resetForReplay(id);
        WebhookEventProcessor.Outcome outcome = ingestion.replay(item.getId());
        Map<String, Object> out = new LinkedHashMap<>(WebhookEventProcessor.describe(outcome));
        out.put("queue_id", id);
        return out;
    }

    @GetMapping("/rate-limits")
    @Operation(summary = "Per-gateway rate limiter utilisation, e.g. \"145/200 req/sec\"")
    public Map<String, Map<String, Object>> rateLimits() {
        return rateLimiter.utilisation();
    }

    @GetMapping("/circuits")
    @Operation(summary = "Circuit breaker state for every gateway and payment method")
    public Map<String, Map<String, String>> circuits() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (GatewayConfig g : gatewayConfigs.all()) {
            Map<String, String> perMethod = new LinkedHashMap<>();
            g.methods().stream().sorted().forEach(m -> perMethod.put(m.name(), circuits.state(g.getGatewayName(), m).name()));
            out.put(g.getGatewayName(), perMethod);
        }
        return out;
    }

    @PostMapping("/circuits/{gateway}/{method}/open")
    @Operation(summary = "Trip a circuit manually (incident response / drills)")
    public Map<String, Map<String, String>> open(@PathVariable String gateway, @PathVariable String method) {
        gatewayConfigs.require(gateway);
        circuits.forceOpen(gateway, PaymentMethod.parse(method));
        return circuits();
    }

    @PostMapping("/circuits/{gateway}/{method}/reset")
    @Operation(summary = "Close a circuit manually")
    public Map<String, Map<String, String>> reset(@PathVariable String gateway, @PathVariable String method) {
        gatewayConfigs.require(gateway);
        circuits.reset(gateway, PaymentMethod.parse(method));
        return circuits();
    }

    @GetMapping("/anomalies")
    @Operation(summary = "Reconciliation and webhook anomalies awaiting human review")
    public List<Anomaly> anomalies() {
        return anomalies.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping("/anomalies/{id}/resolve")
    @Operation(summary = "Human review outcome for an anomaly (FS-11)",
            description = "resolution=SETTLED (gateway was wrong, funds arrived) or WRITE_OFF (payment lost -> FAILED). "
                    + "To give the money back, call POST /payments/{id}/refund instead; it is allowed from "
                    + "RECONCILIATION_MISMATCH. Nothing here is automatic.")
    public Anomaly resolve(@PathVariable UUID id, @RequestParam("resolution") String resolution) {
        Anomaly a = anomalies.findById(id).orElseThrow(() -> com.payflow.error.ApiException.notFound("anomaly " + id));
        com.payflow.domain.TransactionState target = switch (resolution.toUpperCase(java.util.Locale.ROOT)) {
            case "SETTLED" -> com.payflow.domain.TransactionState.SETTLED;
            case "WRITE_OFF" -> com.payflow.domain.TransactionState.FAILED;
            default -> throw com.payflow.error.ApiException.badRequest("INVALID_REQUEST",
                    "resolution must be SETTLED or WRITE_OFF");
        };
        if (machine.transitionIfAllowed(a.getTransactionId(), target,
                com.payflow.statemachine.Audit.of("ANOMALY_RESOLVED", "operator").meta("anomaly_id", id.toString())
                        .meta("resolution", resolution)).isEmpty()) {
            throw com.payflow.error.ApiException.unprocessable("NOT_RESOLVABLE",
                    "the payment is no longer awaiting review");
        }
        a.setStatus(Anomaly.Status.RESOLVED);
        return anomalies.save(a);
    }

    @GetMapping("/security-events")
    @Operation(summary = "Recent security audit events (rejected webhooks, invalid API keys) with source IP")
    public List<SecurityAuditLog> securityEvents() {
        return security.recent();
    }

    @GetMapping("/alerts")
    @Operation(summary = "Recently raised alerts")
    public List<AlertService.Alert> alerts() {
        return alerts.recent();
    }

    @GetMapping("/db-pool")
    @Operation(summary = "Database connection pool utilisation and internal circuit state")
    public Map<String, Object> dbPool() {
        return dbGuard.poolStats();
    }

    @GetMapping("/notifications")
    @Operation(summary = "Customer notifications queued or sent for a payment")
    public List<NotificationOutbox> notifications(@RequestParam("transaction_id") UUID transactionId) {
        return notifications.forTransaction(transactionId);
    }
}
