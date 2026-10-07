package com.payflow.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.gateway.GatewayStatus;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Extracts the event id and payment facts from each gateway's webhook shape
 * (A5.4: "field name varies by gateway"). A generic flat format is also accepted
 * for any gateway: {@code {"event_id", "event_type", "status", "gateway_reference",
 * "transaction_id", "amount", "currency"}}.
 */
@Component
public class WebhookPayloadParser {

    /** Raised for payloads that are not valid JSON or lack an event identifier. */
    public static class MalformedWebhookException extends RuntimeException {
        public MalformedWebhookException(String message) {
            super(message);
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    public JsonNode readTree(byte[] body) {
        try {
            JsonNode n = mapper.readTree(body);
            if (n == null || !n.isObject()) throw new MalformedWebhookException("webhook body must be a JSON object");
            return n;
        } catch (IOException e) {
            throw new MalformedWebhookException("webhook body is not valid JSON");
        }
    }

    public Map<String, Object> toMap(JsonNode node) {
        return mapper.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }

    /** @param headers lower-case header names */
    public NormalizedWebhookEvent parse(String gateway, Map<String, String> headers, JsonNode body) {
        if (body.hasNonNull("event_id")) return generic(body);
        NormalizedWebhookEvent e = switch (gateway) {
            case "razorpay" -> razorpay(headers, body);
            case "stripe" -> stripe(body);
            case "payu" -> payu(body);
            case "upi" -> upi(body);
            default -> throw new MalformedWebhookException("unknown gateway " + gateway);
        };
        if (e.eventId() == null || e.eventId().isBlank()) throw new MalformedWebhookException("missing event id");
        return e;
    }

    /** Razorpay: {"entity":"event","event":"payment.captured","payload":{"payment":{"entity":{...}}}}. */
    private NormalizedWebhookEvent razorpay(Map<String, String> headers, JsonNode b) {
        String type = text(b, "event");
        JsonNode payment = b.path("payload").path("payment").path("entity");
        JsonNode refund = b.path("payload").path("refund").path("entity");
        JsonNode settlement = b.path("payload").path("settlement").path("entity");
        String paymentId = text(payment, "id");
        String eventId = headers.getOrDefault("x-razorpay-event-id", text(b, "id"));
        if (eventId == null && type != null) {
            eventId = type + ":" + (paymentId != null ? paymentId : text(refund, "id")) + ":" + text(b, "created_at");
        }
        GatewayStatus status = switch (String.valueOf(type)) {
            case "payment.authorized" -> GatewayStatus.AUTHORISED;
            case "payment.captured", "order.paid" -> GatewayStatus.CAPTURED;
            case "payment.failed" -> GatewayStatus.FAILED;
            case "refund.processed", "refund.created" -> GatewayStatus.REFUNDED;
            case "refund.failed" -> GatewayStatus.REFUND_FAILED;
            case "payment.dispute.created" -> GatewayStatus.DISPUTE_OPENED;
            case "payment.dispute.won", "payment.dispute.lost", "payment.dispute.closed" -> GatewayStatus.DISPUTE_RESOLVED;
            case "settlement.processed" -> GatewayStatus.SETTLED;
            case "payment.reversed" -> GatewayStatus.REVERSED;
            default -> GatewayStatus.UNKNOWN;
        };
        String reference = paymentId != null ? paymentId : text(refund, "payment_id");
        return new NormalizedWebhookEvent(eventId, type, status, reference,
                uuid(payment.path("notes").path("transaction_id")), longOrNull(payment, "amount"),
                upper(text(payment, "currency")), longOrNull(refund, "amount"), text(refund, "id"),
                text(settlement, "id"));
    }

    /** Stripe: {"id":"evt_..","type":"payment_intent.succeeded","data":{"object":{...}}}. */
    private NormalizedWebhookEvent stripe(JsonNode b) {
        String type = text(b, "type");
        JsonNode obj = b.path("data").path("object");
        boolean isCharge = "charge".equals(text(obj, "object"));
        String reference = isCharge ? text(obj, "payment_intent") : text(obj, "id");
        GatewayStatus status = switch (String.valueOf(type)) {
            case "payment_intent.amount_capturable_updated" -> GatewayStatus.AUTHORISED;
            case "payment_intent.succeeded", "charge.captured" -> GatewayStatus.CAPTURED;
            case "payment_intent.payment_failed", "charge.failed" -> GatewayStatus.FAILED;
            case "payment_intent.canceled" -> GatewayStatus.VOIDED;
            case "charge.refunded" -> GatewayStatus.REFUNDED;
            case "charge.refund.updated" -> "failed".equals(text(obj, "status"))
                    ? GatewayStatus.REFUND_FAILED : GatewayStatus.UNKNOWN;
            case "charge.dispute.created" -> GatewayStatus.DISPUTE_OPENED;
            case "charge.dispute.closed" -> GatewayStatus.DISPUTE_RESOLVED;
            default -> GatewayStatus.UNKNOWN;
        };
        Long amount = longOrNull(obj, "amount");
        Long refundAmount = "charge.refunded".equals(type) ? longOrNull(obj, "amount_refunded") : null;
        return new NormalizedWebhookEvent(text(b, "id"), type, status, reference,
                uuid(obj.path("metadata").path("transaction_id")), amount, upper(text(obj, "currency")),
                refundAmount, refundAmount == null ? null : text(b, "id"), null);
    }

    /** PayU: {"mihpayid":..,"txnid":..,"status":"success","unmappedstatus":"captured","amount":"500.00"}. */
    private NormalizedWebhookEvent payu(JsonNode b) {
        String unmapped = lower(text(b, "unmappedstatus"));
        String st = lower(text(b, "status"));
        GatewayStatus status;
        if ("auth".equals(unmapped)) status = GatewayStatus.AUTHORISED;
        else if ("refunded".equals(unmapped)) status = GatewayStatus.REFUNDED;
        else if ("cancelled".equals(unmapped)) status = GatewayStatus.VOIDED;
        else if ("success".equals(st)) status = GatewayStatus.CAPTURED;
        else if ("failure".equals(st) || "failed".equals(st)) status = GatewayStatus.FAILED;
        else status = GatewayStatus.PENDING;
        String mihpayid = text(b, "mihpayid");
        String eventId = b.hasNonNull("event_id") ? text(b, "event_id") : mihpayid + ":" + (unmapped != null ? unmapped : st);
        Long amount = rupeesToPaise(text(b, "amount"));
        return new NormalizedWebhookEvent(eventId, "payu." + (unmapped != null ? unmapped : st), status, mihpayid,
                uuid(b.path("txnid")), status == GatewayStatus.REFUNDED ? null : amount, "INR",
                status == GatewayStatus.REFUNDED ? amount : null, text(b, "refund_id"), null);
    }

    /** UPI/NPCI callback: {"txnId":..,"merchantTxnRef":..,"status":"SUCCESS|FAILURE|EXPIRED","amount":"500.00"}. */
    private NormalizedWebhookEvent upi(JsonNode b) {
        String st = upper(text(b, "status"));
        GatewayStatus status = switch (String.valueOf(st)) {
            case "SUCCESS" -> GatewayStatus.CAPTURED;
            case "FAILURE", "FAILED", "DECLINED" -> GatewayStatus.FAILED;
            case "EXPIRED" -> GatewayStatus.EXPIRED;
            case "PENDING" -> GatewayStatus.PENDING;
            case "REVERSED" -> GatewayStatus.REVERSED;
            default -> GatewayStatus.UNKNOWN;
        };
        String txnId = text(b, "txnId");
        String eventId = b.hasNonNull("eventId") ? text(b, "eventId") : txnId + ":" + st;
        return new NormalizedWebhookEvent(eventId, "upi.callback." + lower(st), status, txnId,
                uuid(b.path("merchantTxnRef")), rupeesToPaise(text(b, "amount")), "INR", null, null, null);
    }

    /** Gateway-neutral flat format; {@code amount} is in paise. */
    private NormalizedWebhookEvent generic(JsonNode b) {
        GatewayStatus status = GatewayStatus.parse(text(b, "status"));
        Long amount = longOrNull(b, "amount");
        return new NormalizedWebhookEvent(text(b, "event_id"), b.hasNonNull("event_type") ? text(b, "event_type")
                : "payment." + lower(status.name()), status, text(b, "gateway_reference"), uuid(b.path("transaction_id")),
                status == GatewayStatus.REFUNDED ? null : amount, upper(text(b, "currency")),
                status == GatewayStatus.REFUNDED ? amount : null, text(b, "refund_id"), text(b, "settlement_batch_id"));
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static Long longOrNull(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        if (v.isIntegralNumber()) return v.asLong();
        try {
            return Long.parseLong(v.asText().trim());
        } catch (NumberFormatException e) {
            throw new MalformedWebhookException(field + " must be an integer amount in paise");
        }
    }

    /** Converts a decimal rupee string ("500.00") to paise exactly (A6.2: no floating point). */
    static Long rupeesToPaise(String rupees) {
        if (rupees == null || rupees.isBlank()) return null;
        try {
            return new BigDecimal(rupees.trim()).movePointRight(2).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new MalformedWebhookException("invalid amount: " + rupees);
        }
    }

    private static UUID uuid(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        try {
            return UUID.fromString(n.asText());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
