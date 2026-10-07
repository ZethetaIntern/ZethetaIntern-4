package com.payflow.statemachine;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.payflow.domain.AuditEvent;
import com.payflow.domain.TransactionState;
import com.payflow.entity.Transaction;
import com.payflow.tracing.TraceContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only component allowed to change {@code transactions.state} (spec A2, Day 3-4).
 *
 * <p>Locking (A8.1): each call takes {@code SELECT ... FOR NO KEY UPDATE} on the
 * transaction row, validates against {@link TransactionState}'s transition
 * table, applies the change plus any field updates, writes the audit row and
 * commits. Callers never hold the lock across a gateway call.</p>
 */
@Component
public class TransactionStateMachine {

    private static final Logger log = LoggerFactory.getLogger(TransactionStateMachine.class);

    @PersistenceContext
    private EntityManager em;

    private final AuditWriter auditWriter;

    public TransactionStateMachine(AuditWriter auditWriter) {
        this.auditWriter = auditWriter;
    }

    /**
     * Applies a transition or throws {@link InvalidStateTransitionException};
     * a rejected attempt is recorded as {@code REJECTED_TRANSITION} (FS-15).
     */
    @Transactional
    public Transaction transition(UUID transactionId, TransactionState target, Audit audit) {
        Transaction t = lock(transactionId);
        TransactionState from = t.getState();
        if (!from.canTransitionTo(target)) {
            auditWriter.writeRejected(transactionId, from, target, audit);
            log.warn("rejected state transition {} {} {} {}", kv("action", "transition_rejected"),
                    kv("from_state", from), kv("to_state", target), kv("event", audit.event()));
            throw new InvalidStateTransitionException(transactionId, from, target, from.validTargets());
        }
        apply(t, from, target, audit);
        return t;
    }

    /**
     * Applies the transition only if it is valid from the current state;
     * otherwise records {@code DUPLICATE_TRANSITION_IGNORED} and returns empty.
     * Used where a concurrent actor may legitimately have moved the state
     * first, e.g. a webhook that beat the synchronous API response (FS-06).
     */
    @Transactional
    public Optional<Transaction> transitionIfAllowed(UUID transactionId, TransactionState target, Audit audit) {
        Transaction t = lock(transactionId);
        TransactionState from = t.getState();
        if (!from.canTransitionTo(target)) {
            auditWriter.write(t, from, from, Audit.of(AuditEvent.DUPLICATE_TRANSITION_IGNORED, audit.createdBy())
                    .gateway(audit.gatewayReference(), audit.gatewayResponse())
                    .meta(audit.metadata() == null ? java.util.Map.of() : audit.metadata())
                    .meta("ignored_event", audit.event())
                    .meta("ignored_target", target.name()));
            log.info("transition ignored, state already advanced {} {} {}", kv("action", "transition_ignored"),
                    kv("current_state", from), kv("ignored_target", target));
            return Optional.empty();
        }
        apply(t, from, target, audit);
        return Optional.of(t);
    }

    /** Records an audited event that does not change state (gateway attempt, released hold, ...). */
    @Transactional
    public Transaction record(UUID transactionId, Audit audit) {
        Transaction t = lock(transactionId);
        if (audit.mutator() != null) audit.mutator().accept(t);
        auditWriter.write(t, t.getState(), t.getState(), audit);
        return t;
    }

    /** Field updates under the row lock without an audit row (e.g. retry bookkeeping). */
    @Transactional
    public Transaction update(UUID transactionId, java.util.function.Consumer<Transaction> mutator) {
        Transaction t = lock(transactionId);
        mutator.accept(t);
        return t;
    }

    /**
     * Set-based transition for batch jobs (settlement reconciliation of thousands
     * of rows). Validated against the transition table, applied with a single
     * {@code UPDATE ... WHERE state = :from RETURNING id} so rows that moved
     * concurrently are skipped, and audited with one batched insert. Returns the
     * ids that actually transitioned.
     */
    @Transactional
    public java.util.List<UUID> bulkTransition(java.util.Collection<UUID> ids, TransactionState from,
                                               TransactionState target, Audit audit, String settlementBatchId) {
        if (!from.canTransitionTo(target)) {
            throw new InvalidStateTransitionException(null, from, target, from.validTargets());
        }
        if (ids.isEmpty()) return java.util.List.of();
        @SuppressWarnings("unchecked")
        java.util.List<Object> rows = em.createNativeQuery(
                        "UPDATE transactions SET state = :to, version = version + 1, updated_at = NOW(), "
                                + "settled_at = CASE WHEN CAST(:to AS varchar) = 'SETTLED' THEN NOW() ELSE settled_at END, "
                                + "settlement_batch_id = COALESCE(CAST(:batch AS varchar), settlement_batch_id) "
                                + "WHERE id IN (:ids) AND state = :from RETURNING id")
                .setParameter("to", target.name())
                .setParameter("from", from.name())
                .setParameter("batch", settlementBatchId)
                .setParameter("ids", ids)
                .getResultList();
        java.util.List<UUID> moved = rows.stream().map(o -> (UUID) o).toList();
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<>(TraceContext.auditMetadata());
        if (audit.metadata() != null) metadata.putAll(audit.metadata());
        if (settlementBatchId != null) metadata.put("settlement_batch_id", settlementBatchId);
        for (UUID id : moved) {
            em.persist(new com.payflow.entity.TransactionStateLog(id, from, target, audit.event(),
                    audit.gatewayReference(), audit.gatewayResponse(), metadata, audit.createdBy()));
        }
        em.flush();
        em.clear();
        return moved;
    }

    private void apply(Transaction t, TransactionState from, TransactionState target, Audit audit) {
        if (audit.mutator() != null) audit.mutator().accept(t);
        t.setState(target);
        em.flush(); // surface constraint violations (e.g. over-capture) before the audit row is written
        auditWriter.write(t, from, target, audit);
        TraceContext.bindTransaction(t.getId(), t.getTraceId());
        log.info("state transition {} {} {} {} {}", kv("component", "state_machine"),
                kv("action", "state_transition"), kv("from_state", from), kv("to_state", target),
                kv("event", audit.event()));
    }

    /**
     * Pessimistic row lock. {@code FOR NO KEY UPDATE} blocks other writers of
     * this transaction but still lets audit rows reference it via foreign key.
     */
    private Transaction lock(UUID transactionId) {
        java.util.List<?> locked = em.createNativeQuery(
                        "SELECT id FROM transactions WHERE id = :id FOR NO KEY UPDATE")
                .setParameter("id", transactionId)
                .getResultList();
        if (locked.isEmpty()) {
            throw new TransactionNotFoundException(transactionId);
        }
        Transaction t = em.find(Transaction.class, transactionId);
        em.refresh(t); // the lock is held now: make sure we validate against committed state
        return t;
    }

    /** Raised when a transition targets a transaction id that does not exist. */
    public static class TransactionNotFoundException extends RuntimeException {
        public TransactionNotFoundException(UUID id) {
            super("transaction not found: " + id);
        }
    }
}
