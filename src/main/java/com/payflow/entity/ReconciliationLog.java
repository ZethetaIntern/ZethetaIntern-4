package com.payflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One finding of a reconciliation run (table {@code reconciliation_log}, spec A6.1). */
@Entity
@Immutable
@Table(name = "reconciliation_log")
public class ReconciliationLog {

    /** Discrepancy types written by the reconciliation engine. */
    public static final String STALE_NO_GATEWAY_STATUS = "STALE_NO_GATEWAY_STATUS";
    public static final String STATUS_OVERRIDE = "STATUS_OVERRIDE";
    public static final String SETTLEMENT_CONFIRMED = "SETTLEMENT_CONFIRMED";
    public static final String SETTLEMENT_MISMATCH = "SETTLEMENT_MISMATCH";

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "discrepancy_type", nullable = false, length = 64)
    private String discrepancyType;

    @Column(name = "internal_state", length = 32)
    private String internalState;

    @Column(name = "gateway_status", length = 32)
    private String gatewayStatus;

    @Column(name = "action_taken", nullable = false, length = 64)
    private String actionTaken;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ReconciliationLog() {}

    public ReconciliationLog(String runId, UUID transactionId, String discrepancyType, String internalState,
                             String gatewayStatus, String actionTaken, String detail) {
        this.runId = runId;
        this.transactionId = transactionId;
        this.discrepancyType = discrepancyType;
        this.internalState = internalState;
        this.gatewayStatus = gatewayStatus;
        this.actionTaken = actionTaken;
        this.detail = detail;
    }

    public UUID getId() { return id; }
    public String getRunId() { return runId; }
    public UUID getTransactionId() { return transactionId; }
    public String getDiscrepancyType() { return discrepancyType; }
    public String getInternalState() { return internalState; }
    public String getGatewayStatus() { return gatewayStatus; }
    public String getActionTaken() { return actionTaken; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
