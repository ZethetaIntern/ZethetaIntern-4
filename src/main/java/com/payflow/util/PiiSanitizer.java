package com.payflow.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PII sanitisation for logs and the audit trail (A2.3 "PII redacted", Day 3-4).
 * Card numbers, CVVs, UPI handles, e-mails, phone numbers and account numbers
 * never reach {@code transaction_state_log.gateway_response} or log output.
 */
public final class PiiSanitizer {

    private PiiSanitizer() {}

    public static final String REDACTED = "[REDACTED]";

    private static final Pattern CARD = Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,19}(?!\\d)");
    private static final Pattern CVV = Pattern.compile("(?i)\\b(cvv|cvc|cvv2)\\b\\s*[:=]?\\s*\\d{3,4}\\b");
    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b");
    private static final Pattern UPI = Pattern.compile("\\b[a-zA-Z0-9._-]{2,}@[a-zA-Z]{2,}\\b");
    private static final Pattern ACCOUNT =
            Pattern.compile("(?i)\\b(acc(ount)?(\\s*no|\\s*number)?)\\b\\s*[:=]?\\s*[\\dX-]{6,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?91[-\\s]?)?[6-9]\\d{9}(?!\\d)");

    /** Keys whose values are always removed, whatever they contain. */
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "card", "card_number", "cardnumber", "number", "pan", "cvv", "cvc", "expiry", "exp_month",
            "exp_year", "vpa", "upi_id", "payer_vpa", "email", "contact", "phone", "mobile",
            "account_number", "bank_account", "name_on_card", "cardholder_name", "password", "token");

    /** Redacts sensitive values, keeping non-sensitive text readable for debugging. */
    public static String sanitize(String input) {
        if (input == null) return null;
        String out = CARD.matcher(input).replaceAll("[CARD_REDACTED]");
        out = CVV.matcher(out).replaceAll("cvv=[CVV_REDACTED]");
        out = EMAIL.matcher(out).replaceAll("[EMAIL_REDACTED]");
        out = UPI.matcher(out).replaceAll("[UPI_REDACTED]");
        out = ACCOUNT.matcher(out).replaceAll("account=[ACCOUNT_REDACTED]");
        out = PHONE.matcher(out).replaceAll("[PHONE_REDACTED]");
        return out;
    }

    /** Deep-sanitises a JSON-like structure (maps, lists, strings) before it is persisted. */
    public static Map<String, Object> sanitize(Map<String, ?> input) {
        if (input == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        input.forEach((k, v) -> out.put(k, isSensitiveKey(k) ? REDACTED : sanitizeValue(v)));
        return out;
    }

    private static Object sanitizeValue(Object v) {
        if (v instanceof String s) return sanitize(s);
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> nested = new LinkedHashMap<>();
            m.forEach((k, val) -> nested.put(String.valueOf(k), isSensitiveKey(String.valueOf(k))
                    ? REDACTED : sanitizeValue(val)));
            return nested;
        }
        if (v instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(sanitizeValue(item)));
            return copy;
        }
        return v;
    }

    private static boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }

    /** Masks all but the last 4 digits; useful for support-visible references. */
    public static String maskCard(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) return "[CARD_REDACTED]";
        return "************" + cardNumber.substring(cardNumber.length() - 4);
    }
}
