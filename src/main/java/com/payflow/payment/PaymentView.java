package com.payflow.payment;

import com.payflow.entity.Transaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public representation of a payment. Amounts are integer paise; {@code amount}
 * is the display string derived from paise at the API boundary only (A6.2).
 */
public record PaymentView(
        UUID id,
        String merchantId,
        String merchantOrderId,
        long amountPaise,
        String amount,
        String currency,
        String paymentMethod,
        String captureMode,
        String upiFlow,
        String state,
        String gateway,
        String gatewayReference,
        long capturedPaise,
        long remainingHoldPaise,
        long releasedPaise,
        long refundedPaise,
        int attemptsMade,
        int retryCount,
        Instant nextRetryAt,
        String failureCode,
        String failureReason,
        UUID traceId,
        Instant authorisedAt,
        Instant authExpiresAt,
        Instant settledAt,
        String settlementBatchId,
        Instant createdAt,
        Instant updatedAt) {

    public static PaymentView of(Transaction t) {
        return new PaymentView(t.getId(), t.getMerchantId(), t.getMerchantOrderId(), t.getAmountPaise(),
                BigDecimal.valueOf(t.getAmountPaise(), 2).toPlainString(), t.getCurrency(),
                t.getPaymentMethod().name(), t.getCaptureMode().name(),
                t.getUpiFlow() == null ? null : t.getUpiFlow().name(), t.getState().name(), t.getGateway(),
                t.getGatewayReference(), t.getCapturedPaise(), t.remainingHoldPaise(), t.getReleasedPaise(),
                t.getRefundedPaise(), t.getAttemptsMade(), t.getRetryCount(), t.getNextRetryAt(), t.getFailureCode(),
                t.getFailureReason(), t.getTraceId(), t.getAuthorisedAt(), t.getAuthExpiresAt(), t.getSettledAt(),
                t.getSettlementBatchId(), t.getCreatedAt(), t.getUpdatedAt());
    }
}
