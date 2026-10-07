package com.payflow.jobs;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.payflow.config.PayFlowProperties;
import com.payflow.domain.AuditEvent;
import com.payflow.domain.HealthStatus;
import com.payflow.domain.PaymentMethod;
import com.payflow.domain.TransactionState;
import com.payflow.entity.GatewayConfig;
import com.payflow.entity.GatewayHealthMetric;
import com.payflow.entity.Transaction;
import com.payflow.gateway.GatewayCaller;
import com.payflow.gateway.GatewayException;
import com.payflow.gateway.GatewayRegistry;
import com.payflow.gateway.GatewayStatus;
import com.payflow.gateway.PaymentGateway;
import com.payflow.idempotency.IdempotencyService;
import com.payflow.notification.NotificationService;
import com.payflow.payment.AuthorisationOrchestrator;
import com.payflow.repository.GatewayHealthMetricRepository;
import com.payflow.repository.TransactionRepository;
import com.payflow.routing.CircuitBreakerService;
import com.payflow.routing.GatewayConfigService;
import com.payflow.routing.GatewayMetricsService;
import com.payflow.statemachine.Audit;
import com.payflow.statemachine.TransactionStateMachine;
import com.payflow.tracing.TraceContext;
import com.payflow.webhook.WebhookEventProcessor;
import com.payflow.webhook.WebhookQueueService;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/**
 * The work behind each background job. Kept separate from the scheduling so
 * tests can run a job deterministically. Every job is safe to run on several
 * instances at once: rows are claimed under locks and every state change goes
 * through the state machine, which rejects stale transitions.
 */
