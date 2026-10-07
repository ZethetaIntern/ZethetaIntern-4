package com.payflow.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Transaction lifecycle states (spec A2.2).
 *
 * <p>The twelve mandatory states are implemented with the exact names from the
 * brief. Additional states are justified in {@code docs/state-machine.md}.
 * The transition table is the single source of truth: {@link
 * com.payflow.statemachine.TransactionStateMachine} rejects anything not listed here.</p>
 */
public enum TransactionState {
    // --- mandatory (A2.2) ---
    CREATED,
    ROUTE_SELECTED,
    AUTH_INITIATED,
    AUTHORISED,
    AUTH_FAILED,
    CAPTURE_INITIATED,
    CAPTURED,
    PARTIALLY_CAPTURED,
    CAPTURE_FAILED,
    REFUND_INITIATED,
    REFUNDED,
    FAILED,
    // --- additional (A2.2 "additional states you should consider") ---
    ABANDONED,
    VOID_INITIATED,
    VOIDED,
    AUTH_EXPIRED,
    SETTLED,
    PARTIALLY_REFUNDED,
    REFUND_FAILED,
    DISPUTE_OPENED,
    DISPUTE_RESOLVED,
    // --- additional (FS-11): settlement discrepancy parked for human review ---
    RECONCILIATION_MISMATCH;

    private static final Map<TransactionState, Set<TransactionState>> TRANSITIONS =
            new EnumMap<>(TransactionState.class);

    static {
        allow(CREATED, ROUTE_SELECTED, ABANDONED, FAILED);
        allow(ROUTE_SELECTED, AUTH_INITIATED, FAILED);
        // ROUTE_SELECTED: failover after a timeout/5xx/429 (FS-01, event AUTH_TIMEOUT)
        // CAPTURED: instant-capture rails (UPI / Stripe auto-capture) confirmed by webhook (FS-06)
        allow(AUTH_INITIATED, AUTHORISED, AUTH_FAILED, AUTH_EXPIRED, ROUTE_SELECTED, CAPTURED);
        allow(AUTHORISED, CAPTURE_INITIATED, VOID_INITIATED, AUTH_EXPIRED);
        allow(AUTH_FAILED, ROUTE_SELECTED, FAILED);
        allow(CAPTURE_INITIATED, CAPTURED, PARTIALLY_CAPTURED, CAPTURE_FAILED);
        allow(CAPTURED, REFUND_INITIATED, SETTLED, DISPUTE_OPENED, RECONCILIATION_MISMATCH);
        allow(PARTIALLY_CAPTURED, CAPTURE_INITIATED, REFUND_INITIATED, SETTLED, DISPUTE_OPENED,
                RECONCILIATION_MISMATCH);
        // CAPTURED: late-success pattern — status poll shows the gateway did capture (FS-04)
        allow(CAPTURE_FAILED, CAPTURE_INITIATED, VOID_INITIATED, CAPTURED);
        allow(VOID_INITIATED, VOIDED);
        allow(SETTLED, REFUND_INITIATED, DISPUTE_OPENED, RECONCILIATION_MISMATCH);
        allow(REFUND_INITIATED, REFUNDED, PARTIALLY_REFUNDED, REFUND_FAILED);
        allow(PARTIALLY_REFUNDED, REFUND_INITIATED, DISPUTE_OPENED);
        allow(REFUND_FAILED, REFUND_INITIATED);
        allow(DISPUTE_OPENED, DISPUTE_RESOLVED);
        // Human review outcome: either the gateway was wrong (settle) or the payment is lost
        allow(RECONCILIATION_MISMATCH, SETTLED, REFUND_INITIATED, FAILED);
        // terminal
        allow(REFUNDED);
        allow(FAILED);
        allow(ABANDONED);
        allow(VOIDED);
        allow(AUTH_EXPIRED);
        allow(DISPUTE_RESOLVED);
    }

    private static void allow(TransactionState from, TransactionState... to) {
        Set<TransactionState> targets = EnumSet.noneOf(TransactionState.class);
        targets.addAll(java.util.List.of(to));
        TRANSITIONS.put(from, java.util.Collections.unmodifiableSet(targets));
    }

    /** States from which no further transition is possible. */
    public static final Set<TransactionState> TERMINAL = java.util.Collections.unmodifiableSet(
            EnumSet.of(REFUNDED, FAILED, ABANDONED, VOIDED, AUTH_EXPIRED, DISPUTE_RESOLVED));

    /** States in which money has moved to the merchant and may be refunded. */
    public static final Set<TransactionState> REFUNDABLE = java.util.Collections.unmodifiableSet(
            EnumSet.of(CAPTURED, PARTIALLY_CAPTURED, SETTLED, PARTIALLY_REFUNDED, REFUND_FAILED,
                    RECONCILIATION_MISMATCH)); // mismatch: only after human review (FS-11)

    public Set<TransactionState> validTargets() {
        return TRANSITIONS.getOrDefault(this, Set.of());
    }

    public boolean canTransitionTo(TransactionState target) {
        return validTargets().contains(target);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** Read-only view of the full transition table (used by tests and docs). */
    public static Map<TransactionState, Set<TransactionState>> transitionTable() {
        return java.util.Collections.unmodifiableMap(TRANSITIONS);
    }
}
