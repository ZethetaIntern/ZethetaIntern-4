package com.payflow.util;

import java.util.regex.Pattern;

/**
 * PII sanitisation for logs and audit records (Part D Day 3-4 deliverable).
 * Payment data must never leak card numbers, CVVs or UPI handles into logs.
 */
public final class PiiSanitizer {

    private PiiSanitizer() {}

    private static final Pattern CARD = Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,19}(?!\\d)");
    private static final Pattern CVV = Pattern.compile("(?i)\\b(cvv|cvc|cvcv)\\b\\s*[:=]?\\s*\\d{3,4}\\b");
    private static final Pattern UPI = Pattern.compile("\\b[a-zA-Z0-9._-]{2,}@[a-zA-Z]{2,}\\b");
    private static final Pattern EMAIL = Pattern.compile("\\b[\\w.%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}\\b");
    private static final Pattern ACCOUNT = Pattern.compile("(?i)\\b(acc(ount)?(\\s*no|\\s*number)?)\\b\\s*[:=]?\\s*[\\dX-]{6,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?91[-\\s]?)?[6-9]\\d{9}(?!\\d)");

    /** Redacts sensitive values, keeping non-sensitive text readable for debugging. */
    public static String sanitize(String input) {
        if (input == null) return null;
        String out = CARD.matcher(input).replaceAll("[CARD_REDACTED]");
        out = CVV.matcher(out).replaceAll("cvv=[CVV_REDACTED]");
        out = UPI.matcher(out).replaceAll("[UPI_REDACTED]");
        out = ACCOUNT.matcher(out).replaceAll("account=[ACCOUNT_REDACTED]");
        out = PHONE.matcher(out).replaceAll("[PHONE_REDACTED]");
        out = EMAIL.matcher(out).replaceAll("[EMAIL_REDACTED]");
        return out;
    }

    /** Masks all but the last 4 digits — useful for support-visible references. */
    public static String maskCard(String cardNumber) {
        if (cardNumber == null || cardNumber.length() < 4) return "[CARD_REDACTED]";
        return "************" + cardNumber.substring(cardNumber.length() - 4);
    }
}
