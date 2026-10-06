package com.payflow.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Result rows of reconciliation batch runs. */
@Entity
@Table(name = "reconciliation_log", indexes = {
        @Index(name = "idx_recon_run", columnList = "runId"),
        @Index(name = "idx_recon_txn", columnList = "transactionId")})
public class ReconciliationLog {
    @Id
    @Column(nullable = false, updatable = false, length = 36)
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false, updatable = false, length = 64)
    private String runId;

    @Column(name = "transaction_id", updatable = false, length = 36)
    private String transactionId;

    /** e.g. LATE_SUCCESS, MISSING_WEBHOOK, GATEWAY_STATE_MISMATCH */
    @Column(nullable = false, updatable = false, length = 64)
    private String discrepancyType;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String detail;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ReconciliationLog() {}

    public ReconciliationLog(String runId, String transactionId, String discrepancyType, String detail) {
        this.runId = runId;
        this.transactionId = transactionId;
        this.discrepancyType = discrepancyType;
        this.detail = detail;
    }

    public String getId() { return id; }
    public String getRunId() { return runId; }
    public String getTransactionId() { return transactionId; }
    public String getDiscrepancyType() { return discrepancyType; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
