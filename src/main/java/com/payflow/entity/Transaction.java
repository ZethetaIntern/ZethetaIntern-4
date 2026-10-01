package com.payflow.entity;

import com.payflow.domain.TransactionState;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "idx_txn_order", columnList = "merchantOrderId"),
        @Index(name = "idx_txn_state", columnList = "state"),
        @Index(name = "idx_txn_gateway_ref", columnList = "gatewayReference")})
public class Transaction {
    @Id
    @Column(nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private String merchantOrderId;

    @Column(nullable = false)
    private long amountPaise;

    @Column(nullable = false)
    private String currency = "INR";

    @Column(nullable = false)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionState state = TransactionState.CREATED;

    @Version
    @Column(nullable = false)
    private long version;

    private String gateway;
    private String gatewayReference;
    private String failureReason;
    private int attemptsMade;
    private long capturedPaise;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { this.updatedAt = Instant.now(); }

    public String getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String k) { this.idempotencyKey = k; }
    public String getMerchantOrderId() { return merchantOrderId; }
    public void setMerchantOrderId(String o) { this.merchantOrderId = o; }
    public long getAmountPaise() { return amountPaise; }
    public void setAmountPaise(long a) { this.amountPaise = a; }
    public String getCurrency() { return currency; }
    public void setCurrency(String c) { this.currency = c; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String m) { this.paymentMethod = m; }
    public TransactionState getState() { return state; }
    public void setState(TransactionState s) { this.state = s; }
    public long getVersion() { return version; }
    public String getGateway() { return gateway; }
    public void setGateway(String g) { this.gateway = g; }
    public String getGatewayReference() { return gatewayReference; }
    public void setGatewayReference(String r) { this.gatewayReference = r; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String f) { this.failureReason = f; }
    public int getAttemptsMade() { return attemptsMade; }
    public void setAttemptsMade(int a) { this.attemptsMade = a; }
    public long getCapturedPaise() { return capturedPaise; }
    public void setCapturedPaise(long c) { this.capturedPaise = c; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
