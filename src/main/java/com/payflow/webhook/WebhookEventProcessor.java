package com.payflow.webhook;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.payflow.alert.AlertService;
import com.payflow.config.PayFlowProperties;
import com.payflow.domain.AuditEvent;
import com.payflow.domain.TransactionState;
import com.payflow.entity.Anomaly;
import com.payflow.entity.Refund;
import com.payflow.entity.SecurityAuditLog;
import com.payflow.entity.Transaction;
import com.payflow.entity.WebhookQueueItem;
import com.payflow.gateway.GatewayCaller;
import com.payflow.gateway.GatewayException;
import com.payflow.gateway.GatewayRegistry;
import com.payflow.gateway.GatewayStatus;
import com.payflow.gateway.PaymentGateway;
import com.payflow.notification.NotificationService;
import com.payflow.repository.AnomalyRepository;
import com.payflow.repository.RefundRepository;
import com.payflow.repository.TransactionRepository;
import com.payflow.repository.WebhookQueueRepository;
import com.payflow.routing.GatewayConfigService;
import com.payflow.routing.GatewayMetricsService;
import com.payflow.security.SecurityAuditService;
import com.payflow.statemachine.Audit;
import com.payflow.statemachine.TransactionStateMachine;
import com.payflow.tracing.TraceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Event processor stage of the webhook pipeline (A5.2): applies state-machine
 * transitions for a queued, already-verified and deduplicated event.
 *
 * <p>Before anything changes, the C4.3 checks run: the amount, currency and
 * gateway reference must match what we stored, and the reported status must be
 * a valid next step from the current state. Events that arrive ahead of the
 * state they depend on (out-of-order delivery, A5.1) are deferred and retried
 * with backoff; events that are behind the current state are acknowledged as
 * no-ops, so duplicates never cause a second transition.</p>
 */
