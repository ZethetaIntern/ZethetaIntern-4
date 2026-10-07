package com.payflow.entity;

import com.payflow.domain.PaymentMethod;
import com.payflow.domain.TransactionState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Core transaction record holding the <em>current</em> state. History lives in
 * {@link TransactionStateLog}; the state column is only ever changed by
 * {@link com.payflow.statemachine.TransactionStateMachine}.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    public enum CaptureMode { AUTOMATIC, MANUAL }

    public enum UpiFlow { INTENT, COLLECT }

    @Id
    private UUID id = UUID.randomUUID();

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "merchant_order_id", nullable = false)
    private String merchantOrderId;

    /** Authorised amount in paise (A6.2: integer minor units, never floating point). */
    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false, length = 20)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "capture_mode", nullable = false, length = 16)
    private CaptureMode captureMode = CaptureMode.AUTOMATIC;

    @Enumerated(EnumType.STRING)
    @Column(name = "upi_flow", length = 16)
    private UpiFlow upiFlow;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TransactionState state = TransactionState.CREATED;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(length = 32)
    private String gateway;

    @Column(name = "gateway_reference")
    private String gatewayReference;

    @Column(name = "captured_paise", nullable = false)
    private long capturedPaise;

    /** Part of the authorisation hold released without capture (void of the remainder). */
    @Column(name = "released_paise", nullable = false)
    private long releasedPaise;

    @Column(name = "refunded_paise", nullable = false)
    private long refundedPaise;

    @Column(name = "attempts_made", nullable = false)
    private int attemptsMade;

    /** Asynchronous retries used when every gateway was throttled (FS-07). */
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason", length = 512)
    private String failureReason;

    /** A8.5: trace id generated when the payment request was received. */
    @Column(name = "trace_id", nullable = false)
    private UUID traceId;

    @Column(name = "authorised_at")
    private Instant authorisedAt;

    /** Authorisation hold expiry, or the UPI collect mandate window expiry. */
    @Column(name = "auth_expires_at")
    private Instant authExpiresAt;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Column(name = "settlement_batch_id", length = 64)
    private String settlementBatchId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    /** Amount still held on the customer's instrument and available for capture. */
    public long remainingHoldPaise() {
        return amountPaise - capturedPaise - releasedPaise;
    }

    public long refundablePaise() {
        return capturedPaise - refundedPaise;
    }

    public UUID getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getMerchantOrderId() { return merchantOrderId; }
    public void setMerchantOrderId(String merchantOrderId) { this.merchantOrderId = merchantOrderId; }
    public long getAmountPaise() { return amountPaise; }
    public void setAmountPaise(long amountPaise) { this.amountPaise = amountPaise; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(PaymentMethod paymentMethod) { this.paymentMethod = paymentMethod; }
    public CaptureMode getCaptureMode() { return captureMode; }
    public void setCaptureMode(CaptureMode captureMode) { this.captureMode = captureMode; }
    public UpiFlow getUpiFlow() { return upiFlow; }
    public void setUpiFlow(UpiFlow upiFlow) { this.upiFlow = upiFlow; }
    public TransactionState getState() { return state; }
    /** Called only by TransactionStateMachine, which validates and audits every change. */
    public void setState(TransactionState state) { this.state = state; }
    public long getVersion() { return version; }
    public String getGateway() { return gateway; }
    public void setGateway(String gateway) { this.gateway = gateway; }
    public String getGatewayReference() { return gatewayReference; }
    public void setGatewayReference(String gatewayReference) { this.gatewayReference = gatewayReference; }
    public long getCapturedPaise() { return capturedPaise; }
    public void setCapturedPaise(long capturedPaise) { this.capturedPaise = capturedPaise; }
    public long getReleasedPaise() { return releasedPaise; }
    public void setReleasedPaise(long releasedPaise) { this.releasedPaise = releasedPaise; }
    public long getRefundedPaise() { return refundedPaise; }
    public void setRefundedPaise(long refundedPaise) { this.refundedPaise = refundedPaise; }
    public int getAttemptsMade() { return attemptsMade; }
    public void setAttemptsMade(int attemptsMade) { this.attemptsMade = attemptsMade; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public Instant getNextRetryAt() { return nextRetryAt; }
    public void setNextRetryAt(Instant nextRetryAt) { this.nextRetryAt = nextRetryAt; }
    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String failureCode) { this.failureCode = failureCode; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public UUID getTraceId() { return traceId; }
    public void setTraceId(UUID traceId) { this.traceId = traceId; }
    public Instant getAuthorisedAt() { return authorisedAt; }
    public void setAuthorisedAt(Instant authorisedAt) { this.authorisedAt = authorisedAt; }
    public Instant getAuthExpiresAt() { return authExpiresAt; }
    public void setAuthExpiresAt(Instant authExpiresAt) { this.authExpiresAt = authExpiresAt; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
    public String getSettlementBatchId() { return settlementBatchId; }
    public void setSettlementBatchId(String settlementBatchId) { this.settlementBatchId = settlementBatchId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
