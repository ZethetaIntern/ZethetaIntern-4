package com.payflow.service;

import com.payflow.config.PayFlowProperties;
import com.payflow.core.RoutingEngine;
import com.payflow.domain.AttemptOutcome;
import com.payflow.domain.TransactionState;
import com.payflow.entity.*;
import com.payflow.gateway.GatewayClient;
import com.payflow.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.concurrent.*;

/**
 * Payment orchestration: idempotent creation, ranked routing, per-attempt
 * 2s failover budget, two-phase (authorise+capture) gateway flow.
 */
@Service
public class PaymentService {
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    public static class IdempotencyConflictException extends RuntimeException {
        public final String existingId;
        public IdempotencyConflictException(String existingId) {
            super("idempotency key already used");
            this.existingId = existingId;
        }
    }

    private final TransactionRepository transactions;
    private final TransactionStateLogRepository stateLogs;
    private final GatewayAttemptRepository attempts;
    private final IdempotencyKeyRepository idempotencyKeys;
    private final RefundRepository refunds;
    private final RoutingEngine routing;
    private final StateService states;
    private final GatewayClient gateways;
    private final PayFlowProperties props;
    private final ExecutorService gatewayPool = Executors.newCachedThreadPool();

    public PaymentService(TransactionRepository transactions, TransactionStateLogRepository stateLogs,
                          GatewayAttemptRepository attempts, IdempotencyKeyRepository idempotencyKeys,
                          RefundRepository refunds, RoutingEngine routing, StateService states,
                          GatewayClient gateways, PayFlowProperties props) {
        this.transactions = transactions;
        this.stateLogs = stateLogs;
        this.attempts = attempts;
        this.idempotencyKeys = idempotencyKeys;
        this.refunds = refunds;
        this.routing = routing;
        this.states = states;
        this.gateways = gateways;
        this.props = props;
    }

