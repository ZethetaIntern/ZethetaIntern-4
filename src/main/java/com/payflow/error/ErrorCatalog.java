package com.payflow.error;

import com.payflow.domain.AttemptOutcome;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Translates gateway-specific failures into PayFlow's own error vocabulary
 * (A7.2: "do not return gateway-specific error messages directly"). The raw
 * gateway code and description are kept under {@code details} for support.
 */
public final class ErrorCatalog {

    private ErrorCatalog() {}

    /** A translated error: our code, a customer-safe message and a suggestion. */
    public record Translation(String code, String message, String suggestion) {}

    public static Translation forAuthFailure(AttemptOutcome kind, String gatewayCode) {
        if (kind == AttemptOutcome.DECLINED) {
            if (gatewayCode != null && (gatewayCode.equals("AUTH_EXPIRED"))) {
                return new Translation("PAYMENT_AUTH_EXPIRED", "The payment authorisation has expired.",
                        "Ask the customer to pay again.");
            }
            return new Translation("PAYMENT_AUTH_FAILED", "Payment authorisation was declined by the issuing bank.",
                    "Please try a different payment method or contact your bank.");
        }
        if (kind == AttemptOutcome.RATE_LIMITED) {
            return new Translation("GATEWAY_CAPACITY_EXCEEDED", "Payment gateways are temporarily at capacity.",
                    "The payment has been queued and will be retried automatically.");
        }
        return new Translation("GATEWAY_UNAVAILABLE", "No payment gateway could process the payment right now.",
                "Please retry in a few seconds; you will not be charged twice.");
    }

    public static Map<String, Object> details(String gateway, String gatewayCode, String gatewayDescription,
                                              String suggestion) {
        Map<String, Object> d = new LinkedHashMap<>();
        if (gateway != null) d.put("gateway", gateway);
        if (gatewayCode != null) d.put("gateway_error_code", gatewayCode);
        if (gatewayDescription != null) d.put("gateway_error_description", gatewayDescription);
        if (suggestion != null) d.put("suggestion", suggestion);
        return d;
    }
}
