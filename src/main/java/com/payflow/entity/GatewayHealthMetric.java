package com.payflow.entity;

import com.payflow.domain.HealthStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

/** Per-minute gateway performance aggregate (table {@code gateway_health_metrics}, spec A6.1). */
@Entity
@Immutable
@Table(name = "gateway_health_metrics")
public class GatewayHealthMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String gateway;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "success_count", nullable = false)
    private int successCount;

    @Column(name = "success_rate", nullable = false)
    private double successRate;

    @Column(name = "p95_latency_ms", nullable = false)
    private int p95LatencyMs;

    @Column(name = "avg_latency_ms", nullable = false)
    private int avgLatencyMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "health_status", nullable = false, length = 16)
    private HealthStatus healthStatus;

    protected GatewayHealthMetric() {}

    public GatewayHealthMetric(String gateway, Instant recordedAt, int totalCount, int successCount,
                               int p95LatencyMs, int avgLatencyMs, HealthStatus healthStatus) {
        this.gateway = gateway;
        this.recordedAt = recordedAt;
        this.totalCount = totalCount;
        this.successCount = successCount;
        this.successRate = totalCount == 0 ? 0.0 : (double) successCount / totalCount;
        this.p95LatencyMs = p95LatencyMs;
        this.avgLatencyMs = avgLatencyMs;
        this.healthStatus = healthStatus;
    }

    public Long getId() { return id; }
    public String getGateway() { return gateway; }
    public Instant getRecordedAt() { return recordedAt; }
    public int getTotalCount() { return totalCount; }
    public int getSuccessCount() { return successCount; }
    public double getSuccessRate() { return successRate; }
    public int getP95LatencyMs() { return p95LatencyMs; }
    public int getAvgLatencyMs() { return avgLatencyMs; }
    public HealthStatus getHealthStatus() { return healthStatus; }
}
