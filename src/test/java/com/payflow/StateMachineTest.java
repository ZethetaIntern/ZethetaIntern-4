package com.payflow;

import com.payflow.domain.TransactionState;
import com.payflow.service.StateService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StateMachineTest {

    @Test
    void happyPathTransitions() {
        assertTrue(TransactionState.CREATED.canTransitionTo(TransactionState.ROUTING));
        assertTrue(TransactionState.ROUTING.canTransitionTo(TransactionState.AUTH_INITIATED));
        assertTrue(TransactionState.AUTH_INITIATED.canTransitionTo(TransactionState.AUTHORISED));
        assertTrue(TransactionState.AUTHORISED.canTransitionTo(TransactionState.CAPTURE_INITIATED));
        assertTrue(TransactionState.CAPTURE_INITIATED.canTransitionTo(TransactionState.CAPTURED));
        assertTrue(TransactionState.CAPTURED.canTransitionTo(TransactionState.REFUND_INITIATED));
        assertTrue(TransactionState.REFUND_INITIATED.canTransitionTo(TransactionState.REFUNDED));
    }

    @Test
    void failoverPathTransitions() {
        assertTrue(TransactionState.AUTH_INITIATED.canTransitionTo(TransactionState.AUTH_FAILED));
        assertTrue(TransactionState.AUTH_FAILED.canTransitionTo(TransactionState.RETRYING));
        assertTrue(TransactionState.RETRYING.canTransitionTo(TransactionState.AUTH_INITIATED));
        assertTrue(TransactionState.AUTH_FAILED.canTransitionTo(TransactionState.FAILED_TERMINAL));
        assertTrue(TransactionState.RETRYING.canTransitionTo(TransactionState.FAILED_TERMINAL));
    }

    @Test
    void illegalTransitions() {
        assertFalse(TransactionState.CREATED.canTransitionTo(TransactionState.CAPTURED));
        assertFalse(TransactionState.CAPTURED.canTransitionTo(TransactionState.AUTH_INITIATED));
        assertFalse(TransactionState.FAILED_TERMINAL.canTransitionTo(TransactionState.ROUTING));
        assertFalse(TransactionState.REFUNDED.canTransitionTo(TransactionState.CAPTURED));
    }

    @Test
    void terminalStates() {
        assertTrue(TransactionState.FAILED_TERMINAL.isTerminal());
        assertTrue(TransactionState.REFUNDED.isTerminal());
        assertTrue(TransactionState.TRANSITIONS.get(TransactionState.FAILED_TERMINAL).isEmpty());
        assertTrue(TransactionState.TRANSITIONS.get(TransactionState.REFUNDED).isEmpty());
    }

    @Test
    void partialCaptureStateExists() {
        assertTrue(TransactionState.CAPTURE_INITIATED.canTransitionTo(TransactionState.PARTIALLY_CAPTURED));
        assertTrue(TransactionState.PARTIALLY_CAPTURED.canTransitionTo(TransactionState.CAPTURE_INITIATED));
    }
}
