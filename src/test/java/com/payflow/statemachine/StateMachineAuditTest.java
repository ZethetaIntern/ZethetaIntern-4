package com.payflow.statemachine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.domain.PaymentMethod;
import com.payflow.domain.TransactionState;
import com.payflow.entity.Transaction;
import com.payflow.repository.TransactionRepository;
import com.payflow.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

/** The state machine against PostgreSQL: audit trail contents, immutability, rejections, bulk moves. */
class StateMachineAuditTest extends IntegrationTest {

    @Autowired TransactionStateMachine machine;
    @Autowired TransactionRepository transactions;

    private UUID newTransaction() {
        Transaction t = new Transaction();
        t.setMerchantId("default");
        t.setIdempotencyKey(UUID.randomUUID().toString());
        t.setMerchantOrderId("ORD-SM");
        t.setAmountPaise(50_000);
        t.setCurrency("INR");
        t.setPaymentMethod(PaymentMethod.CARD);
        t.setTraceId(UUID.randomUUID());
        return transactions.save(t).getId();
    }

    @Test
    void transitionWritesEveryRequiredAuditField() {
        UUID id = newTransaction();
        machine.transition(id, TransactionState.ROUTE_SELECTED, Audit.of("ROUTE_SELECTED", "gateway_router"));
        machine.transition(id, TransactionState.AUTH_INITIATED, Audit.of("GATEWAY_AUTH_REQUESTED", "orchestrator"));
        machine.transition(id, TransactionState.AUTHORISED, Audit.of("GATEWAY_AUTH_SUCCESS", "webhook_processor")
                .gateway("pay_L1a2b3c4d5e6", Map.of("status", "authorized", "card_number", "4111111111111111",
                        "email", "customer@example.com"))
                .meta("ip", "103.21.1.9"));

        Map<String, Object> row = jdbc.queryForMap("SELECT id, transaction_id, from_state, to_state, event, "
                + "gateway_reference, gateway_response::text AS response, metadata::text AS metadata, created_at, "
                + "created_by FROM transaction_state_log WHERE transaction_id = ? AND to_state = 'AUTHORISED'", id);
        assertThat(row.get("id")).isNotNull();
        assertThat(row).containsEntry("from_state", "AUTH_INITIATED").containsEntry("to_state", "AUTHORISED")
                .containsEntry("event", "GATEWAY_AUTH_SUCCESS").containsEntry("gateway_reference", "pay_L1a2b3c4d5e6")
                .containsEntry("created_by", "webhook_processor");
        assertThat(row.get("created_at")).isNotNull();
        // PII is redacted before it reaches the audit trail
        assertThat((String) row.get("response")).contains("authorized").doesNotContain("4111111111111111")
                .doesNotContain("customer@example.com");
        assertThat((String) row.get("metadata")).contains("trace_id").contains("103.21.1.9")
                .contains("amount_paise");
    }

    @Test
    void auditTrailIsImmutableAtTheDatabaseLevel() {
        UUID id = newTransaction();
        machine.transition(id, TransactionState.ROUTE_SELECTED, Audit.of("ROUTE_SELECTED", "test"));
        assertThatThrownBy(() -> jdbc.update("UPDATE transaction_state_log SET event = 'TAMPERED' WHERE transaction_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM transaction_state_log WHERE transaction_id = ?", id))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
    }

    @Test
    void rejectedTransitionIsAuditedAndStateUnchanged() {
        UUID id = newTransaction();
        assertThatThrownBy(() -> machine.transition(id, TransactionState.CAPTURED, Audit.of("X", "buggy")))
                .isInstanceOf(InvalidStateTransitionException.class);
        assertThat(state(id)).isEqualTo("CREATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_state_log WHERE transaction_id = ? "
                + "AND event = 'REJECTED_TRANSITION' AND to_state = 'CAPTURED'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void transitionIfAllowedIgnoresAlreadyAdvancedState() {
        UUID id = newTransaction();
        machine.transition(id, TransactionState.ROUTE_SELECTED, Audit.of("A", "t"));
        assertThat(machine.transitionIfAllowed(id, TransactionState.ROUTE_SELECTED, Audit.of("B", "t"))).isEmpty();
        assertThat(machine.transitionIfAllowed(id, TransactionState.AUTH_INITIATED, Audit.of("C", "t"))).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_state_log WHERE transaction_id = ? "
                + "AND event = 'DUPLICATE_TRANSITION_IGNORED'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void mutatorChangesCommitWithTheTransition() {
        UUID id = newTransaction();
        machine.transition(id, TransactionState.ROUTE_SELECTED, Audit.of("A", "t").mutate(t -> t.setGateway("stripe")));
        assertThat(transactions.findById(id).orElseThrow().getGateway()).isEqualTo("stripe");
        machine.record(id, Audit.of("NOTE", "t").mutate(t -> t.setFailureCode("X")));
        assertThat(transactions.findById(id).orElseThrow().getFailureCode()).isEqualTo("X");
        assertThat(state(id)).isEqualTo("ROUTE_SELECTED");
    }

    @Test
    void overCaptureIsRejectedByDatabaseConstraint() {
        UUID id = newTransaction();
        machine.transition(id, TransactionState.ROUTE_SELECTED, Audit.of("A", "t"));
        machine.transition(id, TransactionState.AUTH_INITIATED, Audit.of("A", "t"));
        machine.transition(id, TransactionState.AUTHORISED, Audit.of("A", "t"));
        machine.transition(id, TransactionState.CAPTURE_INITIATED, Audit.of("A", "t"));
        assertThatThrownBy(() -> machine.transition(id, TransactionState.CAPTURED,
                Audit.of("A", "t").mutate(t -> t.setCapturedPaise(t.getAmountPaise() + 1))))
                .isInstanceOf(RuntimeException.class).hasMessageContaining("ck_txn_hold");
        assertThat(state(id)).isEqualTo("CAPTURE_INITIATED");
    }

    @Test
    void bulkTransitionMovesOnlyRowsStillInTheSourceState() {
        UUID a = newTransaction();
        UUID b = newTransaction();
        for (UUID id : List.of(a, b)) {
            machine.transition(id, TransactionState.ROUTE_SELECTED, Audit.of("A", "t"));
        }
        machine.transition(b, TransactionState.FAILED, Audit.of("A", "t"));
        List<UUID> moved = machine.bulkTransition(List.of(a, b), TransactionState.ROUTE_SELECTED,
                TransactionState.FAILED, Audit.of("BULK", "batch"), null);
        assertThat(moved).containsExactly(a);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transaction_state_log WHERE event = 'BULK'",
                Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> machine.bulkTransition(List.of(a), TransactionState.FAILED,
                TransactionState.CAPTURED, Audit.of("BAD", "batch"), null))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void unknownTransactionIsReported() {
        assertThatThrownBy(() -> machine.transition(UUID.randomUUID(), TransactionState.ROUTE_SELECTED,
                Audit.of("A", "t"))).isInstanceOf(TransactionStateMachine.TransactionNotFoundException.class);
    }

    @Test
    void timelineIsStrictlyOrdered() {
        UUID id = newTransaction();
        machine.transition(id, TransactionState.ROUTE_SELECTED, Audit.of("A", "t"));
        machine.transition(id, TransactionState.AUTH_INITIATED, Audit.of("B", "t"));
        machine.transition(id, TransactionState.AUTHORISED, Audit.of("C", "t"));
        assertThat(jdbc.queryForList("SELECT event FROM transaction_state_log WHERE transaction_id = ? "
                + "ORDER BY created_at", String.class, id)).containsExactly("A", "B", "C");
    }
}
