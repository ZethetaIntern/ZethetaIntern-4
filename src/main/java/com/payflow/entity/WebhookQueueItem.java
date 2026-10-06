package com.payflow.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Dead letter queue for webhooks (spec A8.3). Webhooks that cannot be
 * processed after {@code maxRetries} land here instead of being lost, and can
 * be replayed through the admin API once the root cause is fixed.
 */
@Entity
@Table(name = "webhook_queue", indexes = @Index(name = "idx_webhook_queue_status", columnList = "status"))
public class WebhookQueueItem {

    public enum Status { PENDING, PROCESSING, COMPLETED, FAILED, DLQ }

    @Id
    @Column(nullable = false, updatable = false, length = 36)
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false, updatable = false, length = 32)
    private String gateway;

    @Column(name = "event_id", nullable = false, updatable = false, length = 255)
    private String eventId;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false, updatable = false, columnDefinition = "TEXT")
    private String signature;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 3;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    protected WebhookQueueItem() {}

    public WebhookQueueItem(String gateway, String eventId, String payload, String signature) {
        this.gateway = gateway;
        this.eventId = eventId;
        this.payload = payload;
        this.signature = signature;
    }

    public String getId() { return id; }
    public String getGateway() { return gateway; }
    public String getEventId() { return eventId; }
    public String getPayload() { return payload; }
    public String getSignature() { return signature; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getRetryCount() { return retryCount; }
    public void incrementRetries() { this.retryCount++; }
    public int getMaxRetries() { return maxRetries; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String e) { this.errorMessage = e; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant t) { this.processedAt = t; }
}
