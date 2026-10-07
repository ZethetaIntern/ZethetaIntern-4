package com.payflow.entity;

import com.payflow.domain.AttemptOutcome;
import com.payflow.domain.PaymentMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** One gateway API call: feeds latency tracking (A3.1) and the per-minute health metrics. */
@Entity
@Immutable
@Table(name = "gateway_attempts")
public class GatewayAttempt {

    public enum Operation { AUTH, CAPTURE, REFUND, VOID, STATUS }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(nullable = false, length = 32)
    private String gateway;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Operation operation;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AttemptOutcome outcome;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected GatewayAttempt() {}

    public GatewayAttempt(UUID transactionId, String gateway, PaymentMethod paymentMethod, Operation operation,
                          int attemptNo, AttemptOutcome outcome, long latencyMs, Integer httpStatus,
                          String errorCode) {
        this.transactionId = transactionId;
        this.gateway = gateway;
        this.paymentMethod = paymentMethod;
        this.operation = operation;
        this.attemptNo = attemptNo;
        this.outcome = outcome;
        this.latencyMs = latencyMs;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public UUID getId() { return id; }
    public UUID getTransactionId() { return transactionId; }
    public String getGateway() { return gateway; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public Operation getOperation() { return operation; }
    public int getAttemptNo() { return attemptNo; }
    public AttemptOutcome getOutcome() { return outcome; }
    public long getLatencyMs() { return latencyMs; }
    public Integer getHttpStatus() { return httpStatus; }
    public String getErrorCode() { return errorCode; }
    public Instant getCreatedAt() { return createdAt; }
}
