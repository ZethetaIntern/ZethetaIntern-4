package com.payflow.service;

import com.payflow.config.PayFlowProperties;
import com.payflow.core.RoutingEngine;
import com.payflow.domain.AttemptOutcome;
import com.payflow.domain.TransactionState;
import com.payflow.entity.*;
import com.payflow.gateway.GatewayClient;
import com.payflow.gateway.MockControlContext;
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
        /** True when the key is still in flight (409); false when already completed (200 replay). */
        public final boolean inProgress;
        public IdempotencyConflictException(String existingId) {
            this(existingId, true);
        }
        public IdempotencyConflictException(String existingId, boolean inProgress) {
            super("idempotency key already used");
            this.existingId = existingId;
            this.inProgress = inProgress;
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
    private final IdempotencyService idempotency;
    private final GatewayRateLimiter rateLimiter;
    private final com.payflow.repository.GatewayRouteSelectionRepository selections;
    private final com.payflow.gateway.MockControlContext mockControl;

    /** Mock control for the current request; null-safe outside an HTTP request. */
    private com.payflow.gateway.MockControl currentMockControl() {
        try {
            return mockControl.get();
        } catch (org.springframework.beans.factory.support.ScopeNotActiveException e) {
            // No active request (e.g. scheduled jobs, direct service calls in tests).
            return com.payflow.gateway.MockControl.fromHeaders(java.util.Map.of());
        }
    }
    private final PayFlowProperties props;
    private final ExecutorService gatewayPool = Executors.newCachedThreadPool();

    public PaymentService(TransactionRepository transactions, TransactionStateLogRepository stateLogs,
                          GatewayAttemptRepository attempts, IdempotencyKeyRepository idempotencyKeys,
                          RefundRepository refunds, RoutingEngine routing, StateService states,
                          GatewayClient gateways, IdempotencyService idempotency,
                          GatewayRateLimiter rateLimiter,
                          com.payflow.repository.GatewayRouteSelectionRepository selections,
                          MockControlContext mockControl, PayFlowProperties props) {
        this.transactions = transactions;
        this.stateLogs = stateLogs;
        this.attempts = attempts;
        this.idempotencyKeys = idempotencyKeys;
        this.refunds = refunds;
        this.routing = routing;
        this.states = states;
        this.gateways = gateways;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
        this.selections = selections;
        this.mockControl = mockControl;
        this.props = props;
    }

    /**
     * Idempotent creation (spec A8.2, FS-03, FS-09, FS-13).
     *
     * <p>Scope: {@code (merchantId, key)}. A completed key replays the original
     * transaction; a key that is still in flight yields
     * {@link IdempotencyConflictException} (HTTP 409).</p>
     */
    @Transactional
    public Transaction create(String idempotencyKey, String merchantOrderId, long amountPaise,
                              String currency, String paymentMethod) {
        return create(idempotencyKey, merchantOrderId, amountPaise, currency, paymentMethod, "default");
    }

    @Transactional
    public Transaction create(String idempotencyKey, String merchantOrderId, long amountPaise,
                              String currency, String paymentMethod, String merchantId) {
        String requestHash = IdempotencyService.requestHash(merchantOrderId, amountPaise, currency, paymentMethod);

        // A8.2: serialise concurrent requests for the same key at the database level.
        idempotency.acquireAdvisoryLock(merchantId, idempotencyKey);

        // Fast path: a known key never reaches the database insert.
        IdempotencyService.Decision known = idempotency.peek(merchantId, idempotencyKey, requestHash);
        if (known.outcome() == IdempotencyService.Outcome.REPLAY) {
            throw new IdempotencyConflictException(known.transactionId(), false);
        }
        if (known.outcome() == IdempotencyService.Outcome.CONFLICT_IN_PROGRESS) {
            throw new IdempotencyConflictException(known.transactionId(), true);
        }

        Transaction t = new Transaction();
        t.setIdempotencyKey(idempotencyKey);
        t.setMerchantId(merchantId);
        t.setMerchantOrderId(merchantOrderId);
        t.setAmountPaise(amountPaise);
        t.setCurrency(currency);
        t.setPaymentMethod(paymentMethod);
        try {
            t = transactions.saveAndFlush(t);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Lost the insert race (FS-09): another request holds (merchantId, key).
            String winner = idempotency.findExistingId(merchantId, idempotencyKey);
            throw new IdempotencyConflictException(winner == null ? "unknown" : winner, true);
        }

        IdempotencyService.Decision decision =
                idempotency.reserve(merchantId, idempotencyKey, t.getId(), requestHash);
        switch (decision.outcome()) {
            case REPLAY -> {
                // Already completed: return the original transaction as an idempotent replay.
                transactions.delete(t);
                throw new IdempotencyConflictException(decision.transactionId(), false);
            }
            case CONFLICT_IN_PROGRESS -> {
                // Still in flight (FS-03/FS-09) or a different payload on the same key.
                transactions.delete(t);
                throw new IdempotencyConflictException(decision.transactionId(), true);
            }
            default -> { /* reserved: this request owns the key */ }
        }
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
        // A6.1: persist which gateway was selected, with its score and rank.
        for (int i = 0; i < ranked.size(); i++) {
            var p = ranked.get(i);
            selections.save(new com.payflow.entity.GatewayRouteSelection(
                    txnId, p.route().getGateway(), p.score(), i + 1, 0));
        }

        String lastError = null;
        int attemptNo = 0;
        for (var plan : ranked) {
            String gw = plan.route().getGateway();
            attemptNo++;
            // A8.4: respect the gateway's outbound rate limit (delay, never drop).
            rateLimiter.acquire(gw);
            states.applyTransition(txnId, TransactionState.AUTH_INITIATED, "orchestrator",
                    "gateway=" + gw + " attempt=" + attemptNo);
            try {
                long started = System.currentTimeMillis();
                final com.payflow.gateway.MockControl control = currentMockControl();
                Future<GatewayClient.AuthResult> future = gatewayPool.submit(() -> {
                    com.payflow.gateway.MockControl.bind(control);
                    try {
                        return gateways.authorize(gw, t.getAmountPaise(), txnId);
                    } finally {
                        com.payflow.gateway.MockControl.clear();
                    }
                });
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

                Transaction finalTxn = captureFlow(txnId, gw, auth.reference(), attemptNo);
                idempotency.complete(t.getMerchantId(), t.getIdempotencyKey(),
                        com.payflow.entity.IdempotencyKey.Status.COMPLETED);
                return finalTxn;
            } catch (GatewayClient.GatewayException e) {
                boolean timeout = e instanceof GatewayClient.GatewayTimeout;
                routing.recordResult(gw, false, props.attemptTimeoutMillis());
                recordAttempt(txnId, gw, attemptNo,
                        timeout ? AttemptOutcome.TIMEOUT : AttemptOutcome.DECLINED,
                        props.attemptTimeoutMillis(), e.code);
                lastError = e.code + " on " + gw + ": " + e.getMessage();
                onAttemptFailure(txnId, gw, lastError, attemptNo);
            } catch (ExecutionException ee) {
                // A gateway failure thrown inside the worker thread arrives wrapped.
                Throwable cause = ee.getCause();
                if (cause instanceof GatewayClient.GatewayException ge) {
                    boolean timeout = ge instanceof GatewayClient.GatewayTimeout;
                    routing.recordResult(gw, false, props.attemptTimeoutMillis());
                    recordAttempt(txnId, gw, attemptNo,
                            timeout ? AttemptOutcome.TIMEOUT : AttemptOutcome.DECLINED,
                            props.attemptTimeoutMillis(), ge.code);
                    lastError = ge.code + " on " + gw + ": " + ge.getMessage();
                    onAttemptFailure(txnId, gw, lastError, attemptNo);
                } else {
                    routing.recordResult(gw, false, props.attemptTimeoutMillis());
                    recordAttempt(txnId, gw, attemptNo, AttemptOutcome.DECLINED,
                            props.attemptTimeoutMillis(), "INTERNAL_ERROR");
                    lastError = "internal error on " + gw + ": " + cause;
                    onAttemptFailure(txnId, gw, lastError, attemptNo);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                lastError = "interrupted while calling " + gw;
                onAttemptFailure(txnId, gw, lastError, attemptNo);
            }
        }
        states.forceFailTerminal(txnId, lastError == null ? "all gateways exhausted" : lastError);
        idempotency.complete(t.getMerchantId(), t.getIdempotencyKey(),
                com.payflow.entity.IdempotencyKey.Status.FAILED);
        return transactions.findById(txnId).orElseThrow();
    }

    /** Capture phase of the two-phase flow; returns the final transaction. */
    public Transaction captureFlow(String txnId, String gw, String reference, int attemptNo) {
        // The caller may already have advanced the state (e.g. an explicit capture call).
        if (transactions.findById(txnId).map(t -> t.getState()).orElse(TransactionState.AUTHORISED)
                != TransactionState.CAPTURE_INITIATED) {
            states.applyTransition(txnId, TransactionState.CAPTURE_INITIATED, "orchestrator", "gateway=" + gw);
        }
        try {
            long started = System.currentTimeMillis();
            long amount = transactions.findById(txnId).orElseThrow().getAmountPaise();
            final com.payflow.gateway.MockControl control = currentMockControl();
            Future<GatewayClient.CaptureResult> f = gatewayPool.submit(() -> {
                com.payflow.gateway.MockControl.bind(control);
                try {
                    return gateways.capture(gw, reference, amount);
                } finally {
                    com.payflow.gateway.MockControl.clear();
                }
            });
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

    /** Void an uncaptured authorisation, releasing the gateway hold (A7.1 #5). */
    @Transactional
    public Transaction voidAuthorisation(String txnId) {
        Transaction t = states.applyTransition(txnId, TransactionState.VOID_INITIATED, "api",
                "void authorisation hold=" + t(txnId));
        gateways.voidAuthorisation(t.getGateway(), t.getGatewayReference());
        return states.applyTransition(txnId, TransactionState.VOIDED, "api", "authorisation released");
    }

    private String t(String txnId) {
        return transactions.findById(txnId).map(Transaction::getAmountPaise).map(String::valueOf).orElse("0");
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

    public String getByKey(String idempotencyKey) {
        return transactions.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalArgumentException("no transaction for key " + idempotencyKey))
                .getId();
    }

    public List<Transaction> viewAllByOrder(String merchantOrderId) {
        return transactions.findByMerchantOrderId(merchantOrderId);
    }
}
