package com.payflow.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Historical hourly gateway performance (spec A3.4). The router is seeded from
 * this dataset so a cold start ranks gateways sensibly before live traffic
 * accumulates; rows are replaced by rolling aggregates as traffic arrives.
 */
@Entity
@Table(name = "gateway_hourly_metrics", indexes = @Index(name = "idx_hourly_gw",
        columnList = "gateway,recordedAt"))
public class GatewayHourlyMetric {

    @Id
    @Column(nullable = false, updatable = false, length = 32)
    private String id;

    @Column(nullable = false, updatable = false, length = 32)
    private String gateway;

    @Column(nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(nullable = false)
    private double successRate;

    @Column(name = "p95_latency_ms", nullable = false)
    private int p95LatencyMs;

    @Column(nullable = false)
    private int transactionCount;

    public GatewayHourlyMetric() {}

    public GatewayHourlyMetric(String gateway, Instant recordedAt, double successRate,
                               int p95LatencyMs, int transactionCount) {
        this.id = gateway + "@" + recordedAt.getEpochSecond() / 3600;
        this.gateway = gateway;
        this.recordedAt = recordedAt;
        this.successRate = successRate;
        this.p95LatencyMs = p95LatencyMs;
        this.transactionCount = transactionCount;
    }

    public String getId() { return id; }
    public String getGateway() { return gateway; }
    public Instant getRecordedAt() { return recordedAt; }
    public double getSuccessRate() { return successRate; }
    public int getP95LatencyMs() { return p95LatencyMs; }
    public int getTransactionCount() { return transactionCount; }
}
