package com.payflow.reconciliation;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.payflow.alert.AlertService;
import com.payflow.config.PayFlowProperties;
import com.payflow.domain.AuditEvent;
import com.payflow.domain.TransactionState;
import com.payflow.entity.Anomaly;
import com.payflow.entity.ReconciliationLog;
import com.payflow.entity.ReconciliationRun;
import com.payflow.entity.Refund;
import com.payflow.entity.Transaction;
import com.payflow.gateway.GatewayCaller;
import com.payflow.gateway.GatewayException;
import com.payflow.gateway.GatewayRegistry;
import com.payflow.gateway.GatewayStatus;
import com.payflow.gateway.PaymentGateway;
import com.payflow.notification.NotificationService;
import com.payflow.payment.CaptureService;
import com.payflow.repository.AnomalyRepository;
import com.payflow.repository.ReconciliationLogRepository;
import com.payflow.repository.ReconciliationRunRepository;
import com.payflow.repository.RefundRepository;
import com.payflow.repository.TransactionRepository;
import com.payflow.statemachine.Audit;
import com.payflow.statemachine.TransactionStateMachine;
import com.payflow.tracing.TraceContext;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciliation engine (spec A5.5), run every 15 minutes and on demand.
 *
 * <ol>
 *   <li>Identify stale transactions stuck in AUTH_INITIATED / CAPTURE_INITIATED (and
 *       VOID_INITIATED / REFUND_INITIATED) for longer than the threshold (default 5 min).</li>
 *   <li>Poll each gateway's status API by gateway reference.</li>
 *   <li>Apply the gateway's status as the source of truth with a RECONCILIATION_OVERRIDE audit event.</li>
 *   <li>Match captured payments against the gateway settlement report (C2.4): confirmed ones
 *       become SETTLED with their batch id; payments the gateway reports FAILED/REVERSED become
 *       RECONCILIATION_MISMATCH with a CRITICAL anomaly and an alert. No automatic refund is ever
 *       issued for a settlement discrepancy (FS-11).</li>
 * </ol>
 * Candidate scans run in read-only transactions, which the routing data source sends to the
 * read replica when one is configured (FS-14).
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);
    private static final String ACTOR = "reconciliation_engine";
    private static final Set<TransactionState> STALE_STATES = EnumSet.of(TransactionState.AUTH_INITIATED,
            TransactionState.CAPTURE_INITIATED, TransactionState.VOID_INITIATED, TransactionState.REFUND_INITIATED);
    private static final Set<TransactionState> SETTLEMENT_STATES = EnumSet.of(TransactionState.CAPTURED,
            TransactionState.PARTIALLY_CAPTURED, TransactionState.SETTLED);
    private static final int MAX_STALE_PER_RUN = 50_000;

    private final TransactionRepository transactions;
    private final ReconciliationRunRepository runs;
    private final ReconciliationLogRepository logs;
    private final AnomalyRepository anomalies;
    private final RefundRepository refunds;
    private final TransactionStateMachine machine;
    private final GatewayRegistry gateways;
    private final GatewayCaller caller;
    private final CaptureService captures;
    private final NotificationService notifications;
    private final AlertService alerts;
    private final PayFlowProperties props;
    private final TransactionTemplate readOnly;
    private final TransactionTemplate write;

    public ReconciliationService(TransactionRepository transactions, ReconciliationRunRepository runs,
                                 ReconciliationLogRepository logs, AnomalyRepository anomalies, RefundRepository refunds,
                                 TransactionStateMachine machine, GatewayRegistry gateways, GatewayCaller caller,
                                 CaptureService captures, NotificationService notifications, AlertService alerts,
                                 PayFlowProperties props, PlatformTransactionManager txManager) {
        this.transactions = transactions;
        this.runs = runs;
        this.logs = logs;
        this.anomalies = anomalies;
        this.refunds = refunds;
        this.machine = machine;
        this.gateways = gateways;
        this.caller = caller;
        this.captures = captures;
        this.notifications = notifications;
        this.alerts = alerts;
        this.props = props;
        this.readOnly = new TransactionTemplate(txManager);
        this.readOnly.setReadOnly(true);
        this.write = new TransactionTemplate(txManager);
    }

    private static final class Counters {
        int scanned;
        int stale;
        int overridden;
        int settled;
        int anomalies;
    }

    public ReconciliationRun run(ReconciliationRun.Trigger trigger) {
        return run(trigger, props.reconciliation().staleThreshold());
    }

    public ReconciliationRun run(ReconciliationRun.Trigger trigger, Duration staleThreshold) {
        String runId = "recon_" + DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss").withZone(ZoneOffset.UTC)
                .format(Instant.now()) + "_" + UUID.randomUUID().toString().substring(0, 6);
        ReconciliationRun run = runs.save(new ReconciliationRun(runId, trigger));
        Counters c = new Counters();
        long started = System.nanoTime();
        try {
            reconcileStale(runId, staleThreshold, c);
            reconcileSettlements(runId, c);
            run.finish(ReconciliationRun.Status.COMPLETED, c.scanned, c.stale, c.overridden, c.settled, c.anomalies);
        } catch (RuntimeException e) {
            log.error("reconciliation run failed {}", kv("run_id", runId), e);
            run.finish(ReconciliationRun.Status.FAILED, c.scanned, c.stale, c.overridden, c.settled, c.anomalies);
        }
        runs.save(run);
        log.info("reconciliation run finished {} {} {} {} {} {} {}", kv("component", "reconciliation"),
                kv("run_id", runId), kv("scanned", c.scanned), kv("stale", c.stale), kv("overridden", c.overridden),
                kv("settled", c.settled), kv("anomalies", c.anomalies),
                kv("duration_ms", (System.nanoTime() - started) / 1_000_000));
        return run;
    }

    // ----------------------------------------------------------------- steps 1-3

    private void reconcileStale(String runId, Duration threshold, Counters c) {
        Instant cutoff = Instant.now().minus(threshold);
        List<Transaction> stale = readOnly.execute(s -> transactions.findByStateInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                STALE_STATES, cutoff, PageRequest.of(0, MAX_STALE_PER_RUN)));
        if (stale == null) return;
        for (Transaction t : stale) {
            c.scanned++;
            c.stale++;
            TraceContext.bindTransaction(t.getId(), t.getTraceId());
            PaymentGateway.StatusResponse polled = pollStatus(t);
            GatewayStatus status = polled == null ? GatewayStatus.UNKNOWN : polled.status();
            boolean overridden = status != GatewayStatus.UNKNOWN && applyOverride(runId, t, polled);
            if (overridden) {
                c.overridden++;
                logs.save(new ReconciliationLog(runId, t.getId(), ReconciliationLog.STATUS_OVERRIDE, t.getState().name(),
                        status.name(), "STATE_OVERRIDDEN", "gateway status applied as source of truth"));
            } else {
                logs.save(new ReconciliationLog(runId, t.getId(), ReconciliationLog.STALE_NO_GATEWAY_STATUS,
                        t.getState().name(), status.name(), "NONE",
                        "no actionable gateway status; stale since " + t.getUpdatedAt()));
            }
        }
    }

    /** Step 2: the gateway's status API, by reference (or by our transaction id when no reference came back). */
    private PaymentGateway.StatusResponse pollStatus(Transaction t) {
        if (t.getGateway() == null) return null;
        try {
            PaymentGateway gateway = gateways.get(t.getGateway());
            return caller.call(t.getGateway(), props.orchestration().attemptTimeoutMs(),
                    () -> gateway.fetchStatus(t.getGatewayReference(), t.getId()));
        } catch (GatewayException e) {
            return null;
        }
    }

    /** Step 3: the gateway's status wins; every override is a RECONCILIATION_OVERRIDE audit event. */
    private boolean applyOverride(String runId, Transaction t, PaymentGateway.StatusResponse polled) {
        UUID id = t.getId();
        GatewayStatus status = polled.status();
        Audit audit = Audit.of(AuditEvent.RECONCILIATION_OVERRIDE, ACTOR).meta("run_id", runId)
                .meta("gateway_status", status.name()).meta("previous_state", t.getState().name());
        try {
            return switch (t.getState()) {
                case AUTH_INITIATED -> switch (status) {
                    case AUTHORISED -> {
                        boolean moved = machine.transitionIfAllowed(id, TransactionState.AUTHORISED,
                                audit.mutate(x -> x.setAuthorisedAt(Instant.now()))).isPresent();
                        if (moved && t.getCaptureMode() == Transaction.CaptureMode.AUTOMATIC) {
                            captures.capture(id, null, ACTOR);
                        }
                        yield moved;
                    }
                    case CAPTURED -> machine.transitionIfAllowed(id, TransactionState.CAPTURED,
                            audit.mutate(x -> x.setCapturedPaise(x.getAmountPaise()))).isPresent();
                    case FAILED -> {
                        machine.transition(id, TransactionState.AUTH_FAILED, audit);
                        machine.transition(id, TransactionState.FAILED, audit.mutate(x -> {
                            x.setFailureCode("PAYMENT_AUTH_FAILED");
                            x.setFailureReason("gateway reported the authorisation as failed");
                        }));
                        notifications.enqueue(load(id), NotificationService.PAYMENT_FAILED,
                                "Your payment could not be completed. No money was debited.");
                        yield true;
                    }
                    case EXPIRED -> {
                        machine.transition(id, TransactionState.AUTH_EXPIRED, audit);
                        notifications.enqueue(load(id), NotificationService.UPI_COLLECT_EXPIRED,
                                "Your payment request expired before it was approved. No money was debited.");
                        yield true;
                    }
                    default -> false;
                };
                case CAPTURE_INITIATED -> switch (status) {
                    case CAPTURED -> {
                        long newly = Math.max(0, polled.capturedPaise() - t.getCapturedPaise());
                        captures.applyCapturedForReconciliation(id, newly == 0 ? t.remainingHoldPaise() : newly, runId);
                        yield true;
                    }
                    case AUTHORISED, FAILED -> machine.transitionIfAllowed(id, TransactionState.CAPTURE_FAILED, audit)
                            .isPresent();
                    default -> false;
                };
                case VOID_INITIATED -> status == GatewayStatus.VOIDED
                        && machine.transitionIfAllowed(id, TransactionState.VOIDED,
                        audit.mutate(x -> x.setReleasedPaise(x.getAmountPaise() - x.getCapturedPaise()))).isPresent();
                case REFUND_INITIATED -> switch (status) {
                    case REFUNDED -> {
                        Refund r = refunds.findFirstByTransactionIdAndStateOrderByCreatedAtDesc(id, Refund.State.INITIATED)
                                .orElse(null);
                        long amount = r == null ? t.refundablePaise() : r.getAmountPaise();
                        if (r != null) {
                            r.markProcessed(r.getGatewayRefundId());
                            refunds.save(r);
                        }
                        long newRefunded = t.getRefundedPaise() + amount;
                        TransactionState target = newRefunded >= t.getCapturedPaise()
                                ? TransactionState.REFUNDED : TransactionState.PARTIALLY_REFUNDED;
                        yield machine.transitionIfAllowed(id, target, audit.mutate(x -> x.setRefundedPaise(newRefunded)))
                                .isPresent();
                    }
                    case FAILED, REFUND_FAILED -> machine.transitionIfAllowed(id, TransactionState.REFUND_FAILED, audit)
                            .isPresent();
                    default -> false;
                };
                default -> false;
            };
        } catch (RuntimeException e) {
            log.warn("override skipped {} {}", kv("transaction_id", id), kv("error", e.getMessage()));
            return false;
        }
    }

    // ----------------------------------------------------------------- step 4: settlements

    private void reconcileSettlements(String runId, Counters c) {
        Instant since = Instant.now().minus(props.reconciliation().settlementLookbackDays(), ChronoUnit.DAYS);
        int pageSize = props.reconciliation().batchSize();
        UUID after = new UUID(0L, 0L); // PostgreSQL orders UUIDs as unsigned bytes: all-zero is the minimum
        while (true) {
            UUID afterId = after;
            List<Transaction> page = readOnly.execute(s -> transactions.findSettlementCandidates(SETTLEMENT_STATES,
                    since, afterId, PageRequest.of(0, pageSize)));
            if (page == null || page.isEmpty()) return;
            c.scanned += page.size();
            reconcileSettlementPage(runId, page, c);
            after = page.get(page.size() - 1).getId();
            if (page.size() < pageSize) return;
        }
    }

    private void reconcileSettlementPage(String runId, List<Transaction> page, Counters c) {
        Map<String, List<Transaction>> byGateway = page.stream().filter(t -> t.getGatewayReference() != null)
                .collect(Collectors.groupingBy(Transaction::getGateway));
        for (Map.Entry<String, List<Transaction>> e : byGateway.entrySet()) {
            String gw = e.getKey();
            Map<String, PaymentGateway.SettlementEntry> report;
            try {
                List<String> refs = e.getValue().stream().map(Transaction::getGatewayReference).toList();
                report = caller.call(gw, 10_000, () -> gateways.get(gw).settlementReport(refs));
            } catch (GatewayException ex) {
                log.warn("settlement report unavailable {} {}", kv("gateway", gw), kv("error", ex.getMessage()));
                continue;
            }
            Map<TransactionState, Map<String, List<UUID>>> toSettle = new HashMap<>();
            List<Transaction> mismatches = new ArrayList<>();
            for (Transaction t : e.getValue()) {
                PaymentGateway.SettlementEntry entry = report.get(t.getGatewayReference());
                if (entry == null) continue;
                GatewayStatus s = entry.status();
                if (s == GatewayStatus.SETTLED && t.getState() != TransactionState.SETTLED) {
                    toSettle.computeIfAbsent(t.getState(), k -> new HashMap<>())
                            .computeIfAbsent(entry.settlementBatchId(), k -> new ArrayList<>()).add(t.getId());
                } else if (s == GatewayStatus.FAILED || s == GatewayStatus.REVERSED) {
                    mismatches.add(t);
                }
            }
            toSettle.forEach((from, batches) -> batches.forEach((batch, ids) -> write.executeWithoutResult(st -> {
                List<UUID> moved = machine.bulkTransition(ids, from, TransactionState.SETTLED,
                        Audit.of(AuditEvent.SETTLEMENT_CONFIRMED, ACTOR).meta("run_id", runId).meta("gateway", gw),
                        batch);
                c.settled += moved.size();
                List<ReconciliationLog> rows = moved.stream().map(id -> new ReconciliationLog(runId, id,
                        ReconciliationLog.SETTLEMENT_CONFIRMED, from.name(), "SETTLED", "MARKED_SETTLED",
                        "settlement batch " + batch)).toList();
                logs.saveAll(rows);
            })));
            for (Transaction t : mismatches) {
                flagMismatch(runId, t, report.get(t.getGatewayReference()).status(), c);
            }
        }
    }

    /** FS-11: anomaly + RECONCILIATION_MISMATCH + alert; never an automatic refund. */
    private void flagMismatch(String runId, Transaction t, GatewayStatus gatewayStatus, Counters c) {
        write.executeWithoutResult(s -> {
            boolean moved = machine.transitionIfAllowed(t.getId(), TransactionState.RECONCILIATION_MISMATCH,
                    Audit.of(AuditEvent.RECONCILIATION_MISMATCH, ACTOR).meta("run_id", runId)
                            .meta("gateway_status", gatewayStatus.name()).meta("previous_state", t.getState().name())
                            .meta("automatic_refund", false)).isPresent();
            if (!moved) return;
            Anomaly a = anomalies.save(new Anomaly(runId, t.getId(), "SETTLEMENT_MISMATCH", t.getState().name(),
                    gatewayStatus.name(), Anomaly.Severity.CRITICAL, "internal state " + t.getState()
                    + " but the gateway settlement report shows " + gatewayStatus
                    + "; requires human investigation, no automatic refund triggered"));
            alerts.anomaly(a);
            logs.save(new ReconciliationLog(runId, t.getId(), ReconciliationLog.SETTLEMENT_MISMATCH,
                    t.getState().name(), gatewayStatus.name(), "ANOMALY_RAISED_NO_AUTO_REFUND", a.getDetail()));
            c.anomalies++;
        });
    }

    public List<ReconciliationLog> entries(String runId) {
        return logs.findByRunIdOrderByCreatedAtAsc(runId);
    }

    private Transaction load(UUID id) {
        return transactions.findById(id).orElseThrow();
    }
}
