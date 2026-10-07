package com.payflow.statemachine;

import com.payflow.domain.TransactionState;
import java.util.Set;
import java.util.UUID;

/**
 * Thrown when a caller asks for a transition that is not in the transition
 * table (spec A2.1, FS-15). The message names the valid transitions so the
 * caller gets a clear, actionable error.
 */
public class InvalidStateTransitionException extends RuntimeException {

    private final UUID transactionId;
    private final TransactionState from;
    private final TransactionState to;
    private final Set<TransactionState> validTransitions;

    public InvalidStateTransitionException(UUID transactionId, TransactionState from, TransactionState to,
                                           Set<TransactionState> validTransitions) {
        super("Invalid state transition " + from + " -> " + to + " for transaction " + transactionId
                + ". Valid transitions from " + from + ": "
                + (validTransitions.isEmpty() ? "none (terminal state)"
                        : validTransitions.stream().map(Enum::name).sorted().toList()));
        this.transactionId = transactionId;
        this.from = from;
        this.to = to;
        this.validTransitions = Set.copyOf(validTransitions);
    }

    public UUID getTransactionId() { return transactionId; }
    public TransactionState getFrom() { return from; }
    public TransactionState getTo() { return to; }
    public Set<TransactionState> getValidTransitions() { return validTransitions; }
}
