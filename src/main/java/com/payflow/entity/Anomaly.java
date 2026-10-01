package com.payflow.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Anomaly record (spec A5.5 step 4, FS-11). A captured transaction whose
 * gateway reports a different status (failed/reversed) is a critical anomaly
 * requiring human review — automatic refunds must NOT be triggered.
 */
@Entity
@Table(name = "anomalies", indexes = {
        @Index(name = "idx_anomaly_txn", columnList = "transactionId"),
        @Index(name = "idx_anomaly_run", columnList = "runId")})
public class Anomaly {

    public enum Severity { WARNING, CRITICAL }

    @Id
    @Column(nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "run_id", nullable = false, updatable = false)
    private String runId;

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private String transactionId;

    @Column(name = "internal_state", nullable = false, updatable = false)
    private String internalState;

    @Column(name = "gateway_status", nullable = false, updatable = false)
    private String gatewayStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity = Severity.CRITICAL;

    @Column(columnDefinition = "TEXT", updatable = false)
    private String detail;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private boolean alerted;

    protected Anomaly() {}

    public Anomaly(String runId, String transactionId, String internalState,
                   String gatewayStatus, Severity severity, String detail) {
        this.runId = runId;
        this.transactionId = transactionId;
        this.internalState = internalState;
        this.gatewayStatus = gatewayStatus;
        this.severity = severity;
        this.detail = detail;
    }

    public String getId() { return id; }
    public String getRunId() { return runId; }
    public String getTransactionId() { return transactionId; }
    public String getInternalState() { return internalState; }
    public String getGatewayStatus() { return gatewayStatus; }
    public Severity getSeverity() { return severity; }
    public String getDetail() { return detail; }
    public boolean isAlerted() { return alerted; }
    public void setAlerted(boolean alerted) { this.alerted = alerted; }
    public Instant getCreatedAt() { return createdAt; }
}