@Service
public class WebhookEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventProcessor.class);
    private static final String ACTOR = "webhook_processor";
    private static final Set<TransactionState> PRE_GATEWAY =
            EnumSet.of(TransactionState.CREATED, TransactionState.ROUTE_SELECTED);
    private static final Set<TransactionState> CAPTURED_LIKE = EnumSet.of(TransactionState.CAPTURED,
            TransactionState.PARTIALLY_CAPTURED, TransactionState.SETTLED);

    /** What happened to a queued event. */
    public enum Result { APPLIED, NO_OP, DEFERRED, REJECTED }

    public record Outcome(Result result, String reason, UUID transactionId, TransactionState state) {}

    private final WebhookQueueRepository queue;
    private final WebhookQueueService queueService;
    private final TransactionRepository transactions;
    private final RefundRepository refunds;
    private final AnomalyRepository anomalies;
    private final TransactionStateMachine machine;
    private final GatewayConfigService gatewayConfigs;
    private final GatewayMetricsService metrics;
    private final NotificationService notifications;
    private final SecurityAuditService security;
    private final AlertService alerts;
    private final GatewayRegistry gateways;
    private final GatewayCaller caller;
    private final PayFlowProperties props;
    private final TransactionTemplate tx;

    public WebhookEventProcessor(WebhookQueueRepository queue, WebhookQueueService queueService,
                                 TransactionRepository transactions, RefundRepository refunds,
                                 AnomalyRepository anomalies, TransactionStateMachine machine,
                                 GatewayConfigService gatewayConfigs, GatewayMetricsService metrics,
                                 NotificationService notifications, SecurityAuditService security,
                                 AlertService alerts, GatewayRegistry gateways, GatewayCaller caller,
                                 PayFlowProperties props, PlatformTransactionManager txManager) {
        this.tx = new TransactionTemplate(txManager);
        this.queue = queue;
        this.queueService = queueService;
        this.transactions = transactions;
        this.refunds = refunds;
        this.anomalies = anomalies;
        this.machine = machine;
        this.gatewayConfigs = gatewayConfigs;
        this.metrics = metrics;
        this.notifications = notifications;
        this.security = security;
        this.alerts = alerts;
        this.gateways = gateways;
        this.caller = caller;
        this.props = props;
    }

    /** Processes an item; unexpected errors schedule a retry instead of losing the event (A8.3). */
    public Outcome processSafely(long queueId) {
        try {
            return process(queueId);
        } catch (RuntimeException e) {
            log.error("webhook processing failed {} {}", kv("component", "webhook_processor"),
                    kv("queue_id", queueId), e);
            queueService.retryLater(queueId, e.getClass().getSimpleName() + ": " + e.getMessage());
            return new Outcome(Result.DEFERRED, "processing_error", null, null);
        }
    }

    /** Processes one queued event in a single database transaction. */
    public Outcome process(long queueId) {
        return tx.execute(status -> processInTransaction(queueId));
    }

    private Outcome processInTransaction(long queueId) {
        WebhookQueueItem item = queue.findByIdForUpdate(queueId).orElse(null);
        if (item == null || item.getStatus() == WebhookQueueItem.Status.COMPLETED
                || item.getStatus() == WebhookQueueItem.Status.DLQ) {
            return new Outcome(Result.NO_OP, "already_processed", null, null);
        }
        Map<?, ?> normalized = (Map<?, ?>) item.getPayload().get("normalized");
        NormalizedWebhookEvent ev = NormalizedWebhookEvent.fromMap(normalized);
        String gateway = item.getGateway();

        Transaction t = resolve(gateway, ev);
        if (t == null) {
            // Our transaction row always commits before the gateway is called, so an
            // event that matches nothing is not ours (or not a payment): acknowledge it.
            log.warn("unmatched webhook {} {} {}", kv("component", "webhook_processor"), kv("gateway", gateway),
                    kv("event_id", ev.eventId()));
            return complete(item, new Outcome(Result.NO_OP, "unmatched_event", null, null));
        }
        TraceContext.bindTransaction(t.getId(), t.getTraceId());

        String rejection = verify(t, gateway, ev);
        if (rejection != null) {
            security.record(SecurityAuditLog.WEBHOOK_VERIFICATION_FAILED, gateway, item.getSourceIp(), null,
                    "/api/v1/webhooks/" + gateway, Map.of("reason", rejection, "event_id", ev.eventId(),
                            "transaction_id", t.getId().toString()));
            queueService.deadLetter(item, rejection);
            return new Outcome(Result.REJECTED, rejection, t.getId(), t.getState());
        }
        if (t.getGateway() != null && !t.getGateway().equals(gateway)) {
            return complete(item, handleOtherGateway(t, gateway, ev));
        }
        if (t.getGateway() == null) {
            return defer(item, "payment_not_routed_yet", t.getId(), t.getState());
        }

        Outcome outcome = apply(t, gateway, ev, item);
        if (outcome.result() == Result.DEFERRED) {
            return defer(item, outcome.reason(), t.getId(), t.getState());
        }
        return complete(item, outcome);
    }

    private Transaction resolve(String gateway, NormalizedWebhookEvent ev) {
        if (ev.gatewayReference() != null) {
            Optional<Transaction> byRef = transactions.findByGatewayAndGatewayReference(gateway, ev.gatewayReference())
                    .stream().findFirst();
            if (byRef.isPresent()) return byRef.get();
        }
        return ev.transactionId() == null ? null : transactions.findById(ev.transactionId()).orElse(null);
    }

    /** C4.3 critical verification steps; returns a rejection reason or null. */
    private String verify(Transaction t, String gateway, NormalizedWebhookEvent ev) {
        if (ev.transactionId() != null && !ev.transactionId().equals(t.getId())) {
            return "TRANSACTION_ID_MISMATCH";
        }
        if (ev.currency() != null && !ev.currency().equalsIgnoreCase(t.getCurrency())) {
            return "CURRENCY_MISMATCH: webhook " + ev.currency() + " != " + t.getCurrency();
        }
        if (gateway.equals(t.getGateway()) && ev.gatewayReference() != null && t.getGatewayReference() != null
                && !ev.gatewayReference().equals(t.getGatewayReference())) {
            return "GATEWAY_REFERENCE_MISMATCH";
        }
        Long amount = ev.amountPaise();
        if (amount != null) {
            switch (ev.status()) {
                case AUTHORISED -> {
                    if (amount != t.getAmountPaise()) return amountMismatch(amount, t.getAmountPaise());
                }
                case CAPTURED -> {
                    // A smaller captured amount is only legitimate while a (partial) capture we
                    // requested is in flight; otherwise it is the C4 attack (₹10 marking ₹50,000 paid).
                    boolean fullCapture = amount == t.getAmountPaise();
                    boolean ourPartialCapture = EnumSet.of(TransactionState.CAPTURE_INITIATED,
                            TransactionState.CAPTURE_FAILED, TransactionState.PARTIALLY_CAPTURED).contains(t.getState())
                            && amount <= t.getAmountPaise() - t.getReleasedPaise();
                    if (!fullCapture && !ourPartialCapture) return amountMismatch(amount, t.getAmountPaise());
                }
                default -> { /* amount not asserted for other events */ }
            }
        }
        if (ev.refundAmountPaise() != null && ev.refundAmountPaise() > t.getCapturedPaise()) {
            return "REFUND_AMOUNT_EXCEEDS_CAPTURED: " + ev.refundAmountPaise() + " > " + t.getCapturedPaise();
        }
        return null;
    }

    private static String amountMismatch(long webhook, long expected) {
        return "AMOUNT_MISMATCH: webhook amount " + webhook + " != expected " + expected;
    }

    private Outcome apply(Transaction t, String gateway, NormalizedWebhookEvent ev, WebhookQueueItem item) {
        TransactionState s = t.getState();
        UUID id = t.getId();
        Audit base = Audit.of(eventName(ev), ACTOR).gateway(ev.gatewayReference(), item.getPayload())
                .meta("event_id", ev.eventId()).meta("event_type", ev.eventType())
                .meta("webhook_queue_id", item.getId());
        return switch (ev.status()) {
            case AUTHORISED -> {
                if (s == TransactionState.AUTH_INITIATED) {
                    int holdDays = gatewayConfigs.require(gateway).getAuthHoldDays();
                    yield applied(id, TransactionState.AUTHORISED, base.mutate(x -> {
                        if (x.getGatewayReference() == null) x.setGatewayReference(ev.gatewayReference());
                        x.setAuthorisedAt(Instant.now());
                        x.setAuthExpiresAt(Instant.now().plus(Math.max(1, holdDays), ChronoUnit.DAYS));
                    }));
                }
                yield PRE_GATEWAY.contains(s) ? deferred("auth_event_before_auth_initiated") : noop("already_past_authorisation");
            }
            case CAPTURED -> captured(t, gateway, ev, base);
            case FAILED, REVERSED -> failed(t, ev, base);
            case EXPIRED -> {
                if (s == TransactionState.AUTH_INITIATED || s == TransactionState.AUTHORISED) {
                    Outcome o = applied(id, TransactionState.AUTH_EXPIRED, base);
                    Transaction now = load(id);
                    notifications.enqueue(now, s == TransactionState.AUTH_INITIATED
                                    ? NotificationService.UPI_COLLECT_EXPIRED : NotificationService.AUTH_HOLD_EXPIRED,
                            "Your payment request expired before it was approved. No money was debited.");
                    yield o;
                }
                yield noop("not_awaiting_authorisation");
            }
            case VOIDED -> {
                if (s == TransactionState.VOID_INITIATED) {
                    yield applied(id, TransactionState.VOIDED, base.mutate(x ->
                            x.setReleasedPaise(x.getAmountPaise() - x.getCapturedPaise())));
                }
                if (s == TransactionState.AUTHORISED) {
                    machine.transition(id, TransactionState.VOID_INITIATED, base);
                    yield applied(id, TransactionState.VOIDED, base.mutate(x ->
                            x.setReleasedPaise(x.getAmountPaise() - x.getCapturedPaise())));
                }
                yield noop("nothing_to_void");
            }
            case REFUNDED -> refunded(t, ev, base);
            case REFUND_FAILED -> {
                if (s == TransactionState.REFUND_INITIATED) {
                    refunds.findFirstByTransactionIdAndStateOrderByCreatedAtDesc(id, Refund.State.INITIATED)
                            .ifPresent(r -> r.markFailed("reported failed by gateway webhook"));
                    yield applied(id, TransactionState.REFUND_FAILED, base);
                }
                yield noop("no_refund_in_flight");
            }
            case DISPUTE_OPENED -> {
                if (s.canTransitionTo(TransactionState.DISPUTE_OPENED)) {
                    yield applied(id, TransactionState.DISPUTE_OPENED, base);
                }
                yield noop("dispute_not_applicable_in_" + s);
            }
            case DISPUTE_RESOLVED -> {
                if (s == TransactionState.DISPUTE_OPENED) yield applied(id, TransactionState.DISPUTE_RESOLVED, base);
                yield CAPTURED_LIKE.contains(s) ? deferred("dispute_resolved_before_opened") : noop("no_open_dispute");
            }
            case SETTLED -> {
                if (s == TransactionState.CAPTURED || s == TransactionState.PARTIALLY_CAPTURED) {
                    yield applied(id, TransactionState.SETTLED, base.mutate(x -> {
                        x.setSettledAt(Instant.now());
                        x.setSettlementBatchId(ev.settlementBatchId());
                    }));
                }
                yield EnumSet.of(TransactionState.AUTH_INITIATED, TransactionState.AUTHORISED,
                        TransactionState.CAPTURE_INITIATED).contains(s)
                        ? deferred("settlement_before_capture") : noop("not_settleable_in_" + s);
            }
            default -> noop("informational_event");
        };
    }

    /** FS-06: a capture confirmation may arrive while the API call is still pending. */
    private Outcome captured(Transaction t, String gateway, NormalizedWebhookEvent ev, Audit base) {
        TransactionState s = t.getState();
        UUID id = t.getId();
        long amount = ev.amountPaise() == null ? t.remainingHoldPaise() + t.getCapturedPaise() : ev.amountPaise();
        switch (s) {
            case AUTH_INITIATED -> {
                Outcome o = applied(id, TransactionState.CAPTURED, base.mutate(x -> {
                    if (x.getGatewayReference() == null) x.setGatewayReference(ev.gatewayReference());
                    x.setAuthorisedAt(Instant.now());
                    x.setCapturedPaise(x.getAmountPaise());
                }));
                metrics.recordCaptured(gateway);
                return o;
            }
            case AUTHORISED -> {
                machine.transition(id, TransactionState.CAPTURE_INITIATED, base);
                return capturedFromInitiated(id, gateway, amount, base);
            }
            case CAPTURE_INITIATED, CAPTURE_FAILED -> {
                return capturedFromInitiated(id, gateway, amount, base);
            }
            case CREATED, ROUTE_SELECTED -> {
                return deferred("capture_event_before_auth_initiated");
            }
            default -> {
                return noop("already_captured_or_beyond");
            }
        }
    }

    private Outcome capturedFromInitiated(UUID id, String gateway, long amount, Audit base) {
        Transaction t = load(id);
        long newCaptured = Math.max(t.getCapturedPaise(), Math.min(amount, t.getAmountPaise() - t.getReleasedPaise()));
        TransactionState target = newCaptured + t.getReleasedPaise() >= t.getAmountPaise()
                ? TransactionState.CAPTURED : TransactionState.PARTIALLY_CAPTURED;
        Outcome o = applied(id, target, base.mutate(x -> x.setCapturedPaise(newCaptured)));
        metrics.recordCaptured(gateway);
        return o;
    }

    private Outcome failed(Transaction t, NormalizedWebhookEvent ev, Audit base) {
        TransactionState s = t.getState();
        UUID id = t.getId();
        if (s == TransactionState.AUTH_INITIATED) {
            boolean asyncFlow = t.getUpiFlow() == Transaction.UpiFlow.COLLECT && t.getAuthExpiresAt() != null;
            long syncBudgetMs = props.orchestration().attemptTimeoutMs() + props.orchestration().failoverWindowMs();
            boolean syncFlowDone = t.getUpdatedAt().isBefore(Instant.now().minusMillis(syncBudgetMs));
            if (!asyncFlow && !syncFlowDone) {
                return deferred("synchronous_authorisation_in_progress");
            }
            machine.transition(id, TransactionState.AUTH_FAILED, base.mutate(x -> {
                x.setFailureCode("PAYMENT_AUTH_FAILED");
                x.setFailureReason("declined: reported by gateway webhook");
            }));
            Outcome o = applied(id, TransactionState.FAILED, Audit.of(AuditEvent.GATEWAY_AUTH_DECLINED, ACTOR)
                    .meta("event_id", ev.eventId()).meta("retry", "not attempted: declined by customer or issuer"));
            notifications.enqueue(load(id), NotificationService.PAYMENT_FAILED,
                    "Your payment was declined. No money was debited.");
            return o;
        }
        if (s == TransactionState.CAPTURE_INITIATED) {
            return applied(id, TransactionState.CAPTURE_FAILED, base);
        }
        if (CAPTURED_LIKE.contains(s)) {
            // C2: a post-capture reversal must never go unnoticed. No automatic refund (FS-11).
            Outcome o = applied(id, TransactionState.RECONCILIATION_MISMATCH,
                    Audit.of(AuditEvent.RECONCILIATION_MISMATCH, ACTOR).meta("event_id", ev.eventId())
                            .meta("gateway_status", ev.status().name()));
            Anomaly a = anomalies.save(new Anomaly("webhook", id, "POST_CAPTURE_REVERSAL", s.name(), ev.status().name(),
                    Anomaly.Severity.CRITICAL, "gateway reported " + ev.status() + " for a " + s
                    + " payment via webhook " + ev.eventId() + "; human review required, no automatic refund"));
            alerts.anomaly(a);
            return o;
        }
        return noop("failure_not_applicable_in_" + s);
    }

    private Outcome refunded(Transaction t, NormalizedWebhookEvent ev, Audit base) {
        TransactionState s = t.getState();
        UUID id = t.getId();
        if (ev.gatewayRefundId() != null && refunds.existsByGatewayRefundId(ev.gatewayRefundId())) {
            return noop("refund_already_recorded");
        }
        if (s == TransactionState.REFUND_INITIATED) {
            if (t.getUpdatedAt().isAfter(Instant.now().minusMillis(props.orchestration().attemptTimeoutMs() * 2))) {
                return deferred("synchronous_refund_in_progress");
            }
            Refund r = refunds.findFirstByTransactionIdAndStateOrderByCreatedAtDesc(id, Refund.State.INITIATED).orElse(null);
            long amount = ev.refundAmountPaise() != null ? ev.refundAmountPaise()
                    : r != null ? r.getAmountPaise() : t.refundablePaise();
            if (r != null) r.markProcessed(ev.gatewayRefundId());
            return finishRefund(id, t, amount, base);
        }
        if (TransactionState.REFUNDABLE.contains(s)) {
            long amount = ev.refundAmountPaise() != null ? Math.min(ev.refundAmountPaise() - t.getRefundedPaise(),
                    t.refundablePaise()) : t.refundablePaise();
            if (amount <= 0) return noop("refund_already_applied");
            Refund r = new Refund(id, amount, t.getGateway());
            r.markProcessed(ev.gatewayRefundId());
            machine.transition(id, TransactionState.REFUND_INITIATED, base.meta("refund_id", r.getId().toString())
                    .meta("initiated_at", "gateway_dashboard"));
            refunds.save(r);
            return finishRefund(id, t, amount, base);
        }
        return noop("refund_not_applicable_in_" + s);
    }

    private Outcome finishRefund(UUID id, Transaction before, long amount, Audit base) {
        long newRefunded = Math.min(before.getCapturedPaise(), before.getRefundedPaise() + amount);
        TransactionState target = newRefunded >= before.getCapturedPaise()
                ? TransactionState.REFUNDED : TransactionState.PARTIALLY_REFUNDED;
        return applied(id, target, base.mutate(x -> x.setRefundedPaise(newRefunded)));
    }

    /**
     * The event comes from a gateway other than the one the payment ended up on:
     * typically the late answer of a gateway we failed over from (FS-01). If it
     * reports money moved, that is an orphan charge: raise an anomaly and void it
     * after commit so the customer is never charged twice.
     */
    private Outcome handleOtherGateway(Transaction t, String gateway, NormalizedWebhookEvent ev) {
        if (ev.status() != GatewayStatus.AUTHORISED && ev.status() != GatewayStatus.CAPTURED) {
            return noop("event_for_abandoned_gateway_attempt");
        }
        Anomaly a = anomalies.save(new Anomaly("webhook", t.getId(), "ORPHAN_GATEWAY_CHARGE", t.getState().name(),
                gateway + ":" + ev.status(), Anomaly.Severity.WARNING, "late " + ev.status() + " from " + gateway
                + " (ref " + ev.gatewayReference() + ") after the payment failed over to " + t.getGateway()
                + "; releasing the orphan authorisation"));
        alerts.anomaly(a);
        if (ev.status() == GatewayStatus.AUTHORISED && ev.gatewayReference() != null
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            UUID id = t.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    releaseOrphan(gateway, ev.gatewayReference(), id);
                }
            });
        }
        return new Outcome(Result.NO_OP, "orphan_charge_on_" + gateway, t.getId(), t.getState());
    }

    private void releaseOrphan(String gateway, String reference, UUID transactionId) {
        try {
            caller.call(gateway, props.orchestration().attemptTimeoutMs(), () -> {
                gateways.get(gateway).voidAuthorisation(
                        new PaymentGateway.VoidRequest(transactionId, reference, 0, TraceContext.traceId()));
                return null;
            });
        } catch (GatewayException e) {
            alerts.raise("ORPHAN_VOID_FAILED", "CRITICAL", "could not void orphan authorisation", Map.of(
                    "gateway", gateway, "reference", reference, "transaction_id", transactionId.toString()));
        }
    }

    private Outcome applied(UUID id, TransactionState target, Audit audit) {
        Optional<Transaction> moved = machine.transitionIfAllowed(id, target, audit);
        return moved.map(t -> new Outcome(Result.APPLIED, "transitioned_to_" + target, id, t.getState()))
                .orElseGet(() -> new Outcome(Result.NO_OP, "transition_not_valid_from_current_state", id,
                        load(id).getState()));
    }

    private Outcome defer(WebhookQueueItem item, String reason, UUID txnId, TransactionState state) {
        item.setRetryCount(item.getRetryCount() + 1);
        item.setErrorMessage(reason);
        if (item.getRetryCount() >= item.getMaxRetries()) {
            queueService.deadLetter(item, "out-of-order event never became applicable: " + reason);
            return new Outcome(Result.REJECTED, reason, txnId, state);
        }
        item.setStatus(WebhookQueueItem.Status.FAILED);
        item.setNextRetryAt(Instant.now().plusMillis(2_000L << (item.getRetryCount() - 1)));
        log.info("webhook deferred {} {} {}", kv("component", "webhook_processor"), kv("reason", reason),
                kv("retry", item.getRetryCount()));
        return new Outcome(Result.DEFERRED, reason, txnId, state);
    }

    private Outcome complete(WebhookQueueItem item, Outcome outcome) {
        item.setStatus(WebhookQueueItem.Status.COMPLETED);
        item.setProcessedAt(Instant.now());
        item.setNextRetryAt(null);
        item.setErrorMessage(outcome.result() == Result.NO_OP ? outcome.reason() : null);
        return outcome;
    }

    private static Outcome noop(String reason) {
        return new Outcome(Result.NO_OP, reason, null, null);
    }

    private static Outcome deferred(String reason) {
        return new Outcome(Result.DEFERRED, reason, null, null);
    }

    private static String eventName(NormalizedWebhookEvent ev) {
        return switch (ev.status()) {
            case AUTHORISED -> AuditEvent.GATEWAY_AUTH_SUCCESS;
            case CAPTURED -> AuditEvent.GATEWAY_CAPTURE_SUCCESS;
            case FAILED -> AuditEvent.GATEWAY_AUTH_DECLINED;
            case REFUNDED -> AuditEvent.GATEWAY_REFUND_SUCCESS;
            case REFUND_FAILED -> AuditEvent.GATEWAY_REFUND_FAILED;
            case SETTLED -> AuditEvent.SETTLEMENT_CONFIRMED;
            case DISPUTE_OPENED -> AuditEvent.DISPUTE_OPENED;
            case DISPUTE_RESOLVED -> AuditEvent.DISPUTE_RESOLVED;
            case EXPIRED -> AuditEvent.AUTH_HOLD_EXPIRED;
            case VOIDED -> AuditEvent.GATEWAY_VOID_SUCCESS;
            default -> AuditEvent.WEBHOOK_RECEIVED;
        };
    }

    private Transaction load(UUID id) {
        return transactions.findById(id).orElseThrow();
    }

    /** Exposed for the ingestion layer's response body. */
    public static Map<String, Object> describe(Outcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("result", o.result().name());
        m.put("reason", o.reason());
        if (o.transactionId() != null) m.put("transaction_id", o.transactionId().toString());
        if (o.state() != null) m.put("state", o.state().name());
        return m;
    }
}
