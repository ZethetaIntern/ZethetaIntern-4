package com.payflow.payment;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.payflow.config.PayFlowProperties;
import com.payflow.domain.AttemptOutcome;
import com.payflow.domain.AuditEvent;
import com.payflow.domain.TransactionState;
import com.payflow.entity.GatewayAttempt;
import com.payflow.entity.GatewayConfig;
import com.payflow.entity.GatewayRouteDecision;
import com.payflow.entity.Transaction;
import com.payflow.error.ErrorCatalog;
import com.payflow.gateway.GatewayCaller;
import com.payflow.gateway.GatewayException;
import com.payflow.gateway.GatewayRegistry;
import com.payflow.gateway.GatewayStatus;
import com.payflow.gateway.PaymentGateway;
import com.payflow.notification.NotificationService;
import com.payflow.ratelimit.GatewayRateLimiter;
import com.payflow.repository.GatewayRouteDecisionRepository;
import com.payflow.repository.TransactionRepository;
import com.payflow.routing.CircuitBreakerService;
import com.payflow.routing.GatewayConfigService;
import com.payflow.routing.GatewayRouter;
import com.payflow.statemachine.Audit;
import com.payflow.statemachine.TransactionStateMachine;
import com.payflow.tracing.RequestTiming;
import com.payflow.tracing.TraceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Authorisation phase with multi-gateway failover (spec A1.1, A3, FS-01, FS-07).
 *
 * <p>For each ranked gateway: rate-limit admission, circuit permission,
 * {@code ROUTE_SELECTED -> AUTH_INITIATED}, then a bounded gateway call. The
 * outcome follows the A1.1 failure matrix:</p>
 * <ul>
 *   <li>unreachable / timeout: fail over immediately ({@code AUTH_INITIATED -> ROUTE_SELECTED})</li>
 *   <li>5xx: one quick retry with backoff, then fail over ({@code AUTH_FAILED -> ROUTE_SELECTED})</li>
 *   <li>429: respect Retry-After and fail over</li>
 *   <li>decline (insufficient funds, fraud): no retry ({@code AUTH_FAILED -> FAILED})</li>
 * </ul>
 * <p>Every gateway call has its own budget and the whole synchronous failover
 * window is bounded, so an alternate gateway answers within 2 seconds of a
 * failure being detected. When every gateway is unavailable or throttled the
 * payment is <em>not</em> failed silently: it is parked in {@code ROUTE_SELECTED}
 * with {@code next_retry_at} and retried asynchronously with exponential
 * backoff (C1.4); after {@code payment-max-retries} it becomes FAILED and the
 * customer is notified.</p>
 */
