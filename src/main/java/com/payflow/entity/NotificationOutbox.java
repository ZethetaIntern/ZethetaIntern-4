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
 * Transactional outbox for customer/merchant notifications (A5.2 notification
 * dispatcher, FS-12 "customer is notified"). Written in the same database
 * transaction as the state change, delivered asynchronously.
 */
@Entity
@Table(name = "notification_outbox")
public class NotificationOutbox {

    public enum Status { PENDING, SENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(nullable = false, length = 64)
    private String template;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    protected NotificationOutbox() {}

    public NotificationOutbox(UUID transactionId, String template, Map<String, Object> payload) {
        this.transactionId = transactionId;
        this.template = template;
        this.payload = payload;
    }

    public void markSent() {
        this.status = Status.SENT;
        this.sentAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public String getTemplate() { return template; }
    public Map<String, Object> getPayload() { return payload; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getSentAt() { return sentAt; }
}