    /** Idempotent creation: replays return the original transaction. */
    @Transactional
    public Transaction create(String idempotencyKey, String merchantOrderId, long amountPaise,
                              String currency, String paymentMethod) {
        var existing = transactions.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            throw new IdempotencyConflictException(existing.get().getId());
        }
        Transaction t = new Transaction();
        t.setIdempotencyKey(idempotencyKey);
        t.setMerchantOrderId(merchantOrderId);
        t.setAmountPaise(amountPaise);
        t.setCurrency(currency);
        t.setPaymentMethod(paymentMethod);
        t = transactions.save(t);
        idempotencyKeys.save(new IdempotencyKey(idempotencyKey, t.getId()));
        stateLogs.save(new TransactionStateLog(t.getId(), null, TransactionState.CREATED,
                "api", "amount=" + amountPaise + " " + currency + " method=" + paymentMethod));
        return t;
    }

    /** Full flow: routing -> per-gateway authorise (2s budget, failover) -> capture. */
    public Transaction process(String txnId) {
        Transaction t = transactions.findById(txnId).orElseThrow();
        List<RoutingEngine.RankedGateway> ranked = routing.rank(t.getPaymentMethod(), t.getAmountPaise());
        if (ranked.isEmpty()) {
            states.forceFailTerminal(txnId, "no eligible gateway");
            return transactions.findById(txnId).orElseThrow();
        }
        ranked = ranked.subList(0, Math.min(props.maxGatewayAttempts(), ranked.size()));

        states.applyTransition(txnId, TransactionState.ROUTING, "orchestrator",
                "ranked=" + ranked.stream().map(r -> r.route().getGateway()).toList());

        String lastError = null;
        int attemptNo = 0;
        for (var plan : ranked) {
            String gw = plan.route().getGateway();
            attemptNo++;
            states.applyTransition(txnId, TransactionState.AUTH_INITIATED, "orchestrator",
                    "gateway=" + gw + " attempt=" + attemptNo);
            try {
                long started = System.currentTimeMillis();
                Future<GatewayClient.AuthResult> future = gatewayPool.submit(
                        () -> gateways.authorize(gw, t.getAmountPaise(), txnId));
                GatewayClient.AuthResult auth;
                try {
                    auth = future.get(props.attemptTimeoutMillis(), TimeUnit.MILLISECONDS);
                } catch (TimeoutException te) {
                    future.cancel(true);
                    throw new GatewayClient.GatewayTimeout(gw);
                }
                long latency = System.currentTimeMillis() - started;
                final GatewayClient.AuthResult authResult = auth;
                routing.recordResult(gw, true, latency);
                recordAttempt(txnId, gw, attemptNo, AttemptOutcome.SUCCESS, latency, null);

                final String gwFinal = gw;
                final int attemptFinal = attemptNo;
                states.mutate(txnId, x -> {
                    x.setGateway(gwFinal);
                    x.setGatewayReference(authResult.reference());
                    x.setAttemptsMade(attemptFinal);
                });
                states.applyTransition(txnId, TransactionState.AUTHORISED, "gateway:" + gw,
                        "reference=" + auth.reference() + " latencyMs=" + latency);

                return captureFlow(txnId, gw, auth.reference(), attemptNo);
            } catch (GatewayClient.GatewayException e) {
                boolean timeout = e instanceof GatewayClient.GatewayTimeout;
                routing.recordResult(gw, false, props.attemptTimeoutMillis());
                recordAttempt(txnId, gw, attemptNo,
                        timeout ? AttemptOutcome.TIMEOUT : AttemptOutcome.DECLINED,
                        props.attemptTimeoutMillis(), e.code);
                lastError = e.code + " on " + gw + ": " + e.getMessage();
                onAttemptFailure(txnId, gw, lastError, attemptNo);
            } catch (ExecutionException | InterruptedException e) {
                Thread.currentThread().interrupt();
                lastError = "internal error on " + gw;
                onAttemptFailure(txnId, gw, lastError, attemptNo);
            }
        }
        states.forceFailTerminal(txnId, lastError == null ? "all gateways exhausted" : lastError);
        return transactions.findById(txnId).orElseThrow();
    }

    /** Capture phase of the two-phase flow; returns the final transaction. */
    public Transaction captureFlow(String txnId, String gw, String reference, int attemptNo) {
        states.applyTransition(txnId, TransactionState.CAPTURE_INITIATED, "orchestrator", "gateway=" + gw);
        try {
            long started = System.currentTimeMillis();
            long amount = transactions.findById(txnId).orElseThrow().getAmountPaise();
            Future<GatewayClient.CaptureResult> f = gatewayPool.submit(
                    () -> gateways.capture(gw, reference, amount));
            GatewayClient.CaptureResult cap = f.get(props.attemptTimeoutMillis(), TimeUnit.MILLISECONDS);
            long latency = System.currentTimeMillis() - started;
            final long captured = cap.capturedPaise();
            TransactionState target = captured < amount
                    ? TransactionState.PARTIALLY_CAPTURED : TransactionState.CAPTURED;
            states.mutate(txnId, x -> x.setCapturedPaise(captured));
            states.applyTransition(txnId, target, "gateway:" + gw,
                    "captured=" + captured + " latencyMs=" + latency);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            String code = cause instanceof GatewayClient.GatewayException ge ? ge.code : "CAPTURE_ERROR";
            routing.recordResult(gw, false, props.attemptTimeoutMillis());
            recordAttempt(txnId, gw, attemptNo, AttemptOutcome.DECLINED, props.attemptTimeoutMillis(), code);
            states.applyTransition(txnId, TransactionState.CAPTURE_FAILED, "gateway:" + gw, code);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            states.applyTransition(txnId, TransactionState.CAPTURE_FAILED, "gateway:" + gw,
                    "capture error: " + e.getMessage());
        }
        return transactions.findById(txnId).orElseThrow();
    }

    @Transactional
    public Transaction refund(String txnId, long amountPaise, String actor) {
        Transaction t = states.applyTransition(txnId, TransactionState.REFUND_INITIATED, actor,
                "refund=" + amountPaise);
        refunds.save(new Refund(txnId, amountPaise, t.getGateway(), "INITIATED"));
        boolean ok = gateways.refund(t.getGateway(), t.getGatewayReference(), amountPaise);
        refunds.save(new Refund(txnId, amountPaise, t.getGateway(), ok ? "PROCESSED" : "FAILED"));
        return states.applyTransition(txnId, TransactionState.REFUNDED, actor, "refunded=" + amountPaise);
    }

    private void onAttemptFailure(String txnId, String gw, String error, int attemptNo) {
        try {
            states.mutate(txnId, x -> {
                x.setFailureReason(error);
                x.setAttemptsMade(attemptNo);
            });
            Transaction current = transactions.findById(txnId).orElseThrow();
            if (current.getState() == TransactionState.AUTH_INITIATED
                    && attemptNo < props.maxGatewayAttempts()) {
                states.applyTransition(txnId, TransactionState.AUTH_FAILED, "gateway:" + gw, error);
                states.applyTransition(txnId, TransactionState.RETRYING, "orchestrator",
                        "failover from " + gw);
            }
        } catch (Exception e) {
            log.warn("failure-path transition issue for {}: {}", txnId, e.getMessage());
        }
    }

    private void recordAttempt(String txnId, String gw, int no, AttemptOutcome outcome,
                               long latency, String error) {
        attempts.save(new GatewayAttempt(txnId, gw, no, outcome, latency, error));
        stateLogs.save(new TransactionStateLog(txnId, null, null, "gateway:" + gw,
                "attempt=" + no + " outcome=" + outcome + " latencyMs=" + latency
                        + (error == null ? "" : " error=" + error)));
    }

    public record TransactionView(Transaction txn, List<GatewayAttempt> attempts,
                                  List<TransactionStateLog> audit, List<Refund> refundList) {}

    public TransactionView view(String txnId) {
        Transaction t = transactions.findById(txnId)
                .orElseThrow(() -> new IllegalArgumentException("transaction not found: " + txnId));
        return new TransactionView(t, attempts.findByTransactionIdOrderByAttemptNoAsc(txnId),
                stateLogs.findByTransactionIdOrderByCreatedAtAsc(txnId), refunds.findByTransactionId(txnId));
    }

    public Transaction getTransaction(String txnId) {
        return transactions.findById(txnId)
                .orElseThrow(() -> new IllegalArgumentException("transaction not found: " + txnId));
    }

    public List<Transaction> viewAllByOrder(String merchantOrderId) {
        return transactions.findByMerchantOrderId(merchantOrderId);
    }
}