@Service
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);
    private static final int BATCH = 100;

    private final TransactionRepository transactions;
    private final TransactionStateMachine machine;
    private final AuthorisationOrchestrator orchestrator;
    private final WebhookQueueService webhookQueue;
    private final WebhookEventProcessor webhookProcessor;
    private final GatewayRegistry gateways;
    private final GatewayCaller caller;
    private final GatewayConfigService gatewayConfigs;
    private final GatewayMetricsService metrics;
    private final CircuitBreakerService circuits;
    private final GatewayHealthMetricRepository healthMetrics;
    private final IdempotencyService idempotency;
    private final NotificationService notifications;
    private final PayFlowProperties props;

    public MaintenanceService(TransactionRepository transactions, TransactionStateMachine machine,
                              AuthorisationOrchestrator orchestrator, WebhookQueueService webhookQueue,
                              WebhookEventProcessor webhookProcessor, GatewayRegistry gateways, GatewayCaller caller,
                              GatewayConfigService gatewayConfigs, GatewayMetricsService metrics,
                              CircuitBreakerService circuits, GatewayHealthMetricRepository healthMetrics,
                              IdempotencyService idempotency, NotificationService notifications,
                              PayFlowProperties props) {
        this.transactions = transactions;
        this.machine = machine;
        this.orchestrator = orchestrator;
        this.webhookQueue = webhookQueue;
        this.webhookProcessor = webhookProcessor;
        this.gateways = gateways;
        this.caller = caller;
        this.gatewayConfigs = gatewayConfigs;
        this.metrics = metrics;
        this.circuits = circuits;
        this.healthMetrics = healthMetrics;
        this.idempotency = idempotency;
        this.notifications = notifications;
        this.props = props;
    }

    /** Webhook queue consumer: retries deferred/failed events, DLQs after max retries (A8.3). */
    public int processWebhookQueue() {
        List<Long> ids = webhookQueue.claimDue(BATCH);
        ids.forEach(webhookProcessor::processSafely);
        return ids.size();
    }

    /** Asynchronous payment retry queue (C1.4, FS-07): payments parked when no gateway could take them. */
    public int retryParkedPayments() {
        List<Transaction> due = transactions.findByStateAndNextRetryAtBeforeOrderByNextRetryAtAsc(
                TransactionState.ROUTE_SELECTED, Instant.now(), PageRequest.of(0, BATCH));
        int n = 0;
        for (Transaction t : due) {
            if (!orchestrator.claimParked(t.getId())) continue;
            TraceContext.bindTransaction(t.getId(), t.getTraceId());
            orchestrator.process(t.getId());
            n++;
        }
        return n;
    }

    /**
     * FS-12: UPI collect requests whose mandate window passed. The UPI status is
     * polled once; an unapproved collect becomes AUTH_EXPIRED, the customer is
     * notified, and nothing is retried (a collect cannot be force-retried).
     */
    public int expireUpiCollects() {
        List<Transaction> expired = transactions.findByStateInAndAuthExpiresAtBeforeOrderByAuthExpiresAtAsc(
                EnumSet.of(TransactionState.AUTH_INITIATED), Instant.now(), PageRequest.of(0, BATCH));
        int n = 0;
        for (Transaction t : expired) {
            TraceContext.bindTransaction(t.getId(), t.getTraceId());
            GatewayStatus status = poll(t);
            Audit audit = Audit.of(AuditEvent.AUTH_HOLD_EXPIRED, "upi_collect_expiry_job")
                    .meta("gateway_status", status.name()).meta("collect_expired_at", String.valueOf(t.getAuthExpiresAt()));
            switch (status) {
                case CAPTURED -> machine.transitionIfAllowed(t.getId(), TransactionState.CAPTURED,
                        audit.mutate(x -> x.setCapturedPaise(x.getAmountPaise())));
                case FAILED -> {
                    machine.transitionIfAllowed(t.getId(), TransactionState.AUTH_FAILED, audit);
                    machine.transitionIfAllowed(t.getId(), TransactionState.FAILED, audit);
                    notifications.enqueue(load(t.getId()), NotificationService.PAYMENT_FAILED,
                            "Your UPI payment was declined. No money was debited.");
                }
                default -> {
                    if (machine.transitionIfAllowed(t.getId(), TransactionState.AUTH_EXPIRED, audit
                            .meta("retry", "not attempted: UPI collect cannot be force-retried")
                            .mutate(x -> {
                                x.setFailureCode("UPI_COLLECT_EXPIRED");
                                x.setFailureReason("customer did not approve the collect request in time");
                            })).isPresent()) {
                        notifications.enqueue(load(t.getId()), NotificationService.UPI_COLLECT_EXPIRED,
                                "Your UPI payment request expired. No money was debited; please try again.");
                    }
                }
            }
            n++;
        }
        return n;
    }

    /** A1.2: an authorisation not captured within the hold period is released by the gateway. */
    public int expireAuthorisationHolds() {
        List<Transaction> expired = transactions.findByStateInAndAuthExpiresAtBeforeOrderByAuthExpiresAtAsc(
                EnumSet.of(TransactionState.AUTHORISED), Instant.now(), PageRequest.of(0, BATCH));
        for (Transaction t : expired) {
            TraceContext.bindTransaction(t.getId(), t.getTraceId());
            machine.transitionIfAllowed(t.getId(), TransactionState.AUTH_EXPIRED,
                    Audit.of(AuditEvent.AUTH_HOLD_EXPIRED, "auth_expiry_job")
                            .mutate(x -> x.setReleasedPaise(x.getAmountPaise() - x.getCapturedPaise())))
                    .ifPresent(x -> notifications.enqueue(x, NotificationService.AUTH_HOLD_EXPIRED,
                            "The authorisation hold expired before capture and was released."));
        }
        return expired.size();
    }

    /** CREATED payments that were never routed (client dropped off / crash before routing). */
    public int abandonStalePayments() {
        Instant cutoff = Instant.now().minus(props.jobs().abandonAfter());
        List<Transaction> stale = transactions.findByStateAndCreatedAtBeforeOrderByCreatedAtAsc(
                TransactionState.CREATED, cutoff, PageRequest.of(0, BATCH));
        stale.forEach(t -> machine.transitionIfAllowed(t.getId(), TransactionState.ABANDONED,
                Audit.of(AuditEvent.ABANDONED, "abandonment_job").meta("created_at", t.getCreatedAt().toString())));
        return stale.size();
    }

    /** Writes the per-minute aggregate rows of {@code gateway_health_metrics} for the minute that just closed. */
    public int flushHealthMetrics() {
        long minute = GatewayMetricsService.minuteOf(Instant.now()) - 1;
        Instant recordedAt = Instant.ofEpochSecond(minute * 60);
        int written = 0;
        for (GatewayConfig g : gatewayConfigs.all()) {
            GatewayMetricsService.WindowStats w = metrics.window(g.getGatewayName(), minute, minute);
            if (w.attempts() == 0 || healthMetrics.existsByGatewayAndRecordedAt(g.getGatewayName(), recordedAt)) {
                continue;
            }
            healthMetrics.save(new GatewayHealthMetric(g.getGatewayName(), recordedAt, w.attempts(), w.successes(),
                    w.p95LatencyMs(), w.avgLatencyMs(), worstHealth(g)));
            written++;
        }
        return written;
    }

    private HealthStatus worstHealth(GatewayConfig g) {
        HealthStatus worst = HealthStatus.HEALTHY;
        for (PaymentMethod m : g.methods()) {
            HealthStatus h = circuits.health(g.getGatewayName(), m);
            if (h.score() < worst.score()) worst = h;
        }
        return worst;
    }

    public int purgeIdempotencyKeys() {
        int n = idempotency.purgeExpired();
        if (n > 0) log.info("expired idempotency keys purged {}", kv("count", n));
        return n;
    }

    public int dispatchNotifications() {
        return notifications.dispatchPending();
    }

    private GatewayStatus poll(Transaction t) {
        if (t.getGateway() == null) return GatewayStatus.UNKNOWN;
        try {
            PaymentGateway gateway = gateways.get(t.getGateway());
            return caller.call(t.getGateway(), props.orchestration().attemptTimeoutMs(),
                    () -> gateway.fetchStatus(t.getGatewayReference(), t.getId())).status();
        } catch (GatewayException e) {
            return GatewayStatus.UNKNOWN;
        }
    }

    private Transaction load(UUID id) {
        return transactions.findById(id).orElseThrow();
    }
}
