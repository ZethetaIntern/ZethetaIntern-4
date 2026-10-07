package com.payflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Refund linked to its parent transaction (table {@code refunds}, spec A6.1). */
@Entity
@Table(name = "refunds")
public class Refund {

    public enum State { INITIATED, PROCESSED, FAILED }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "amount_paise", nullable = false, updatable = false)
    private long amountPaise;

    @Column(nullable = false, length = 32, updatable = false)
    private String gateway;

    @Column(name = "gateway_refund_id")
    private String gatewayRefundId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private State state = State.INITIATED;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Refund() {}

    public Refund(UUID transactionId, long amountPaise, String gateway) {
        this.transactionId = transactionId;
        this.amountPaise = amountPaise;
        this.gateway = gateway;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public void markProcessed(String gatewayRefundId) {
        this.state = State.PROCESSED;
        this.gatewayRefundId = gatewayRefundId;
    }

    public void markFailed(String reason) {
        this.state = State.FAILED;
        this.failureReason = reason;
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public long getAmountPaise() { return amountPaise; }
    public String getGateway() { return gateway; }
    public String getGatewayRefundId() { return gatewayRefundId; }
    public State getState() { return state; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
