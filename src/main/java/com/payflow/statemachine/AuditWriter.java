package com.payflow.statemachine;

import com.payflow.domain.AuditEvent;
import com.payflow.domain.TransactionState;
import com.payflow.entity.Transaction;
import com.payflow.entity.TransactionStateLog;
import com.payflow.repository.TransactionStateLogRepository;
import com.payflow.tracing.TraceContext;
import com.payflow.util.PiiSanitizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes {@code transaction_state_log} rows (spec A2.3). Gateway responses are
 * PII-sanitised and every row carries the trace context in {@code metadata}.
 */
@Component
public class AuditWriter {

    private final TransactionStateLogRepository logs;

    public AuditWriter(TransactionStateLogRepository logs) {
        this.logs = logs;
    }

    /** Joins the caller's transaction so the audit row commits atomically with the state change. */
    @Transactional(propagation = Propagation.MANDATORY)
    public TransactionStateLog write(Transaction t, TransactionState from, TransactionState to, Audit audit) {
        return logs.save(build(t.getId(), from, to, audit.event(), audit, snapshot(t)));
    }

    /**
     * FS-15: a rejected transition is recorded in its own transaction so the
     * evidence survives the caller's rollback. The row is written while the
     * caller holds {@code FOR NO KEY UPDATE} on the transaction, which does not
     * conflict with the foreign-key check's {@code KEY SHARE} lock.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeRejected(UUID transactionId, TransactionState from, TransactionState attempted, Audit audit) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("attempted_event", audit.event());
        meta.put("attempted_by", audit.createdBy());
        meta.put("rejected_target", attempted.name());
        meta.put("valid_transitions", from.validTargets().stream().map(Enum::name).sorted().toList());
        logs.save(build(transactionId, from, attempted, AuditEvent.REJECTED_TRANSITION, audit, meta));
    }

    private TransactionStateLog build(UUID transactionId, TransactionState from, TransactionState to,
                                      String event, Audit audit, Map<String, Object> extra) {
        Map<String, Object> metadata = TraceContext.auditMetadata();
        if (audit.metadata() != null) metadata.putAll(audit.metadata());
        metadata.putAll(extra);
        Map<String, Object> response = audit.gatewayResponse() == null
                ? null : PiiSanitizer.sanitize(audit.gatewayResponse());
        return new TransactionStateLog(transactionId, from, to, event, audit.gatewayReference(), response,
                metadata, audit.createdBy());
    }

    /** Monetary position after the change: lets the audit trail reconcile "to the rupee". */
    private static Map<String, Object> snapshot(Transaction t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amount_paise", t.getAmountPaise());
        m.put("captured_paise", t.getCapturedPaise());
        m.put("remaining_hold_paise", t.remainingHoldPaise());
        m.put("refunded_paise", t.getRefundedPaise());
        if (t.getGateway() != null) m.put("gateway", t.getGateway());
        return m;
    }
}
