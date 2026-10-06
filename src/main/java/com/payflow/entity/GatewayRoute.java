package com.payflow.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Per-gateway configuration + rolling health metrics (router inputs, DB-configurable). */
@Entity
@Table(name = "gateway_routes")
public class GatewayRoute {
    @Id
    @Column(nullable = false, updatable = false, length = 32)
    private String gateway;

    private boolean supportsUpi = true;

    /** Cost in basis points of transaction value (e.g. 200 = 2.0%). */
    @Column(nullable = false)
    private int costBps;

    @Column(nullable = false)
    private int fixedCostPaise;

    /** Configured baseline latency in ms (from the historical dataset). */
    @Column(nullable = false)
    private int baseLatencyMs;

    @Column(nullable = false)
    private boolean healthy = true;

    @Column(nullable = false)
    private int successCount;

    @Column(nullable = false)
    private int failureCount;

    @Column(nullable = false)
    private long totalLatencyMs;

    @Column(nullable = false)
    private int consecutiveFailures;

    @Column(nullable = false)
    private Instant circuitOpenUntil = Instant.EPOCH;

    @Column(name = "probe_lease_until", nullable = false)
    private Instant probeLeaseUntil = Instant.EPOCH;

    public String getGateway() { return gateway; }
    public void setGateway(String g) { this.gateway = g; }
    public boolean isSupportsUpi() { return supportsUpi; }
    public void setSupportsUpi(boolean s) { this.supportsUpi = s; }
    public int getCostBps() { return costBps; }
    public void setCostBps(int c) { this.costBps = c; }
    public int getFixedCostPaise() { return fixedCostPaise; }
    public void setFixedCostPaise(int f) { this.fixedCostPaise = f; }
    public int getBaseLatencyMs() { return baseLatencyMs; }
    public void setBaseLatencyMs(int l) { this.baseLatencyMs = l; }
    public boolean isHealthy() { return healthy; }
    public void setHealthy(boolean h) { this.healthy = h; }
    public int getSuccessCount() { return successCount; }
    public void setSuccessCount(int s) { this.successCount = s; }
    public int getFailureCount() { return failureCount; }
    public void setFailureCount(int f) { this.failureCount = f; }
    public long getTotalLatencyMs() { return totalLatencyMs; }
    public void setTotalLatencyMs(long t) { this.totalLatencyMs = t; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int c) { this.consecutiveFailures = c; }
    public Instant getCircuitOpenUntil() { return circuitOpenUntil; }
    public void setCircuitOpenUntil(Instant t) { this.circuitOpenUntil = t; }
    public boolean isProbeInFlight(Instant now) { return probeLeaseUntil != null && now.isBefore(probeLeaseUntil); }
    public void setProbeLeaseUntil(Instant probeLeaseUntil) { this.probeLeaseUntil = probeLeaseUntil; }
}
