package com.payflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Idempotency store (spec A4.2). The primary key is the composite
 * {@code (merchant_id, key)} so identical keys from different merchants are
 * different requests (FS-13).
 */
@Entity
@IdClass(IdempotencyKey.Pk.class)
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    public enum Status { PROCESSING, COMPLETED, FAILED }

    /** Composite primary key (merchant_id, key). */
    public static class Pk implements Serializable {
        private String merchantId;
        private String idempotencyKey;

        protected Pk() {}

        public Pk(String merchantId, String idempotencyKey) {
            this.merchantId = merchantId;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Pk p && java.util.Objects.equals(merchantId, p.merchantId)
                    && java.util.Objects.equals(idempotencyKey, p.idempotencyKey);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(merchantId, idempotencyKey);
        }
    }

    public static final Duration TTL = Duration.ofHours(24);

    @Id
    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Id
    @Column(name = "key", nullable = false)
    private String idempotencyKey;

    /** SHA-256 of the canonical request body: a different body on the same key is rejected. */
    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "request_path", nullable = false)
    private String requestPath;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PROCESSING;

    @Column(name = "response_code")
    private Integer responseCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_body")
    private Map<String, Object> responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt = Instant.now().plus(TTL);

    protected IdempotencyKey() {}

    public IdempotencyKey(String merchantId, String idempotencyKey, String requestHash, String requestPath) {
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.requestPath = requestPath;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public void complete(Status finalStatus, int code, Map<String, Object> body) {
        this.status = finalStatus;
        this.responseCode = code;
        this.responseBody = body;
    }

    public String getMerchantId() { return merchantId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getRequestPath() { return requestPath; }
    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Integer getResponseCode() { return responseCode; }
    public Map<String, Object> getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
