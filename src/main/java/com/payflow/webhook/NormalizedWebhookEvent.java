package com.payflow.webhook;

import com.payflow.gateway.GatewayStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A webhook reduced to the fields the orchestrator acts on, whatever gateway sent it. */
public record NormalizedWebhookEvent(
        String eventId,
        String eventType,
        GatewayStatus status,
        String gatewayReference,
        UUID transactionId,
        Long amountPaise,
        String currency,
        Long refundAmountPaise,
        String gatewayRefundId,
        String settlementBatchId) {

    /** Stored in {@code webhook_queue.payload.normalized}. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event_id", eventId);
        m.put("event_type", eventType);
        m.put("status", status.name());
        m.put("gateway_reference", gatewayReference);
        m.put("transaction_id", transactionId == null ? null : transactionId.toString());
        m.put("amount_paise", amountPaise);
        m.put("currency", currency);
        m.put("refund_amount_paise", refundAmountPaise);
        m.put("gateway_refund_id", gatewayRefundId);
        m.put("settlement_batch_id", settlementBatchId);
        return m;
    }

    public static NormalizedWebhookEvent fromMap(Map<?, ?> m) {
        return new NormalizedWebhookEvent(str(m, "event_id"), str(m, "event_type"),
                GatewayStatus.valueOf(str(m, "status")), str(m, "gateway_reference"),
                m.get("transaction_id") == null ? null : UUID.fromString(str(m, "transaction_id")),
                lng(m, "amount_paise"), str(m, "currency"), lng(m, "refund_amount_paise"),
                str(m, "gateway_refund_id"), str(m, "settlement_batch_id"));
    }

    private static String str(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v == null ? null : v.toString();
    }

    private static Long lng(Map<?, ?> m, String k) {
        Object v = m.get(k);
        return v == null ? null : ((Number) v).longValue();
    }
}
