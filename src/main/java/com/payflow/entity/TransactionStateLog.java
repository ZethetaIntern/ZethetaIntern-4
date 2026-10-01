package com.payflow.entity;

import com.payflow.domain.TransactionState;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Immutable audit trail of every state transition. Never updated, only inserted. */
@Entity
@Table(name = "transaction_state_log", indexes = {
        @Index(name = "idx_log_txn", columnList = "transactionId"),
        @Index(name = "idx_log_created", columnList = "createdAt")})
public class TransactionStateLog {
    @Id
    @Column(nullable = false, updatable = false)
    private String id = UUID.randomUUID().toString();

    @Column(name = "transaction_id", nullable = false)
    private String transactionId;

    @Enumerated(EnumType.STRING)
    private TransactionState fromState;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionState toState;

    @Column(nullable = false)
    private String actor;

    @Column(columnDefinition = "TEXT")
    private String detail;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public TransactionStateLog() {}

    public TransactionStateLog(String transactionId, TransactionState from,
                               TransactionState to, String actor, String detail) {
        this.transactionId = transactionId;
        this.fromState = from;
        this.toState = to;
        this.actor = actor;
        this.detail = detail;
    }

    public String getId() { return id; }
    public String getTransactionId() { return transactionId; }
    public TransactionState getFromState() { return fromState; }
    public TransactionState getToState() { return toState; }
    public String getActor() { return actor; }
    public String getDetail() { return detail; }
    public Instant getCreatedAt() { return createdAt; }
}
