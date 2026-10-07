package com.payflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Circuit breaker settings (spec A3.3), stored in the database so they can be
 * changed without redeployment. {@code '*'} rows are wildcards; the most
 * specific (gateway, payment_method) match wins.
 */
@Entity
@Table(name = "circuit_breaker_config")
public class CircuitBreakerConfig {

    public static final String ANY = "*";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String gateway;

    @Column(name = "payment_method", nullable = false, length = 20)
    private String paymentMethod;

    @Column(name = "failure_threshold", nullable = false)
    private int failureThreshold;

    @Column(name = "open_timeout_ms", nullable = false)
    private int openTimeoutMs;

    @Column(name = "half_open_max_requests", nullable = false)
    private int halfOpenMaxRequests;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CircuitBreakerConfig() {}

    public CircuitBreakerConfig(String gateway, String paymentMethod, int failureThreshold, int openTimeoutMs,
                                int halfOpenMaxRequests) {
        this.gateway = gateway;
        this.paymentMethod = paymentMethod;
        this.failureThreshold = failureThreshold;
        this.openTimeoutMs = openTimeoutMs;
        this.halfOpenMaxRequests = halfOpenMaxRequests;
    }

    public void update(Integer threshold, Integer timeoutMs, Integer halfOpen) {
        if (threshold != null) this.failureThreshold = threshold;
        if (timeoutMs != null) this.openTimeoutMs = timeoutMs;
        if (halfOpen != null) this.halfOpenMaxRequests = halfOpen;
        this.updatedAt = Instant.now();
    }

    /** Specificity used to pick the best matching row: exact beats wildcard. */
    public int specificity() {
        return (ANY.equals(gateway) ? 0 : 2) + (ANY.equals(paymentMethod) ? 0 : 1);
    }

    public boolean matches(String gw, String method) {
        return (ANY.equals(gateway) || gateway.equals(gw)) && (ANY.equals(paymentMethod) || paymentMethod.equals(method));
    }

    public Long getId() { return id; }
    public String getGateway() { return gateway; }
    public String getPaymentMethod() { return paymentMethod; }
    public int getFailureThreshold() { return failureThreshold; }
    public int getOpenTimeoutMs() { return openTimeoutMs; }
    public int getHalfOpenMaxRequests() { return halfOpenMaxRequests; }
    public Instant getUpdatedAt() { return updatedAt; }
}
