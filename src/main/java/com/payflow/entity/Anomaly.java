package com.payflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Critical discrepancy requiring human investigation (A5.5 step 4, FS-11).
 * An anomaly never triggers an automatic refund.
 */
@Entity
@Table(name = "anomalies")
public class Anomaly {

    public enum Severity { WARNING, CRITICAL }

    public enum Status { OPEN, ACKNOWLEDGED, RESOLVED }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "run_id", nullable = false, length = 64)
    private String runId;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "anomaly_type", nullable = false, length = 64)
    private String anomalyType;

    @Column(name = "internal_state", nullable = false, length = 32)
    private String internalState;

    @Column(name = "gateway_status", nullable = false, length = 32)
    private String gatewayStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.OPEN;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(nullable = false)
    private boolean alerted;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Anomaly() {}

    public Anomaly(String runId, UUID transactionId, String anomalyType, String internalState,
                   String gatewayStatus, Severity severity, String detail) {
        this.runId = runId;
        this.transactionId = transactionId;
        this.anomalyType = anomalyType;
        this.internalState = internalState;
        this.gatewayStatus = gatewayStatus;
        this.severity = severity;
        this.detail = detail;
    }

    public UUID getId() { return id; }
    public String getRunId() { return runId; }
    public UUID getTransactionId() { return transactionId; }
    public String getAnomalyType() { return anomalyType; }
    public String getInternalState() { return internalState; }
    public String getGatewayStatus() { return gatewayStatus; }
    public Severity getSeverity() { return severity; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public String getDetail() { return detail; }
    public boolean isAlerted() { return alerted; }
    public void setAlerted(boolean alerted) { this.alerted = alerted; }
    public Instant getCreatedAt() { return createdAt; }
}
