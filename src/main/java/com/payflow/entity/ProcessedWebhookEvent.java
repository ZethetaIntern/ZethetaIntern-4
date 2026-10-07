package com.payflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * Webhook deduplication store (spec A5.4). Composite primary key
 * {@code (gateway, event_id)}: event ids are only unique within one gateway.
 */
@Entity
@Immutable
@IdClass(ProcessedWebhookEvent.Pk.class)
@Table(name = "processed_webhook_events")
public class ProcessedWebhookEvent {

    /** Composite primary key (gateway, event_id). */
    public static class Pk implements Serializable {
        private String gateway;
        private String eventId;

        protected Pk() {}

        public Pk(String gateway, String eventId) {
            this.gateway = gateway;
            this.eventId = eventId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Pk p && java.util.Objects.equals(gateway, p.gateway)
                    && java.util.Objects.equals(eventId, p.eventId);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(gateway, eventId);
        }
    }

    @Id
    @Column(name = "gateway", nullable = false, length = 50)
    private String gateway;

    @Id
    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Column(name = "transaction_id")
    private UUID transactionId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();

    protected ProcessedWebhookEvent() {}

    public ProcessedWebhookEvent(String gateway, String eventId, String eventType, String payloadHash,
                                 UUID transactionId) {
        this.gateway = gateway;
        this.eventId = eventId;
        this.eventType = eventType;
        this.payloadHash = payloadHash;
        this.transactionId = transactionId;
    }

    public String getGateway() { return gateway; }
    public String getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getPayloadHash() { return payloadHash; }
    public UUID getTransactionId() { return transactionId; }
    public Instant getProcessedAt() { return processedAt; }
}
