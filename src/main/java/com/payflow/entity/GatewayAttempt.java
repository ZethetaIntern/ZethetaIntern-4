package com.payflow.entity;

import com.payflow.domain.AttemptOutcome;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "gateway_attempts", indexes = @Index(name = "idx_att_txn", columnList = "transactionId"))
public class GatewayAttempt {
    @Id
    @Column(nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "transaction_id", nullable = false)
    private String transactionId;

    @Column(nullable = false)
    private String gateway;

    @Column(nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AttemptOutcome outcome;

    private long latencyMs;
    private String error;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public GatewayAttempt() {}

    public GatewayAttempt(String transactionId, String gateway, int attemptNo,
                          AttemptOutcome outcome, long latencyMs, String error) {
        this.transactionId = transactionId;
        this.gateway = gateway;
        this.attemptNo = attemptNo;
        this.outcome = outcome;
        this.latencyMs = latencyMs;
        this.error = error;
    }

    public String getId() { return id; }
    public String getTransactionId() { return transactionId; }
    public String getGateway() { return gateway; }
    public int getAttemptNo() { return attemptNo; }
    public AttemptOutcome getOutcome() { return outcome; }
    public long getLatencyMs() { return latencyMs; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
}
