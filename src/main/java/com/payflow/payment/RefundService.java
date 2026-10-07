package com.payflow.payment;

import com.payflow.config.PayFlowProperties;
import com.payflow.domain.AttemptOutcome;
import com.payflow.domain.AuditEvent;
import com.payflow.domain.TransactionState;
import com.payflow.entity.GatewayAttempt;
import com.payflow.entity.GatewayConfig;
import com.payflow.entity.Refund;
import com.payflow.entity.Transaction;
import com.payflow.error.ApiException;
import com.payflow.error.ErrorCatalog;
import com.payflow.gateway.GatewayCaller;
import com.payflow.gateway.GatewayException;
import com.payflow.gateway.GatewayRegistry;
import com.payflow.gateway.PaymentGateway;
import com.payflow.repository.RefundRepository;
import com.payflow.repository.TransactionRepository;
import com.payflow.routing.GatewayConfigService;
import com.payflow.statemachine.Audit;
import com.payflow.statemachine.TransactionStateMachine;
import com.payflow.tracing.TraceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Full and partial refunds, including refunds on already-settled payments (FS-08:
 * {@code SETTLED -> REFUND_INITIATED -> REFUNDED}). Refunds are validated
 * against what was actually captured, the gateway's partial-refund support
 * (UPI: none, A1.3) and its refund window.
 */
@Service
public class RefundService {

    private final TransactionRepository transactions;
    private final RefundRepository refunds;
    private final TransactionStateMachine machine;
    private final GatewayRegistry gateways;
    private final GatewayCaller caller;
    private final GatewayConfigService gatewayConfigs;
    private final AttemptRecorder recorder;
    private final PayFlowProperties props;

    public RefundService(TransactionRepository transactions, RefundRepository refunds, TransactionStateMachine machine,
                         GatewayRegistry gateways, GatewayCaller caller, GatewayConfigService gatewayConfigs,
                         AttemptRecorder recorder, PayFlowProperties props) {
        this.transactions = transactions;
        this.refunds = refunds;
        this.machine = machine;
        this.gateways = gateways;
        this.caller = caller;
        this.gatewayConfigs = gatewayConfigs;
        this.recorder = recorder;
        this.props = props;
    }

    public PaymentOutcome refund(UUID transactionId, Long amountPaise, String reason, String actor) {
        Transaction t = transactions.findById(transactionId).orElseThrow(() -> ApiException.notFound("payment " + transactionId));
        TraceContext.bindTransaction(t.getId(), t.getTraceId());
        if (!TransactionState.REFUNDABLE.contains(t.getState())) {
            // Let the state machine reject it so the attempt is audited (FS-15) and the caller sees valid transitions.
            machine.transition(transactionId, TransactionState.REFUND_INITIATED,
                    Audit.of(AuditEvent.REFUND_REQUESTED, actor).meta("requested_paise", amountPaise));
        }
        long refundable = t.refundablePaise();
        long amount = amountPaise == null ? refundable : amountPaise;
        if (amount <= 0 || amount > refundable) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_REFUND_AMOUNT",
                    "refund amount must be between 1 and the refundable amount",
                    Map.of("requested_paise", amount, "refundable_paise", refundable));
        }
        GatewayConfig gw = gatewayConfigs.require(t.getGateway());
        boolean partial = amount < refundable || t.getRefundedPaise() > 0;
        if (partial && !gw.isSupportsPartialRefund()) {
            throw ApiException.unprocessable("PARTIAL_REFUND_NOT_SUPPORTED",
                    gw.getDisplayName() + " does not support partial refunds; refund the full amount");
        }
        Instant windowStart = t.getAuthorisedAt() != null ? t.getAuthorisedAt() : t.getCreatedAt();
        Instant windowEnd = windowStart.plus(gw.getRefundWindowDays(), ChronoUnit.DAYS);
        if (Instant.now().isAfter(windowEnd)) {
            throw ApiException.unprocessable("REFUND_WINDOW_EXPIRED",
                    "the " + gw.getRefundWindowDays() + "-day refund window for this payment has closed");
        }

        Refund refund = new Refund(transactionId, amount, t.getGateway());
        // The transition is the concurrency guard: a second concurrent refund finds
        // REFUND_INITIATED and is rejected before any refund row exists for it.
        machine.transition(transactionId, TransactionState.REFUND_INITIATED, Audit.of(AuditEvent.REFUND_REQUESTED, actor)
                .meta("refund_id", refund.getId().toString()).meta("refund_paise", amount)
                .meta("refunded_from_state", t.getState().name()).meta("reason", reason));
        refunds.save(refund);

        PaymentGateway gateway = gateways.get(t.getGateway());
        long started = System.nanoTime();
        try {
            PaymentGateway.RefundResponse resp = caller.call(t.getGateway(), props.orchestration().attemptTimeoutMs(),
                    () -> gateway.refund(new PaymentGateway.RefundRequest(transactionId, t.getGatewayReference(),
                            refund.getId(), amount, TraceContext.traceId())));
            recorder.success(t, t.getGateway(), GatewayAttempt.Operation.REFUND, 1, AttemptOutcome.SUCCESS,
                    (System.nanoTime() - started) / 1_000_000);
            refund.markProcessed(resp.gatewayRefundId());
            refunds.save(refund);
            long newRefunded = t.getRefundedPaise() + amount;
            TransactionState target = newRefunded >= t.getCapturedPaise()
                    ? TransactionState.REFUNDED : TransactionState.PARTIALLY_REFUNDED;
            machine.transition(transactionId, target, Audit.of(AuditEvent.GATEWAY_REFUND_SUCCESS, "gateway:" + t.getGateway())
                    .gateway(resp.gatewayRefundId(), resp.raw())
                    .meta("refund_id", refund.getId().toString())
                    .mutate(x -> x.setRefundedPaise(newRefunded)));
            return PaymentOutcome.ok(load(transactionId), HttpStatus.OK);
        } catch (GatewayException e) {
            recorder.failure(t, t.getGateway(), GatewayAttempt.Operation.REFUND, 1, e,
                    (System.nanoTime() - started) / 1_000_000);
            refund.markFailed(e.getGatewayErrorCode() + ": " + e.getGatewayErrorDescription());
            refunds.save(refund);
            machine.transition(transactionId, TransactionState.REFUND_FAILED,
                    Audit.of(AuditEvent.GATEWAY_REFUND_FAILED, "gateway:" + t.getGateway())
                            .gateway(null, Map.of("error_code", e.getGatewayErrorCode(),
                                    "error_description", e.getGatewayErrorDescription()))
                            .meta("refund_id", refund.getId().toString()));
            return PaymentOutcome.error(load(transactionId), HttpStatus.BAD_GATEWAY, "REFUND_FAILED",
                    "The gateway could not process the refund.",
                    ErrorCatalog.details(t.getGateway(), e.getGatewayErrorCode(), e.getGatewayErrorDescription(),
                            "Retry the refund; no money has left the merchant account."));
        }
    }

    private Transaction load(UUID id) {
        return transactions.findById(id).orElseThrow();
    }
}
