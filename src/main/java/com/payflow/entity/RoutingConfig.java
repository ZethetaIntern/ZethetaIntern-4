package com.payflow.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Routing weights — stored in the DB so they can be tuned without redeployment. */
@Entity
@Table(name = "routing_config")
public class RoutingConfig {
    @Id
    @Column(nullable = false, updatable = false)
    private Long id = 1L;

    /** Weights are normalised by the routing engine; they express relative priority. */
    @Column(nullable = false)
    private double weightSuccess = 0.35;

    @Column(nullable = false)
    private double weightLatency = 0.20;

    @Column(nullable = false)
    private double weightCost = 0.20;

    @Column(nullable = false)
    private double weightHealth = 0.15;

    @Column(nullable = false)
    private double weightMethodFit = 0.10;

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public double getWeightSuccess() { return weightSuccess; }
    public void setWeightSuccess(double w) { this.weightSuccess = w; touch(); }
    public double getWeightLatency() { return weightLatency; }
    public void setWeightLatency(double w) { this.weightLatency = w; touch(); }
    public double getWeightCost() { return weightCost; }
    public void setWeightCost(double w) { this.weightCost = w; touch(); }
    public double getWeightHealth() { return weightHealth; }
    public void setWeightHealth(double w) { this.weightHealth = w; touch(); }
    public double getWeightMethodFit() { return weightMethodFit; }
    public void setWeightMethodFit(double w) { this.weightMethodFit = w; touch(); }
    public Instant getUpdatedAt() { return updatedAt; }
    private void touch() { this.updatedAt = Instant.now(); }
}
