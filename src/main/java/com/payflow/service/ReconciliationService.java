package com.payflow.service;

import com.payflow.domain.TransactionState;
import com.payflow.entity.ReconciliationLog;
import com.payflow.entity.Transaction;
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

    public ReconciliationService(TransactionRepository transactions,
                                 ReconciliationLogRepository logs) {
        this.transactions = transactions;
        this.logs = logs;
    }

    @Scheduled(fixedDelayString = "${payflow.reconciliationIntervalMs:60000}")
    public void scheduledRun() {
        runOnce();
    }

    public String runOnce() {
        String runId = "recon_" + UUID.randomUUID().toString().substring(0, 8);
        Instant cutoff = Instant.now().minus(10, ChronoUnit.MINUTES);
        List<TransactionState> unknown = List.of(
                TransactionState.ROUTING, TransactionState.AUTH_INITIATED,
                TransactionState.CAPTURE_INITIATED, TransactionState.RETRYING);
        int flagged = 0;
        for (Transaction t : transactions.findByStateIn(unknown)) {
            if (t.getUpdatedAt().isBefore(cutoff)) {
                logs.save(new ReconciliationLog(runId, t.getId(), "STUCK_INTERMEDIATE_STATE",
                        "state=" + t.getState() + " gateway=" + t.getGateway()
                                + " lastUpdate=" + t.getUpdatedAt()));
                flagged++;
            }
        }
        log.info("reconciliation run {} flagged {} transactions", runId, flagged);
        return runId;
    }
}
