package com.payflow.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refunds", indexes = @Index(name = "idx_refund_txn", columnList = "transactionId"))
public class Refund {
    @Id
    @Column(nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private String transactionId;

    @Column(nullable = false, updatable = false)
    private long amountPaise;

    @Column(name = "gateway", nullable = true, updatable = false)
    private String gateway;

    @Column(nullable = false, updatable = false)
    private String status;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Refund() {}

    public Refund(String transactionId, long amountPaise, String gateway, String status) {
        this.transactionId = transactionId;
        this.amountPaise = amountPaise;
        this.gateway = gateway;
        this.status = status;
    }

    public String getId() { return id; }
    public String getTransactionId() { return transactionId; }
    public long getAmountPaise() { return amountPaise; }
    public String getGateway() { return gateway; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
