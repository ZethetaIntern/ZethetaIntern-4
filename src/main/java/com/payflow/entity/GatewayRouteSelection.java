package com.payflow.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A6.1: record of which gateway was selected for a transaction and the score. */
@Entity
@Table(name = "gateway_route_selections", indexes = @Index(name = "idx_route_sel_txn", columnList = "transaction_id"))
public class GatewayRouteSelection {
    @Id
    @Column(nullable = false, updatable = false, length = 36)
    private String id = UUID.randomUUID().toString();

    @Column(name = "transaction_id", nullable = false, updatable = false, length = 36)
    private String transactionId;

    @Column(nullable = false, updatable = false, length = 32)
    private String gateway;

    @Column(nullable = false)
    private double score;

    @Column(nullable = false)
    private int rank;

    @Column(nullable = false)
    private int attemptNo;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected GatewayRouteSelection() {}

    public GatewayRouteSelection(String transactionId, String gateway, double score, int rank, int attemptNo) {
        this.transactionId = transactionId;
        this.gateway = gateway;
        this.score = score;
        this.rank = rank;
        this.attemptNo = attemptNo;
    }

    public String getId() { return id; }
    public String getTransactionId() { return transactionId; }
    public String getGateway() { return gateway; }
    public double getScore() { return score; }
    public int getRank() { return rank; }
    public int getAttemptNo() { return attemptNo; }
    public Instant getCreatedAt() { return createdAt; }
}
