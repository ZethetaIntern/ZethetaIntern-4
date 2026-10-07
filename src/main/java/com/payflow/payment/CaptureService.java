package com.payflow.payment;

import com.payflow.config.PayFlowProperties;
import com.payflow.domain.AttemptOutcome;
import com.payflow.domain.AuditEvent;
import com.payflow.domain.TransactionState;
import com.payflow.entity.GatewayAttempt;
import com.payflow.entity.GatewayConfig;
import com.payflow.entity.Transaction;
import com.payflow.error.ApiException;
import com.payflow.error.ErrorCatalog;
import com.payflow.gateway.GatewayCaller;
import com.payflow.gateway.GatewayException;
import com.payflow.gateway.GatewayRegistry;
import com.payflow.gateway.GatewayStatus;
import com.payflow.gateway.PaymentGateway;
import com.payflow.repository.TransactionRepository;
import com.payflow.routing.GatewayConfigService;
import com.payflow.statemachine.Audit;
import com.payflow.statemachine.TransactionStateMachine;
import com.payflow.tracing.TraceContext;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Second phase of the two-phase flow (A1.2): capture, partial capture, and
 * releasing the authorisation hold (void).
 *
 * <p>FS-04: a 5xx/timeout on capture is retried with exponential backoff
 * (1 s, 2 s, 4 s). After the last retry the state becomes CAPTURE_FAILED and the
 * gateway's status API is polled, because the capture may have succeeded
 * server-side (late success); if so the state moves CAPTURE_FAILED -> CAPTURED.</p>
 */
@Service
public class CaptureService {

    private final TransactionRepository transactions;
    private final TransactionStateMachine machine;
    private final GatewayRegistry gateways;
    private final GatewayCaller caller;
    private final GatewayConfigService gatewayConfigs;
    private final AttemptRecorder recorder;
    private final PayFlowProperties.Orchestration cfg;

    public CaptureService(TransactionRepository transactions, TransactionStateMachine machine,
                          GatewayRegistry gateways, GatewayCaller caller, GatewayConfigService gatewayConfigs,
                          AttemptRecorder recorder, PayFlowProperties props) {
        this.transactions = transactions;
        this.machine = machine;
        this.gateways = gateways;
        this.caller = caller;
        this.gatewayConfigs = gatewayConfigs;
        this.recorder = recorder;
        this.cfg = props.orchestration();
    }

