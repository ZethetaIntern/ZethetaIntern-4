package com.payflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persistent webhook event queue and dead letter queue (spec A5.2, A8.3).
 * Items are written after signature verification and deduplication, then
 * consumed by {@link com.payflow.webhook.WebhookEventProcessor}.
 */
@Entity
@Table(name = "webhook_queue")
public class WebhookQueueItem {

    public enum Status { PENDING, PROCESSING, COMPLETED, FAILED, DLQ }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String gateway;

    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String signature;

    @Column(name = "source_ip", length = 64)
    private String sourceIp;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 3;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    protected WebhookQueueItem() {}

    public WebhookQueueItem(String gateway, String eventId, String eventType, UUID transactionId,
                            Map<String, Object> payload, String signature, String sourceIp) {
        this.gateway = gateway;
        this.eventId = eventId;
        this.eventType = eventType;
        this.transactionId = transactionId;
        this.payload = payload;
        this.signature = signature;
        this.sourceIp = sourceIp;
        this.nextRetryAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getGateway() { return gateway; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public UUID getTransactionId() { return transactionId; }
    public Map<String, Object> getPayload() { return payload; }
    public String getSignature() { return signature; }
    public String getSourceIp() { return sourceIp; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
