package com.payflow.statemachine;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.domain.TransactionState;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Transition table tests (Day 3-4: "minimum 20 valid, 15 invalid"), plus an
 * exhaustive check of every (from, to) pair so the table has 100% coverage.
 */
class TransactionStateTransitionTest {

    @ParameterizedTest(name = "valid: {0} -> {1}")
    @CsvSource({
            // A2.2 mandatory transitions
            "CREATED, ROUTE_SELECTED",
            "CREATED, ABANDONED",
            "ROUTE_SELECTED, AUTH_INITIATED",
            "ROUTE_SELECTED, FAILED",
            "AUTH_INITIATED, AUTHORISED",
            "AUTH_INITIATED, AUTH_FAILED",
            "AUTH_INITIATED, AUTH_EXPIRED",
            "AUTHORISED, CAPTURE_INITIATED",
            "AUTHORISED, VOID_INITIATED",
            "AUTHORISED, AUTH_EXPIRED",
            "AUTH_FAILED, ROUTE_SELECTED",
            "AUTH_FAILED, FAILED",
            "CAPTURE_INITIATED, CAPTURED",
            "CAPTURE_INITIATED, CAPTURE_FAILED",
            "CAPTURE_INITIATED, PARTIALLY_CAPTURED",
            "CAPTURED, REFUND_INITIATED",
            "CAPTURED, SETTLED",
            "PARTIALLY_CAPTURED, CAPTURE_INITIATED",
            "PARTIALLY_CAPTURED, REFUND_INITIATED",
            "PARTIALLY_CAPTURED, SETTLED",
            "CAPTURE_FAILED, CAPTURE_INITIATED",
            "CAPTURE_FAILED, VOID_INITIATED",
            "REFUND_INITIATED, REFUNDED",
            "REFUND_INITIATED, PARTIALLY_REFUNDED",
            "REFUND_INITIATED, REFUND_FAILED",
            // additional states (justified in docs/state-machine.md)
            "AUTH_INITIATED, ROUTE_SELECTED",
            "AUTH_INITIATED, CAPTURED",
            "CAPTURE_FAILED, CAPTURED",
            "VOID_INITIATED, VOIDED",
            "SETTLED, REFUND_INITIATED",
            "SETTLED, RECONCILIATION_MISMATCH",
            "CAPTURED, RECONCILIATION_MISMATCH",
            "CAPTURED, DISPUTE_OPENED",
            "DISPUTE_OPENED, DISPUTE_RESOLVED",
            "PARTIALLY_REFUNDED, REFUND_INITIATED",
            "REFUND_FAILED, REFUND_INITIATED",
            "RECONCILIATION_MISMATCH, SETTLED",
            "CREATED, FAILED"
    })
    void validTransitionsAreAccepted(TransactionState from, TransactionState to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest(name = "invalid: {0} -> {1}")
    @CsvSource({
            "CREATED, REFUNDED",              // FS-15
            "CREATED, CAPTURED",
            "CREATED, AUTHORISED",
            "CREATED, AUTH_INITIATED",        // must be routed first
            "ROUTE_SELECTED, AUTHORISED",
            "AUTHORISED, REFUND_INITIATED",   // A2.1: refunds only after capture
            "AUTHORISED, CAPTURED",           // must go through CAPTURE_INITIATED
            "AUTH_FAILED, AUTHORISED",
            "CAPTURE_INITIATED, REFUND_INITIATED",
            "CAPTURED, AUTH_INITIATED",
            "CAPTURED, VOID_INITIATED",       // captured money is refunded, not voided
            "CAPTURED, CAPTURED",
            "REFUNDED, CAPTURED",
            "REFUNDED, REFUND_INITIATED",
            "FAILED, ROUTE_SELECTED",
            "VOIDED, CAPTURE_INITIATED",
            "AUTH_EXPIRED, CAPTURE_INITIATED",
            "ABANDONED, ROUTE_SELECTED",
            "SETTLED, CAPTURED",
            "DISPUTE_RESOLVED, DISPUTE_OPENED",
            "PARTIALLY_CAPTURED, VOID_INITIATED",
            "REFUND_INITIATED, CAPTURED"
    })
    void invalidTransitionsAreRejected(TransactionState from, TransactionState to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @Test
    void allTwelveMandatoryStatesExist() {
        Set<String> names = Stream.of(TransactionState.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet());
        assertThat(names).contains("CREATED", "ROUTE_SELECTED", "AUTH_INITIATED", "AUTHORISED", "AUTH_FAILED",
                "CAPTURE_INITIATED", "CAPTURED", "PARTIALLY_CAPTURED", "CAPTURE_FAILED", "REFUND_INITIATED",
                "REFUNDED", "FAILED");
        assertThat(TransactionState.values().length).isGreaterThanOrEqualTo(12);
    }

    @Test
    void refundOnlyFromStatesHoldingCapturedMoney() {
        for (TransactionState s : TransactionState.values()) {
            boolean canRefund = s.canTransitionTo(TransactionState.REFUND_INITIATED);
            assertThat(canRefund).as(s.name()).isEqualTo(EnumSet.of(TransactionState.CAPTURED,
                    TransactionState.PARTIALLY_CAPTURED, TransactionState.SETTLED, TransactionState.PARTIALLY_REFUNDED,
                    TransactionState.REFUND_FAILED, TransactionState.RECONCILIATION_MISMATCH).contains(s));
        }
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        assertThat(TransactionState.TERMINAL).contains(TransactionState.REFUNDED, TransactionState.FAILED,
                TransactionState.VOIDED, TransactionState.AUTH_EXPIRED, TransactionState.ABANDONED);
        for (TransactionState t : TransactionState.TERMINAL) {
            assertThat(t.validTargets()).as(t.name()).isEmpty();
            assertThat(t.isTerminal()).isTrue();
        }
        assertThat(TransactionState.CAPTURED.isTerminal()).isFalse();
    }

    @Test
    void everyNonTerminalStateCanEventuallyMove() {
        for (TransactionState s : TransactionState.values()) {
            if (!s.isTerminal()) assertThat(s.validTargets()).as(s.name()).isNotEmpty();
        }
    }

    @Test
    void exceptionMessageListsValidTransitions() {
        UUID id = UUID.randomUUID();
        InvalidStateTransitionException e = new InvalidStateTransitionException(id, TransactionState.CREATED,
                TransactionState.REFUNDED, TransactionState.CREATED.validTargets());
        assertThat(e.getMessage()).contains("CREATED -> REFUNDED").contains(id.toString())
                .contains("[ABANDONED, FAILED, ROUTE_SELECTED]");
        assertThat(e.getValidTransitions()).containsExactlyInAnyOrder(TransactionState.ROUTE_SELECTED,
                TransactionState.ABANDONED, TransactionState.FAILED);
        assertThat(new InvalidStateTransitionException(id, TransactionState.REFUNDED, TransactionState.CAPTURED, Set.of())
                .getMessage()).contains("none (terminal state)");
    }

    @TestFactory
    Stream<DynamicTest> everyDeclaredTransitionIsAccepted() {
        return TransactionState.transitionTable().entrySet().stream().flatMap(e -> e.getValue().stream()
                .map(to -> DynamicTest.dynamicTest(e.getKey() + " -> " + to,
                        () -> assertThat(e.getKey().canTransitionTo(to)).isTrue())));
    }

    @TestFactory
    Stream<DynamicTest> everyUndeclaredTransitionIsRejected() {
        return Stream.of(TransactionState.values()).flatMap(from -> Stream.of(TransactionState.values())
                .filter(to -> !TransactionState.transitionTable().get(from).contains(to))
                .map(to -> DynamicTest.dynamicTest(from + " -X-> " + to,
                        () -> assertThat(from.canTransitionTo(to)).isFalse())));
    }
}
