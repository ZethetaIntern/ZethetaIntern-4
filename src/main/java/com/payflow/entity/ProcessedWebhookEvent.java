package com.payflow.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Webhook deduplication store. Composite PK (gateway, event_id) per spec A5.4;
 * insert + reconciliation happen in the same transaction so concurrent
 * deliveries cannot double-process.
 */
@Entity
@Table(name = "processed_webhook_events")
public class ProcessedWebhookEvent {
    @Id
    @Column(name = "event_key", nullable = false, updatable = false, length = 320)
    private String eventKey; // gateway + ":" + eventId

    @Column(nullable = false, updatable = false)
    private String gateway;

    @Column(nullable = false, updatable = false)
    private String eventId;

    @Column(nullable = false, updatable = false)
    private String eventType;

    @Column(nullable = false, updatable = false)
    private String payloadHash;

    private String transactionId;

    @Column(nullable = false, updatable = false)
    private Instant processedAt = Instant.now();

    protected ProcessedWebhookEvent() {}

    public ProcessedWebhookEvent(String gateway, String eventId, String eventType,
                                 String payloadHash, String transactionId) {
        this.eventKey = gateway + ":" + eventId;
        this.gateway = gateway;
        this.eventId = eventId;
        this.eventType = eventType;
        this.payloadHash = payloadHash;
        this.transactionId = transactionId;
    }

    public String getEventKey() { return eventKey; }
    public String getGateway() { return gateway; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayloadHash() { return payloadHash; }
    public String getTransactionId() { return transactionId; }
    public Instant getProcessedAt() { return processedAt; }
}
