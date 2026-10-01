package com.payflow.service;

import com.payflow.domain.TransactionState;
import com.payflow.entity.Anomaly;
import com.payflow.entity.ReconciliationLog;
import com.payflow.entity.Transaction;
import com.payflow.repository.AnomalyRepository;
import com.payflow.repository.ReconciliationLogRepository;
import com.payflow.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Reconciliation Engine (spec A5.5 / Part D Day 11-12): batch job that scans
 * for transactions stuck in intermediate states (outcome unknown) and flags
 * discrepancies for manual review.
 */
@Service
public class ReconciliationService {
    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final TransactionRepository transactions;
    private final ReconciliationLogRepository logs;
    private final com.payflow.repository.AnomalyRepository anomalyRepo;
    private final com.payflow.gateway.GatewayClient gateways;
    private final StateService states;
    private final AlertService alerts;

    public ReconciliationService(TransactionRepository transactions,
                                 ReconciliationLogRepository logs,
                                 com.payflow.repository.AnomalyRepository anomalies,
                                 com.payflow.gateway.GatewayClient gateways,
                                 StateService states, AlertService alerts) {
        this.transactions = transactions;
        this.logs = logs;
        this.anomalyRepo = anomalies;
        this.gateways = gateways;
        this.states = states;
        this.alerts = alerts;
    }

    /**
     * A5.5 — Reconciliation Engine.
     * Step 1: find transactions stuck in AUTH_INITIATED / CAPTURE_INITIATED.
     * Step 2: poll the gateway's status API using the gateway reference.
     * Step 3: apply the gateway status as source of truth and write a
     *         RECONCILIATION_OVERRIDE audit event.
     * Step 4: when a CAPTURED transaction is reported failed/reversed, record a
     *         CRITICAL anomaly and raise an alert. Automatic refunds are never
     *         triggered — settlement discrepancies need human review (FS-11).
     */
    @Scheduled(fixedDelayString = "${payflow.reconciliationIntervalMs:900000}")
    public void scheduledRun() {
        runOnce();
    }

    public String runOnce() {
        return runOnce(java.time.Duration.ofMinutes(5));
    }

    public record RunResult(String runId, int stale, int overridden, int anomalies) {}

    /** Runs a batch and returns the detailed outcome. */
    public RunResult runBatch(java.time.Duration stuckThreshold) {
        return run(stuckThreshold, java.util.UUID.randomUUID());
    }

    /** Flags transactions stuck in an intermediate state for longer than the threshold. */
    public String runOnce(java.time.Duration stuckThreshold) {
        return run(stuckThreshold, java.util.UUID.randomUUID()).runId();
    }

    private RunResult run(java.time.Duration stuckThreshold, java.util.UUID runId) {
        String id = "recon_" + runId.toString().substring(0, 8);
        Instant cutoff = Instant.now().minus(stuckThreshold);
        List<TransactionState> unknown = List.of(
                TransactionState.ROUTING, TransactionState.AUTH_INITIATED,
                TransactionState.CAPTURE_INITIATED, TransactionState.RETRYING);

        int stale = 0, overridden = 0, anomalies = 0;
        for (Transaction t : transactions.findByStateIn(unknown)) {
            if (!t.getUpdatedAt().isBefore(cutoff)) continue;
            stale++;

            // Step 2: poll the gateway (only possible when we hold a reference).
            String gatewayStatus = t.getGateway() == null ? "UNKNOWN"
                    : gateways.fetchStatus(t.getGateway(), t.getGatewayReference());

            if ("UNKNOWN".equals(gatewayStatus)) {
                logs.save(new ReconciliationLog(id, t.getId(), "STUCK_INTERMEDIATE_STATE",
                        "state=" + t.getState() + " gateway=" + t.getGateway()
                                + " lastUpdate=" + t.getUpdatedAt()));
                continue;
            }

            // Step 3: gateway is the source of truth -> override + audit event.
            if (applyOverride(t, gatewayStatus, id)) {
                overridden++;
            }

            // Step 4: a settled/captured payment reported as failed is critical.
            if (isCriticalMismatch(t, gatewayStatus)) {
                Anomaly a = anomalyRepo.save(new Anomaly(id, t.getId(),
                        t.getState().name(), gatewayStatus, Anomaly.Severity.CRITICAL,
                        "internal=" + t.getState() + " gateway=" + gatewayStatus
                                + " — manual review required, no automatic refund"));
                alerts.dispatch(a);
                anomalies++;
                logs.save(new ReconciliationLog(id, t.getId(), "RECONCILIATION_MISMATCH",
                        "internal=" + t.getState() + " gateway=" + gatewayStatus));
            }
        }
        log.info("reconciliation run {}: stale={} overridden={} anomalies={}", id, stale, overridden, anomalies);
        return new RunResult(id, stale, overridden, anomalies);
    }

    private boolean applyOverride(Transaction t, String gatewayStatus, String runId) {
        try {
            TransactionState target = switch (gatewayStatus) {
                case "CAPTURED" -> TransactionState.CAPTURED;
                case "AUTHORISED" -> TransactionState.AUTHORISED;
                case "EXPIRED" -> TransactionState.AUTH_EXPIRED;
                case "FAILED" -> TransactionState.AUTH_FAILED;
                default -> null;
            };
            if (target == null || !t.getState().canTransitionTo(target)) return false;
            states.applyTransition(t.getId(), target, "reconciliation:" + runId,
                    "RECONCILIATION_OVERRIDE gatewayStatus=" + gatewayStatus);
            return true;
        } catch (RuntimeException e) {
            log.debug("override skipped for {}: {}", t.getId(), e.getMessage());
            return false;
        }
    }

    private boolean isCriticalMismatch(Transaction t, String gatewayStatus) {
        boolean capturedInternally = t.getState() == TransactionState.CAPTURED
                || t.getState() == TransactionState.SETTLED;
        return capturedInternally && ("FAILED".equals(gatewayStatus) || "REVERSED".equals(gatewayStatus));
    }

    public List<Anomaly> anomalies() { return anomalyRepo.findAll(); }
    public List<ReconciliationLog> entriesForRun(String runId) { return logs.findByRunId(runId); }
}
