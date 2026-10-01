package com.payflow.domain;

import java.util.Map;
import java.util.Set;

/** Transaction lifecycle state machine (two-phase: authorise then capture). */
public enum TransactionState {
    CREATED, ROUTING, AUTH_INITIATED, AUTHORISED, CAPTURE_INITIATED,
    CAPTURED, PARTIALLY_CAPTURED, CAPTURE_FAILED, AUTH_FAILED, RETRYING,
    FAILED_TERMINAL, REFUND_INITIATED, REFUNDED;

    public static final Map<TransactionState, Set<TransactionState>> TRANSITIONS = Map.ofEntries(
            Map.entry(CREATED, Set.of(ROUTING)),
            Map.entry(ROUTING, Set.of(AUTH_INITIATED, FAILED_TERMINAL)),
            Map.entry(AUTH_INITIATED, Set.of(AUTHORISED, AUTH_FAILED)),
            Map.entry(AUTHORISED, Set.of(CAPTURE_INITIATED, REFUND_INITIATED)),
            Map.entry(CAPTURE_INITIATED, Set.of(CAPTURED, PARTIALLY_CAPTURED, CAPTURE_FAILED)),
            Map.entry(CAPTURE_FAILED, Set.of(CAPTURE_INITIATED, FAILED_TERMINAL)),
            Map.entry(PARTIALLY_CAPTURED, Set.of(CAPTURE_INITIATED)),
            Map.entry(AUTH_FAILED, Set.of(RETRYING, FAILED_TERMINAL)),
            Map.entry(RETRYING, Set.of(AUTH_INITIATED, FAILED_TERMINAL)),
            Map.entry(CAPTURED, Set.of(REFUND_INITIATED)),
            Map.entry(REFUND_INITIATED, Set.of(REFUNDED)),
            Map.entry(FAILED_TERMINAL, Set.of()),
            Map.entry(REFUNDED, Set.of()));

    /** Late-success reconciliation path (webhook after timeout). */
    public boolean isReconcilableFromUnknown() {
        return this == AUTH_INITIATED;
    }

    public static final Set<TransactionState> TERMINAL = Set.of(FAILED_TERMINAL, REFUNDED);

    public boolean canTransitionTo(TransactionState target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