@Service
public class AuthorisationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AuthorisationOrchestrator.class);
    private static final Set<TransactionState> ROUTABLE =
            EnumSet.of(TransactionState.CREATED, TransactionState.ROUTE_SELECTED, TransactionState.AUTH_FAILED);

    private final TransactionRepository transactions;
    private final TransactionStateMachine machine;
    private final GatewayRouter router;
    private final GatewayRegistry gateways;
    private final GatewayCaller caller;
    private final GatewayRateLimiter rateLimiter;
    private final CircuitBreakerService circuits;
    private final GatewayConfigService gatewayConfigs;
    private final GatewayRouteDecisionRepository routeDecisions;
    private final AttemptRecorder recorder;
    private final CaptureService captures;
    private final NotificationService notifications;
    private final PayFlowProperties props;

    public AuthorisationOrchestrator(TransactionRepository transactions, TransactionStateMachine machine,
                                     GatewayRouter router, GatewayRegistry gateways, GatewayCaller caller,
                                     GatewayRateLimiter rateLimiter, CircuitBreakerService circuits,
                                     GatewayConfigService gatewayConfigs, GatewayRouteDecisionRepository routeDecisions,
                                     AttemptRecorder recorder, CaptureService captures,
                                     NotificationService notifications, PayFlowProperties props) {
        this.transactions = transactions;
        this.machine = machine;
        this.router = router;
        this.gateways = gateways;
        this.caller = caller;
        this.rateLimiter = rateLimiter;
        this.circuits = circuits;
        this.gatewayConfigs = gatewayConfigs;
        this.routeDecisions = routeDecisions;
        this.recorder = recorder;
        this.captures = captures;
        this.notifications = notifications;
        this.props = props;
    }

    /** Tracks why synchronous attempts did not succeed. */
    private static final class Attempts {
        int made;
        boolean capacityOnly = true;
        long retryAfterMs;
        String bestThrottledGateway;
        GatewayException last;
        String pendingFailoverEvent;
        final List<String> tried = new ArrayList<>();

        void throttled(String gateway, long retryAfter) {
            if (bestThrottledGateway == null) bestThrottledGateway = gateway;
            retryAfterMs = retryAfterMs == 0 ? retryAfter : Math.min(retryAfterMs, retryAfter);
        }
    }

    public PaymentOutcome process(UUID transactionId) {
        Transaction t = load(transactionId);
        TraceContext.bindTransaction(t.getId(), t.getTraceId());
        if (!ROUTABLE.contains(t.getState())) {
            return outcomeFor(t);
        }
        PayFlowProperties.Orchestration cfg = props.orchestration();
        long started = System.nanoTime();
        long deadline = started + (cfg.attemptTimeoutMs() + cfg.failoverWindowMs()) * 1_000_000L;

        GatewayRouter.Decision decision = router.decide(t.getPaymentMethod(), t.getCurrency(), t.getAmountPaise());
        if (decision.isEmpty()) {
            return noEligibleGateway(t, decision);
        }

        Attempts attempts = new Attempts();
        for (GatewayRouter.Candidate candidate : decision.ranked()) {
            if (attempts.made >= cfg.maxGatewayAttempts()) break;
            long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
            if (remainingMs <= 0) break;
            String gw = candidate.gateway();

            GatewayRateLimiter.Admission admission = rateLimiter.tryAcquire(gw, Math.min(50, remainingMs));
            if (!admission.admitted()) {
                attempts.throttled(gw, admission.retryAfterMs());
                continue;
            }
            CircuitBreakerService.Permit permit = circuits.acquire(gw, t.getPaymentMethod());
            if (permit == CircuitBreakerService.Permit.REJECTED) {
                attempts.throttled(gw, circuits.config(gw, t.getPaymentMethod()).getOpenTimeoutMs());
                continue;
            }

            attempts.made++;
            int attemptNo = load(transactionId).getAttemptsMade() + 1;
            selectRoute(transactionId, candidate, decision, attemptNo, attempts.pendingFailoverEvent, permit);
            machine.transition(transactionId, TransactionState.AUTH_INITIATED,
                    Audit.of(AuditEvent.GATEWAY_AUTH_REQUESTED, "orchestrator")
                            .meta("gateway", gw).meta("attempt_no", attemptNo)
                            .mutate(x -> {
                                x.setGateway(gw);
                                x.setAttemptsMade(attemptNo);
                                x.setNextRetryAt(null);
                            }));
            attempts.tried.add(gw);

            try {
                PaymentGateway.AuthResponse response = authorise(t, gw, attemptNo, deadline);
                return onGatewayAccepted(transactionId, gw, response);
            } catch (GatewayException e) {
                attempts.last = e;
                if (e.getKind() == AttemptOutcome.DECLINED) {
                    return hardDecline(transactionId, gw, e);
                }
                if (e.getKind() == AttemptOutcome.RATE_LIMITED) {
                    attempts.throttled(gw, e.getRetryAfterMs());
                } else {
                    attempts.capacityOnly = false;
                }
                attempts.pendingFailoverEvent = afterRetryableFailure(transactionId, gw, e);
            }
        }
        return exhausted(transactionId, attempts);
    }

    /** Calls the gateway; a 5xx gets one quick retry on the same gateway if the budget allows. */
    private PaymentGateway.AuthResponse authorise(Transaction t, String gw, int attemptNo, long deadline)
            throws GatewayException {
        PaymentGateway gateway = gateways.get(gw);
        PaymentGateway.AuthRequest request = new PaymentGateway.AuthRequest(t.getId(), t.getAmountPaise(),
                t.getCurrency(), t.getPaymentMethod(), t.getUpiFlow(), t.getId().toString(), TraceContext.traceId());
        long attemptTimeout = props.orchestration().attemptTimeoutMs();
        for (int call = 0; ; call++) {
            long remaining = Math.max(1, (deadline - System.nanoTime()) / 1_000_000);
            long budget = Math.min(attemptTimeout, remaining);
            long started = System.nanoTime();
            RequestTiming.markGatewayCallInitiated();
            try {
                PaymentGateway.AuthResponse response = caller.call(gw, budget, () -> gateway.authorize(request));
                recorder.success(t, gw, GatewayAttempt.Operation.AUTH, attemptNo,
                        response.status() == GatewayStatus.PENDING ? AttemptOutcome.PENDING : AttemptOutcome.SUCCESS,
                        (System.nanoTime() - started) / 1_000_000);
                return response;
            } catch (GatewayException e) {
                recorder.failure(t, gw, GatewayAttempt.Operation.AUTH, attemptNo, e,
                        (System.nanoTime() - started) / 1_000_000);
                long backoff = 100 + ThreadLocalRandom.current().nextLong(50);
                boolean retrySameGateway = call == 0 && e.getKind() == AttemptOutcome.SERVER_ERROR
                        && remaining > backoff + attemptTimeout / 2;
                if (!retrySameGateway) throw e;
                machine.record(t.getId(), Audit.of(AuditEvent.GATEWAY_FAILOVER, "orchestrator")
                        .meta("action", "retry_same_gateway").meta("gateway", gw).meta("backoff_ms", backoff)
                        .meta("gateway_error_code", e.getGatewayErrorCode()));
                sleep(backoff);
            }
        }
    }

    private void selectRoute(UUID transactionId, GatewayRouter.Candidate candidate, GatewayRouter.Decision decision,
                             int attemptNo, String failoverEvent, CircuitBreakerService.Permit permit) {
        List<GatewayRouteDecision> rows = new ArrayList<>();
        for (int i = 0; i < decision.ranked().size(); i++) {
            GatewayRouter.Candidate c = decision.ranked().get(i);
            boolean selected = c.gateway().equals(candidate.gateway());
            Map<String, Object> breakdown = new LinkedHashMap<>(c.breakdown());
            if (selected && !decision.excluded().isEmpty()) {
                breakdown.put("excluded", decision.excluded().stream()
                        .map(x -> Map.of("gateway", x.gateway(), "reason", x.reason())).toList());
            }
            rows.add(new GatewayRouteDecision(transactionId, attemptNo, c.gateway(), i + 1, c.score(), selected,
                    c.health().name(), breakdown, selected ? reason(decision, permit) : null));
        }
        routeDecisions.saveAll(rows);

        Audit audit = Audit.of(failoverEvent == null ? AuditEvent.ROUTE_SELECTED : failoverEvent, "gateway_router")
                .meta("gateway", candidate.gateway())
                .meta("score", Math.round(candidate.score() * 10_000.0) / 10_000.0)
                .meta("attempt_no", attemptNo)
                .meta("circuit_permit", permit.name())
                .meta("routing_note", decision.note())
                .mutate(x -> x.setGateway(candidate.gateway()));
        Transaction current = load(transactionId);
        if (current.getState() == TransactionState.ROUTE_SELECTED) {
            machine.record(transactionId, audit); // re-routing a parked payment: no state change
        } else {
            machine.transition(transactionId, TransactionState.ROUTE_SELECTED, audit);
        }
    }

    private static String reason(GatewayRouter.Decision decision, CircuitBreakerService.Permit permit) {
        String r = permit == CircuitBreakerService.Permit.PROBE ? "half-open probe" : "highest available score";
        return decision.note() == null ? r : r + "; " + decision.note();
    }

    /** The gateway accepted the payment: AUTHORISED, CAPTURED (instant) or PENDING (UPI collect). */
    private PaymentOutcome onGatewayAccepted(UUID transactionId, String gw, PaymentGateway.AuthResponse response) {
        GatewayConfig gwConfig = gatewayConfigs.require(gw);
        switch (response.status()) {
            case PENDING -> {
                Instant expiry = Instant.now().plus(props.upi().collectWindow());
                machine.record(transactionId, Audit.of(AuditEvent.UPI_COLLECT_INITIATED, "gateway:" + gw)
                        .gateway(response.reference(), response.raw())
                        .meta("collect_expires_at", expiry.toString())
                        .mutate(x -> {
                            x.setGatewayReference(response.reference());
                            x.setAuthExpiresAt(expiry);
                        }));
                return PaymentOutcome.ok(load(transactionId), HttpStatus.ACCEPTED);
            }
            case CAPTURED -> {
                machine.transitionIfAllowed(transactionId, TransactionState.CAPTURED,
                        Audit.of(AuditEvent.GATEWAY_CAPTURE_SUCCESS, "gateway:" + gw)
                                .gateway(response.reference(), response.raw())
                                .mutate(x -> {
                                    x.setGatewayReference(response.reference());
                                    x.setAuthorisedAt(Instant.now());
                                    x.setCapturedPaise(x.getAmountPaise());
                                })).ifPresent(x -> recorder.captured(gw));
                return outcomeFor(load(transactionId));
            }
            default -> {
                Instant holdExpiry = Instant.now().plus(Math.max(1, gwConfig.getAuthHoldDays()), ChronoUnit.DAYS);
                machine.transitionIfAllowed(transactionId, TransactionState.AUTHORISED,
                        Audit.of(AuditEvent.GATEWAY_AUTH_SUCCESS, "gateway:" + gw)
                                .gateway(response.reference(), response.raw())
                                .mutate(x -> {
                                    x.setGatewayReference(response.reference());
                                    x.setAuthorisedAt(Instant.now());
                                    x.setAuthExpiresAt(holdExpiry);
                                }));
                Transaction now = load(transactionId);
                // FS-06: a webhook may already have moved the payment on; we only capture from AUTHORISED.
                if (now.getState() == TransactionState.AUTHORISED
                        && now.getCaptureMode() == Transaction.CaptureMode.AUTOMATIC) {
                    PaymentOutcome captured = captures.capture(transactionId, null, "orchestrator");
                    return captured.isError() ? PaymentOutcome.ok(captured.transaction(), HttpStatus.CREATED)
                            : outcomeFor(captured.transaction());
                }
                return outcomeFor(now);
            }
        }
    }

    private PaymentOutcome hardDecline(UUID transactionId, String gw, GatewayException e) {
        ErrorCatalog.Translation tr = ErrorCatalog.forAuthFailure(e.getKind(), e.getGatewayErrorCode());
        Map<String, Object> gatewayError = Map.of("error_code", e.getGatewayErrorCode(),
                "error_description", e.getGatewayErrorDescription());
        machine.transitionIfAllowed(transactionId, TransactionState.AUTH_FAILED,
                Audit.of(AuditEvent.GATEWAY_AUTH_DECLINED, "gateway:" + gw).gateway(null, gatewayError)
                        .mutate(x -> {
                            x.setFailureCode(tr.code());
                            x.setFailureReason(e.getGatewayErrorDescription());
                        }));
        machine.transitionIfAllowed(transactionId, TransactionState.FAILED,
                Audit.of(AuditEvent.GATEWAY_AUTH_DECLINED, "orchestrator").meta("retry", "not attempted: hard decline"));
        Transaction t = load(transactionId);
        notifications.enqueue(t, NotificationService.PAYMENT_FAILED, tr.message());
        return PaymentOutcome.error(t, HttpStatus.PAYMENT_REQUIRED, tr.code(), tr.message(),
                ErrorCatalog.details(gw, e.getGatewayErrorCode(), e.getGatewayErrorDescription(), tr.suggestion()));
    }

    /**
     * Records the failed attempt. Timeouts move straight back to ROUTE_SELECTED on
     * the next selection (FS-01); other retryable errors pass through AUTH_FAILED.
     * Returns the audit event to use for the next ROUTE_SELECTED.
     */
    private String afterRetryableFailure(UUID transactionId, String gw, GatewayException e) {
        machine.update(transactionId, x -> {
            x.setFailureCode(e.getGatewayErrorCode());
            x.setFailureReason(e.getGatewayErrorDescription());
        });
        log.warn("gateway attempt failed {} {} {} {}", kv("component", "orchestrator"), kv("action", "gateway_failed"),
                kv("gateway", gw), kv("failure", e.getKind()));
        if (e.getKind() == AttemptOutcome.TIMEOUT) {
            return AuditEvent.AUTH_TIMEOUT;
        }
        machine.transitionIfAllowed(transactionId, TransactionState.AUTH_FAILED,
                Audit.of(AuditEvent.GATEWAY_FAILOVER, "gateway:" + gw)
                        .gateway(null, Map.of("error_code", e.getGatewayErrorCode(),
                                "error_description", e.getGatewayErrorDescription()))
                        .meta("failure", e.getKind().name()));
        return AuditEvent.GATEWAY_FAILOVER;
    }

    /** No synchronous attempt succeeded: park for an asynchronous retry, or fail after the last retry. */
    private PaymentOutcome exhausted(UUID transactionId, Attempts attempts) {
        Transaction t = load(transactionId);
        PayFlowProperties.Orchestration cfg = props.orchestration();
        if (t.getRetryCount() < cfg.paymentMaxRetries()) {
            return park(transactionId, attempts);
        }
        if (t.getState() == TransactionState.AUTH_INITIATED) {
            machine.transitionIfAllowed(transactionId, TransactionState.AUTH_FAILED,
                    Audit.of(attempts.pendingFailoverEvent == null ? AuditEvent.AUTH_TIMEOUT : attempts.pendingFailoverEvent,
                            "orchestrator"));
        }
        ErrorCatalog.Translation tr = ErrorCatalog.forAuthFailure(
                attempts.last == null ? AttemptOutcome.TIMEOUT : attempts.last.getKind(),
                attempts.last == null ? null : attempts.last.getGatewayErrorCode());
        machine.transitionIfAllowed(transactionId, TransactionState.FAILED,
                Audit.of(AuditEvent.MAX_RETRIES_EXCEEDED, "orchestrator")
                        .meta("retries", t.getRetryCount()).meta("gateways_tried", attempts.tried)
                        .mutate(x -> {
                            x.setFailureCode("MAX_RETRIES_EXCEEDED");
                            x.setFailureReason(tr.message());
                            x.setNextRetryAt(null);
                        }));
        Transaction failed = load(transactionId);
        notifications.enqueue(failed, NotificationService.PAYMENT_FAILED, tr.message());
        return PaymentOutcome.error(failed, HttpStatus.SERVICE_UNAVAILABLE, "MAX_RETRIES_EXCEEDED", tr.message(),
                ErrorCatalog.details(failed.getGateway(),
                        attempts.last == null ? null : attempts.last.getGatewayErrorCode(),
                        attempts.last == null ? null : attempts.last.getGatewayErrorDescription(), tr.suggestion()));
    }

    /** Queues the payment for an asynchronous retry with exponential backoff and jitter (C1.4, FS-07). */
    private PaymentOutcome park(UUID transactionId, Attempts attempts) {
        Transaction t = load(transactionId);
        PayFlowProperties.Orchestration cfg = props.orchestration();
        int retry = t.getRetryCount() + 1;
        long backoff = cfg.paymentRetryBaseMs() * (1L << (retry - 1));
        long delay = Math.max(attempts.retryAfterMs, backoff) + ThreadLocalRandom.current().nextLong(backoff / 4 + 1);
        Instant next = Instant.now().plusMillis(delay);
        String gw = attempts.bestThrottledGateway != null ? attempts.bestThrottledGateway : t.getGateway();
        String reason = attempts.capacityOnly ? "all eligible gateways are throttled or circuit-open"
                : "all eligible gateways failed";
        Audit audit = Audit.of(AuditEvent.PAYMENT_QUEUED_FOR_RETRY, "orchestrator")
                .meta("retry", retry).meta("next_retry_at", next.toString()).meta("delay_ms", delay)
                .meta("reason", reason).meta("gateways_tried", attempts.tried)
                .mutate(x -> {
                    x.setRetryCount(retry);
                    x.setNextRetryAt(next);
                    if (gw != null) x.setGateway(gw);
                });
        if (t.getState() == TransactionState.ROUTE_SELECTED) {
            machine.record(transactionId, audit);
        } else {
            machine.transitionIfAllowed(transactionId, TransactionState.ROUTE_SELECTED, audit);
        }
        Transaction parked = load(transactionId);
        ErrorCatalog.Translation tr = ErrorCatalog.forAuthFailure(attempts.capacityOnly
                ? AttemptOutcome.RATE_LIMITED : AttemptOutcome.TIMEOUT, null);
        return PaymentOutcome.error(parked, HttpStatus.ACCEPTED, "PAYMENT_QUEUED_FOR_RETRY", tr.message(),
                Map.of("next_retry_at", next.toString(), "retry", retry, "suggestion", tr.suggestion()));
    }

    private PaymentOutcome noEligibleGateway(Transaction t, GatewayRouter.Decision decision) {
        // CIRCUIT_OPEN is only assigned after method and currency matched, so such a
        // gateway could serve this payment once its circuit recovers: queue, don't fail.
        boolean recoverable = decision.excluded().stream().anyMatch(x -> x.reason().equals("CIRCUIT_OPEN"));
        if (recoverable && t.getRetryCount() < props.orchestration().paymentMaxRetries()) {
            Attempts a = new Attempts();
            decision.excluded().stream().filter(x -> x.reason().equals("CIRCUIT_OPEN")).findFirst()
                    .ifPresent(x -> a.throttled(x.gateway(), 0));
            return park(t.getId(), a);
        }
        List<Map<String, String>> reasons = decision.excluded().stream()
                .map(x -> Map.of("gateway", x.gateway(), "reason", x.reason())).toList();
        machine.transition(t.getId(), TransactionState.FAILED, Audit.of(AuditEvent.ROUTE_FAILED, "gateway_router")
                .meta("excluded", reasons)
                .mutate(x -> {
                    x.setFailureCode("NO_ELIGIBLE_GATEWAY");
                    x.setFailureReason("no gateway supports " + x.getPaymentMethod() + " in " + x.getCurrency());
                }));
        Transaction failed = load(t.getId());
        return PaymentOutcome.error(failed, HttpStatus.UNPROCESSABLE_ENTITY, "NO_ELIGIBLE_GATEWAY",
                "No configured gateway can process this payment method and currency.",
                Map.of("excluded", reasons));
    }

    /**
     * Claims a parked payment for the retry worker (clears {@code next_retry_at}
     * under the row lock) so two workers never retry the same payment.
     */
    public boolean claimParked(UUID transactionId) {
        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean();
        machine.update(transactionId, x -> {
            if (x.getState() == TransactionState.ROUTE_SELECTED && x.getNextRetryAt() != null
                    && !x.getNextRetryAt().isAfter(Instant.now())) {
                x.setNextRetryAt(null);
                claimed.set(true);
            }
        });
        return claimed.get();
    }

    /** HTTP status for a payment that finished processing without error. */
    static PaymentOutcome outcomeFor(Transaction t) {
        return switch (t.getState()) {
            case FAILED -> PaymentOutcome.error(t, HttpStatus.PAYMENT_REQUIRED,
                    t.getFailureCode() == null ? "PAYMENT_FAILED" : t.getFailureCode(),
                    t.getFailureReason() == null ? "Payment failed." : t.getFailureReason(), Map.of());
            case AUTH_INITIATED, ROUTE_SELECTED, CREATED -> PaymentOutcome.ok(t, HttpStatus.ACCEPTED);
            default -> PaymentOutcome.ok(t, HttpStatus.CREATED);
        };
    }

    private Transaction load(UUID id) {
        return transactions.findById(id).orElseThrow();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
