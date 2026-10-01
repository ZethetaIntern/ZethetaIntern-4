package com.payflow.service;

import com.payflow.domain.TransactionState;
import com.payflow.entity.Transaction;
import com.payflow.repository.TransactionRepository;
import com.payflow.repository.TransactionStateLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * State machine enforcement. Locking pattern per spec A4.2: acquire
 * pessimistic lock -> validate + apply transition -> commit (release lock)
 * -> only then call the gateway. No locks are held during gateway I/O.
 */
@Service
public class StateService {

    public static class IllegalTransitionException extends RuntimeException {
        public IllegalTransitionException(String msg) { super(msg); }
    }

    private final TransactionRepository transactions;
    private final TransactionStateLogRepository logs;

    public StateService(TransactionRepository transactions, TransactionStateLogRepository logs) {
        this.transactions = transactions;
        this.logs = logs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction applyTransition(String txnId, TransactionState target, String actor, String detail) {
        Transaction t = transactions.findByIdForUpdate(txnId)
                .orElseThrow(() -> new IllegalArgumentException("transaction not found: " + txnId));
        TransactionState current = t.getState();
        if (!current.canTransitionTo(target)) {
            throw new IllegalTransitionException(
                    "illegal transition " + current + " -> " + target + " for " + txnId);
        }
        t.setState(target);
        logs.save(new com.payflow.entity.TransactionStateLog(txnId, current, target, actor, detail));
        return transactions.save(t);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Transaction mutate(String txnId, java.util.function.Consumer<Transaction> mutator) {
        Transaction t = transactions.findByIdForUpdate(txnId)
                .orElseThrow(() -> new IllegalArgumentException("transaction not found: " + txnId));
        mutator.accept(t);
        return transactions.save(t);
    }

    /** Best-effort terminal transition used on exhaustion paths. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void forceFailTerminal(String txnId, String reason) {
        Transaction t = transactions.findById(txnId).orElseThrow();
        TransactionState cur = t.getState();
        try {
            switch (cur) {
                case CREATED, ROUTING -> {
                    applyTransition(txnId, TransactionState.ROUTING, "orchestrator", reason);
                    applyTransition(txnId, TransactionState.AUTH_INITIATED, "orchestrator", reason);
                    applyTransition(txnId, TransactionState.AUTH_FAILED, "orchestrator", reason);
                    applyTransition(txnId, TransactionState.FAILED_TERMINAL, "orchestrator", reason);
                }
                case AUTH_INITIATED -> {
                    applyTransition(txnId, TransactionState.AUTH_FAILED, "orchestrator", reason);
                    applyTransition(txnId, TransactionState.FAILED_TERMINAL, "orchestrator", reason);
                }
                case RETRYING ->
                        applyTransition(txnId, TransactionState.FAILED_TERMINAL, "orchestrator", reason);
                default -> { /* already terminal or elsewhere; leave as-is */ }
            }
        } catch (IllegalTransitionException ignored) {
            // concurrent transition won; audit trail already reflects reality
        }
    }
}
