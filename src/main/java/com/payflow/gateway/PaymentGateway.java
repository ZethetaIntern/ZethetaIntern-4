package com.payflow.gateway;

import com.payflow.domain.PaymentMethod;
import com.payflow.entity.Transaction;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Uniform gateway contract (Day 5-6 "PaymentGateway interface"). Each adapter
 * translates to its gateway's API shape; the orchestrator only ever sees these
 * types. Every request carries the trace id (sent as {@code X-Trace-Id}) and the
 * gateway-specific idempotency token, so a retried call never creates a second charge.
 */
public interface PaymentGateway {

    String name();

    AuthResponse authorize(AuthRequest request) throws GatewayException;

    CaptureResponse capture(CaptureRequest request) throws GatewayException;

    RefundResponse refund(RefundRequest request) throws GatewayException;

    void voidAuthorisation(VoidRequest request) throws GatewayException;

    /** A5.5 step 2: the gateway's authoritative status, by reference or by our transaction id. */
    StatusResponse fetchStatus(String reference, UUID transactionId) throws GatewayException;

    /** Settlement report for a batch of references (C2.4): SETTLED / PENDING / FAILED / REVERSED / UNKNOWN. */
    Map<String, SettlementEntry> settlementReport(Collection<String> references) throws GatewayException;

    record AuthRequest(UUID transactionId, long amountPaise, String currency, PaymentMethod method,
                       Transaction.UpiFlow upiFlow, String idempotencyKey, String traceId) {}

    /** {@code status} is AUTHORISED (two-phase), CAPTURED (instant rails) or PENDING (UPI collect). */
    record AuthResponse(GatewayStatus status, String reference, Map<String, Object> raw) {}

    record CaptureRequest(UUID transactionId, String reference, long amountPaise, String idempotencyKey,
                          String traceId) {}

    record CaptureResponse(String reference, long capturedPaise, Map<String, Object> raw) {}

    record RefundRequest(UUID transactionId, String reference, UUID refundId, long amountPaise, String traceId) {}

    record RefundResponse(String gatewayRefundId, Map<String, Object> raw) {}

    record VoidRequest(UUID transactionId, String reference, long amountPaise, String traceId) {}

    record StatusResponse(GatewayStatus status, String reference, long capturedPaise, Map<String, Object> raw) {}

    record SettlementEntry(GatewayStatus status, String settlementBatchId) {}
}
