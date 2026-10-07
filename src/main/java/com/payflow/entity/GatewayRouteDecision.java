package com.payflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row per candidate gateway per routing decision (table {@code gateway_routes},
 * spec A6.1): which gateway was selected, its score, and the per-factor breakdown.
 */
@Entity
@Immutable
@Table(name = "gateway_routes")
public class GatewayRouteDecision {

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Column(nullable = false, length = 32)
    private String gateway;

    @Column(nullable = false)
    private int rank;

    @Column(nullable = false)
    private double score;

    @Column(nullable = false)
    private boolean selected;

    @Column(name = "health_status", nullable = false, length = 16)
    private String healthStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> breakdown;

    @Column(length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected GatewayRouteDecision() {}

    public GatewayRouteDecision(UUID transactionId, int attemptNo, String gateway, int rank, double score,
                                boolean selected, String healthStatus, Map<String, Object> breakdown,
                                String reason) {
        this.transactionId = transactionId;
        this.attemptNo = attemptNo;
        this.gateway = gateway;
        this.rank = rank;
        this.score = score;
        this.selected = selected;
        this.healthStatus = healthStatus;
        this.breakdown = breakdown;
        this.reason = reason;
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public int getAttemptNo() { return attemptNo; }
    public String getGateway() { return gateway; }
    public int getRank() { return rank; }
    public double getScore() { return score; }
    public boolean isSelected() { return selected; }
    public String getHealthStatus() { return healthStatus; }
    public Map<String, Object> getBreakdown() { return breakdown; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
