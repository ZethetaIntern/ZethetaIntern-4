package com.payflow.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Idempotency store (spec A6.1, A8.2, FS-13).
 *
 * <p>Keyed by {@code (merchantId, key)} so two merchants that generate the
 * same UUID are treated as distinct requests, as required by FS-13.</p>
 */
@Entity
@Table(name = "idempotency_keys", indexes = {
        @Index(name = "idx_idem_merchant_key", columnList = "merchant_id,idempotency_key"),
        @Index(name = "idx_idem_expires", columnList = "expires_at")})
public class IdempotencyKey {

    public enum Status { PROCESSING, COMPLETED, FAILED }

    /** Composite primary key (merchant_id + key) — FS-13 merchant scoping. */
    @Id
    @Column(name = "idem_pk", nullable = false, updatable = false, length = 64)
    private String id;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 255)
    private String key;
    @Column(name = "merchant_id", nullable = false, updatable = false, length = 255)
    private String merchantId;

    @Column(nullable = false, updatable = false, length = 36)
    private String transactionId;

    /** Hash of the request payload: a different payload on the same key is a conflict. */
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PROCESSING;

    @Column(nullable = false, updatable = false)
    private Instant expiresAt = Instant.now().plusSeconds(24 * 3600);

    protected IdempotencyKey() {}

    public IdempotencyKey(String key, String merchantId, String transactionId, String requestHash) {
        this.key = key;
        this.merchantId = merchantId;
        this.transactionId = transactionId;
        this.requestHash = requestHash;
        this.id = compositeId(merchantId, key);
    }

    public static String compositeId(String merchantId, String key) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((merchantId + "\u0000" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public String getId() { return id; }

    public String getKey() { return key; }
    public String getMerchantId() { return merchantId; }
    public String getTransactionId() { return transactionId; }
    public String getRequestHash() { return requestHash; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
}
