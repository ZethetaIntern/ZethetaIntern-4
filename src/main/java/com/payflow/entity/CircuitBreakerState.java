package com.payflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.io.Serializable;
import java.time.Instant;

/**
 * Runtime circuit state per (gateway, payment method) — spec A3.3: "a gateway
 * may be healthy for card payments but degraded for UPI". Shared through the
 * database so every application instance sees the same circuit.
 */
@Entity
@IdClass(CircuitBreakerState.Pk.class)
@Table(name = "circuit_breaker_state")
public class CircuitBreakerState {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** Composite primary key (gateway, payment_method). */
    public static class Pk implements Serializable {
        private String gateway;
        private String paymentMethod;

        protected Pk() {}

        public Pk(String gateway, String paymentMethod) {
            this.gateway = gateway;
            this.paymentMethod = paymentMethod;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Pk p && java.util.Objects.equals(gateway, p.gateway)
                    && java.util.Objects.equals(paymentMethod, p.paymentMethod);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(gateway, paymentMethod);
        }
    }

    @Id
    @Column(nullable = false, length = 32)
    private String gateway;

    @Id
    @Column(name = "payment_method", nullable = false, length = 20)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private State state = State.CLOSED;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "half_open_in_flight", nullable = false)
    private int halfOpenInFlight;

    @Column(name = "half_open_successes", nullable = false)
    private int halfOpenSuccesses;

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(nullable = false)
    private long version;

    protected CircuitBreakerState() {}

    public CircuitBreakerState(String gateway, String paymentMethod) {
        this.gateway = gateway;
        this.paymentMethod = paymentMethod;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public void open(Instant now) {
        this.state = State.OPEN;
        this.openedAt = now;
        this.halfOpenInFlight = 0;
        this.halfOpenSuccesses = 0;
    }

    public void halfOpen() {
        this.state = State.HALF_OPEN;
        this.halfOpenInFlight = 0;
        this.halfOpenSuccesses = 0;
    }

    public void close() {
        this.state = State.CLOSED;
        this.consecutiveFailures = 0;
        this.halfOpenInFlight = 0;
        this.halfOpenSuccesses = 0;
        this.openedAt = null;
    }

    public String getGateway() { return gateway; }
    public String getPaymentMethod() { return paymentMethod; }
    public State getState() { return state; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(int consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public int getHalfOpenInFlight() { return halfOpenInFlight; }
    public void setHalfOpenInFlight(int halfOpenInFlight) { this.halfOpenInFlight = halfOpenInFlight; }
    public int getHalfOpenSuccesses() { return halfOpenSuccesses; }
    public void setHalfOpenSuccesses(int halfOpenSuccesses) { this.halfOpenSuccesses = halfOpenSuccesses; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getLastFailureAt() { return lastFailureAt; }
    public void setLastFailureAt(Instant lastFailureAt) { this.lastFailureAt = lastFailureAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