    /**
     * Captures {@code amountPaise} (or the whole remaining hold when null).
     * Capturing less than the hold leaves the transaction PARTIALLY_CAPTURED
     * with the remainder still available (FS-05).
     */
    public PaymentOutcome capture(UUID transactionId, Long amountPaise, String actor) {
        Transaction t = load(transactionId);
        TraceContext.bindTransaction(t.getId(), t.getTraceId());
        GatewayConfig gw = gatewayConfigs.require(requireGateway(t));
        if (!gw.isSupportsAuthCapture()) {
            throw ApiException.unprocessable("CAPTURE_NOT_SUPPORTED",
                    gw.getDisplayName() + " settles instantly; there is no separate capture");
        }
        long remaining = t.remainingHoldPaise();
        long amount = amountPaise == null ? remaining : amountPaise;
        if (t.getState().canTransitionTo(TransactionState.CAPTURE_INITIATED) && (amount <= 0 || amount > remaining)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_CAPTURE_AMOUNT",
                    "capture amount must be between 1 and the remaining hold",
                    Map.of("requested_paise", amount, "remaining_hold_paise", remaining));
        }
        // Validates the state (rejected attempts are audited and raise 409).
        machine.transition(transactionId, TransactionState.CAPTURE_INITIATED,
                Audit.of(AuditEvent.MERCHANT_CAPTURE_REQUESTED, actor)
                        .meta("requested_capture_paise", amount)
                        .meta("remaining_hold_before_paise", remaining));
        return executeCapture(transactionId, amount, actor);
    }

    /** Runs the gateway capture with retries; the transaction is already CAPTURE_INITIATED. */
    PaymentOutcome executeCapture(UUID transactionId, long amount, String actor) {
        Transaction t = load(transactionId);
        PaymentGateway gateway = gateways.get(t.getGateway());
        String idempotencyToken = t.getId() + ":capture:" + t.getCapturedPaise();
        GatewayException last = null;
        int maxRetries = cfg.captureMaxRetries();
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            long started = System.nanoTime();
            try {
                PaymentGateway.CaptureResponse resp = caller.call(t.getGateway(), cfg.attemptTimeoutMs(),
                        () -> gateway.capture(new PaymentGateway.CaptureRequest(t.getId(), t.getGatewayReference(),
                                amount, idempotencyToken, TraceContext.traceId())));
                recorder.success(t, t.getGateway(), GatewayAttempt.Operation.CAPTURE, attempt + 1,
                        AttemptOutcome.SUCCESS, elapsedMs(started));
                return applyCaptured(transactionId, resp.capturedPaise(), resp.reference(), resp.raw(),
                        AuditEvent.GATEWAY_CAPTURE_SUCCESS, actor);
            } catch (GatewayException e) {
                recorder.failure(t, t.getGateway(), GatewayAttempt.Operation.CAPTURE, attempt + 1, e, elapsedMs(started));
                last = e;
                if (!e.isRetryable() || attempt == maxRetries) break;
                long backoff = cfg.captureBackoffMs() * (1L << attempt);
                machine.record(transactionId, Audit.of(AuditEvent.CAPTURE_RETRY, "orchestrator")
                        .meta("retry", attempt + 1).meta("backoff_ms", backoff)
                        .meta("gateway_error_code", e.getGatewayErrorCode()));
                sleep(backoff);
            }
        }
        GatewayException failure = last;
        machine.transitionIfAllowed(transactionId, TransactionState.CAPTURE_FAILED,
                Audit.of(AuditEvent.GATEWAY_CAPTURE_ERROR, "gateway:" + t.getGateway())
                        .gateway(t.getGatewayReference(), Map.of("error_code", failure.getGatewayErrorCode(),
                                "error_description", failure.getGatewayErrorDescription()))
                        .meta("retries", maxRetries));
        Optional<PaymentOutcome> late = pollForLateSuccess(transactionId, t.getGateway(), actor);
        if (late.isPresent()) return late.get();
        return PaymentOutcome.error(load(transactionId), HttpStatus.BAD_GATEWAY, "CAPTURE_FAILED",
                "The gateway could not capture the payment. The authorisation is still held; retry the capture or void it.",
                ErrorCatalog.details(t.getGateway(), failure.getGatewayErrorCode(),
                        failure.getGatewayErrorDescription(), "Retry the capture later or void the authorisation."));
    }

    /** FS-04 late-success pattern: ask the gateway whether the capture actually happened. */
    Optional<PaymentOutcome> pollForLateSuccess(UUID transactionId, String gatewayName, String actor) {
        Transaction t = load(transactionId);
        PaymentGateway gateway = gateways.get(gatewayName);
        try {
            PaymentGateway.StatusResponse status = caller.call(gatewayName, cfg.attemptTimeoutMs(),
                    () -> gateway.fetchStatus(t.getGatewayReference(), t.getId()));
            if (status.status() == GatewayStatus.CAPTURED && status.capturedPaise() > t.getCapturedPaise()) {
                return Optional.of(applyCaptured(transactionId, status.capturedPaise() - t.getCapturedPaise(),
                        status.reference(), status.raw(), AuditEvent.LATE_CAPTURE_SUCCESS, actor));
            }
        } catch (GatewayException e) {
            // status API unavailable too: reconciliation will settle it later
        }
        return Optional.empty();
    }

    /** A5.5 step 3: the gateway reports a capture we never recorded. */
    public PaymentOutcome applyCapturedForReconciliation(UUID transactionId, long capturedNow, String runId) {
        Transaction t = load(transactionId);
        return applyCaptured(transactionId, capturedNow, t.getGatewayReference(),
                Map.of("source", "status_api", "run_id", runId), AuditEvent.RECONCILIATION_OVERRIDE,
                "reconciliation_engine");
    }

    /** Moves to CAPTURED or PARTIALLY_CAPTURED depending on what is left of the hold. */
    PaymentOutcome applyCaptured(UUID transactionId, long capturedNow, String reference, Map<String, Object> raw,
                                 String event, String actor) {
        Transaction before = load(transactionId);
        long newCaptured = Math.min(before.getAmountPaise() - before.getReleasedPaise(),
                before.getCapturedPaise() + capturedNow);
        boolean complete = newCaptured + before.getReleasedPaise() >= before.getAmountPaise();
        TransactionState target = complete ? TransactionState.CAPTURED : TransactionState.PARTIALLY_CAPTURED;
        String auditEvent = complete ? event : AuditEvent.GATEWAY_PARTIAL_CAPTURE;
        Optional<Transaction> moved = machine.transitionIfAllowed(transactionId, target,
                Audit.of(auditEvent, "gateway:" + before.getGateway())
                        .gateway(reference, raw)
                        .meta("captured_now_paise", capturedNow)
                        .meta("captured_by", actor)
                        .mutate(x -> x.setCapturedPaise(newCaptured)));
        moved.ifPresent(x -> recorder.captured(x.getGateway()));
        return PaymentOutcome.ok(load(transactionId), HttpStatus.OK);
    }

    /**
     * Releases the authorisation hold. From AUTHORISED / CAPTURE_FAILED this is a
     * full void (VOID_INITIATED -> VOIDED). From PARTIALLY_CAPTURED only the
     * remaining hold is released and the captured part stays (FS-05).
     */
    public PaymentOutcome voidHold(UUID transactionId, String actor) {
        Transaction t = load(transactionId);
        TraceContext.bindTransaction(t.getId(), t.getTraceId());
        if (t.getState() == TransactionState.PARTIALLY_CAPTURED) {
            long remaining = t.remainingHoldPaise();
            if (remaining <= 0) {
                throw ApiException.unprocessable("NO_REMAINING_HOLD", "the authorisation hold is already fully used");
            }
            callVoid(t, remaining);
            machine.record(transactionId, Audit.of(AuditEvent.REMAINING_HOLD_RELEASED, actor)
                    .meta("released_paise", remaining)
                    .mutate(x -> x.setReleasedPaise(x.getReleasedPaise() + remaining)));
            return PaymentOutcome.ok(load(transactionId), HttpStatus.OK);
        }
        machine.transition(transactionId, TransactionState.VOID_INITIATED, Audit.of(AuditEvent.VOID_REQUESTED, actor)
                .meta("hold_paise", t.remainingHoldPaise()));
        callVoid(t, t.remainingHoldPaise());
        machine.transition(transactionId, TransactionState.VOIDED,
                Audit.of(AuditEvent.GATEWAY_VOID_SUCCESS, "gateway:" + t.getGateway())
                        .mutate(x -> x.setReleasedPaise(x.getAmountPaise() - x.getCapturedPaise())));
        return PaymentOutcome.ok(load(transactionId), HttpStatus.OK);
    }

    private void callVoid(Transaction t, long amount) {
        PaymentGateway gateway = gateways.get(t.getGateway());
        GatewayException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            long started = System.nanoTime();
            try {
                caller.call(t.getGateway(), cfg.attemptTimeoutMs(), () -> {
                    gateway.voidAuthorisation(new PaymentGateway.VoidRequest(t.getId(), t.getGatewayReference(),
                            amount, TraceContext.traceId()));
                    return null;
                });
                recorder.success(t, t.getGateway(), GatewayAttempt.Operation.VOID, attempt, AttemptOutcome.SUCCESS,
                        elapsedMs(started));
                return;
            } catch (GatewayException e) {
                recorder.failure(t, t.getGateway(), GatewayAttempt.Operation.VOID, attempt, e, elapsedMs(started));
                last = e;
                if (!e.isRetryable()) break;
                sleep(100L * attempt);
            }
        }
        throw new ApiException(HttpStatus.BAD_GATEWAY, "VOID_FAILED", "the gateway could not release the hold",
                ErrorCatalog.details(t.getGateway(), last.getGatewayErrorCode(), last.getGatewayErrorDescription(),
                        "The void will be retried by reconciliation; no funds were captured."));
    }

    private Transaction load(UUID id) {
        return transactions.findById(id).orElseThrow(() -> ApiException.notFound("payment " + id));
    }

    private static String requireGateway(Transaction t) {
        if (t.getGateway() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "PAYMENT_NOT_AUTHORISED",
                    "payment has not been authorised by any gateway", Map.of("state", t.getState().name()));
        }
        return t.getGateway();
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
