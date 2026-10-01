package com.payflow.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Idempotency store — duplicate request detection, pruned after 24h. */
@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {
    @Id
    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String key;

    @Column(nullable = false, updatable = false)
    private String transactionId;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt = Instant.now().plusSeconds(24 * 3600);

    protected IdempotencyKey() {}

    public IdempotencyKey(String key, String transactionId) {
        this.key = key;
        this.transactionId = transactionId;
    }

    public String getKey() { return key; }
    public String getTransactionId() { return transactionId; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
}
