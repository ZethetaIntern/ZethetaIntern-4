package com.payflow.entity;

import com.payflow.domain.TransactionState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Immutable audit trail entry (spec A2.3). Rows are insert-only: Hibernate
 * treats the entity as {@link Immutable} and a database trigger rejects any
 * UPDATE or DELETE.
 *
 * <p>For events that do not change state (gateway attempt results, a released
 * hold, a rejected transition) {@code to_state} records the state the
 * transaction is in, or for {@code REJECTED_TRANSITION} the state that was
 * attempted.</p>
 */
@Entity
@Immutable
@Table(name = "transaction_state_log")
public class TransactionStateLog {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_state", length = 32, updatable = false)
    private TransactionState fromState;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_state", nullable = false, length = 32, updatable = false)
    private TransactionState toState;

    @Column(nullable = false, length = 100, updatable = false)
    private String event;

    @Column(name = "gateway_reference", updatable = false)
    private String gatewayReference;

    /** Full gateway response payload, PII redacted before it gets here. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "gateway_response", updatable = false)
    private Map<String, Object> gatewayResponse;

    /** Additional context: trace_id, request_id, client IP, user agent, amounts. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(updatable = false)
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = nextTimestamp();

    /** Strictly increasing per JVM (microsecond steps) so a payment's timeline orders deterministically. */
    private static final java.util.concurrent.atomic.AtomicReference<Instant> LAST =
            new java.util.concurrent.atomic.AtomicReference<>(Instant.EPOCH);

    private static Instant nextTimestamp() {
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return LAST.accumulateAndGet(now, (prev, cur) -> cur.isAfter(prev) ? cur : prev.plusNanos(1_000));
    }

    @Column(name = "created_by", nullable = false, length = 100, updatable = false)
    private String createdBy;

    protected TransactionStateLog() {}

    public TransactionStateLog(UUID transactionId, TransactionState fromState, TransactionState toState,
                               String event, String gatewayReference, Map<String, Object> gatewayResponse,
                               Map<String, Object> metadata, String createdBy) {
        this.transactionId = transactionId;
        this.fromState = fromState;
        this.toState = toState;
        this.event = event;
        this.gatewayReference = gatewayReference;
        this.gatewayResponse = gatewayResponse;
        this.metadata = metadata;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public TransactionState getFromState() { return fromState; }
    public TransactionState getToState() { return toState; }
    public String getEvent() { return event; }
    public String getGatewayReference() { return gatewayReference; }
    public Map<String, Object> getGatewayResponse() { return gatewayResponse; }
    public Map<String, Object> getMetadata() { return metadata; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
