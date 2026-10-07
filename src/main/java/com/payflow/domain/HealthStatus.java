package com.payflow.domain;

/** Gateway health as used by the routing score (spec A3.2 HealthScore). */
public enum HealthStatus {
    HEALTHY(1.0),
    DEGRADED(0.5),
    DOWN(0.0);

    private final double score;

    HealthStatus(double score) {
        this.score = score;
    }

    public double score() {
        return score;
    }
}
