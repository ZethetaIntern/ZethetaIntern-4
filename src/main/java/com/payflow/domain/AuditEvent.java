package com.payflow.domain;

/**
 * Event names written to {@code transaction_state_log.event} (spec A2.3).
 * Every row in the audit trail carries one of these.
 */
public final class AuditEvent {
    private AuditEvent() {}

    public static final String PAYMENT_CREATED = "PAYMENT_CREATED";
    public static final String ROUTE_SELECTED = "ROUTE_SELECTED";
    public static final String ROUTE_FAILED = "ROUTE_FAILED";
    public static final String GATEWAY_AUTH_REQUESTED = "GATEWAY_AUTH_REQUESTED";
    public static final String GATEWAY_AUTH_SUCCESS = "GATEWAY_AUTH_SUCCESS";
    public static final String GATEWAY_AUTH_DECLINED = "GATEWAY_AUTH_DECLINED";
    public static final String AUTH_TIMEOUT = "AUTH_TIMEOUT";
    public static final String GATEWAY_FAILOVER = "GATEWAY_FAILOVER";
    public static final String PAYMENT_QUEUED_FOR_RETRY = "PAYMENT_QUEUED_FOR_RETRY";
    public static final String MAX_RETRIES_EXCEEDED = "MAX_RETRIES_EXCEEDED";
    public static final String UPI_COLLECT_INITIATED = "UPI_COLLECT_INITIATED";
    public static final String AUTH_HOLD_EXPIRED = "AUTH_HOLD_EXPIRED";
    public static final String MERCHANT_CAPTURE_REQUESTED = "MERCHANT_CAPTURE_REQUESTED";
    public static final String GATEWAY_CAPTURE_SUCCESS = "GATEWAY_CAPTURE_SUCCESS";
    public static final String GATEWAY_PARTIAL_CAPTURE = "GATEWAY_PARTIAL_CAPTURE";
    public static final String GATEWAY_CAPTURE_ERROR = "GATEWAY_CAPTURE_ERROR";
    public static final String CAPTURE_RETRY = "CAPTURE_RETRY";
    public static final String LATE_CAPTURE_SUCCESS = "LATE_CAPTURE_SUCCESS";
    public static final String VOID_REQUESTED = "VOID_REQUESTED";
    public static final String GATEWAY_VOID_SUCCESS = "GATEWAY_VOID_SUCCESS";
    public static final String REMAINING_HOLD_RELEASED = "REMAINING_HOLD_RELEASED";
    public static final String REFUND_REQUESTED = "REFUND_REQUESTED";
    public static final String GATEWAY_REFUND_SUCCESS = "GATEWAY_REFUND_SUCCESS";
    public static final String GATEWAY_REFUND_FAILED = "GATEWAY_REFUND_FAILED";
    public static final String SETTLEMENT_CONFIRMED = "SETTLEMENT_CONFIRMED";
    public static final String DISPUTE_OPENED = "DISPUTE_OPENED";
    public static final String DISPUTE_RESOLVED = "DISPUTE_RESOLVED";
    public static final String WEBHOOK_RECEIVED = "WEBHOOK_RECEIVED";
    public static final String DUPLICATE_TRANSITION_IGNORED = "DUPLICATE_TRANSITION_IGNORED";
    public static final String RECONCILIATION_OVERRIDE = "RECONCILIATION_OVERRIDE";
    public static final String RECONCILIATION_MISMATCH = "RECONCILIATION_MISMATCH";
    public static final String REJECTED_TRANSITION = "REJECTED_TRANSITION";
    public static final String ABANDONED = "ABANDONED";
}
