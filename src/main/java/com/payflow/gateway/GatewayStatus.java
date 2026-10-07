package com.payflow.gateway;

import java.util.Locale;

/** A gateway's view of a payment, normalised across Razorpay / Stripe / PayU / UPI vocabularies. */
public enum GatewayStatus {
    PENDING, AUTHORISED, CAPTURED, FAILED, EXPIRED, VOIDED, REFUNDED, REFUND_FAILED,
    REVERSED, SETTLED, DISPUTE_OPENED, DISPUTE_RESOLVED, UNKNOWN;

    /** Lenient parse used for the generic webhook format and admin/mock endpoints. */
    public static GatewayStatus parse(String raw) {
        if (raw == null) return UNKNOWN;
        return switch (raw.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "pending", "created", "requires_action", "processing" -> PENDING;
            case "authorized", "authorised", "auth", "requires_capture" -> AUTHORISED;
            case "captured", "succeeded", "success", "paid" -> CAPTURED;
            case "failed", "failure", "declined" -> FAILED;
            case "expired" -> EXPIRED;
            case "voided", "canceled", "cancelled" -> VOIDED;
            case "refunded" -> REFUNDED;
            case "refund_failed" -> REFUND_FAILED;
            case "reversed", "chargeback" -> REVERSED;
            case "settled" -> SETTLED;
            case "dispute_opened", "disputed" -> DISPUTE_OPENED;
            case "dispute_resolved", "dispute_closed" -> DISPUTE_RESOLVED;
            default -> UNKNOWN;
        };
    }
}
