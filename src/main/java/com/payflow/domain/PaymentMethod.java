package com.payflow.domain;

import java.util.Locale;

/** Payment methods accepted by PayFlow Commerce (spec B1.1). */
public enum PaymentMethod {
    CARD, UPI, NETBANKING, WALLET;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PaymentMethod parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("payment_method is required");
        String v = raw.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "");
        return switch (v) {
            case "CARD", "CREDITCARD", "DEBITCARD" -> CARD;
            case "UPI" -> UPI;
            case "NETBANKING", "NB" -> NETBANKING;
            case "WALLET" -> WALLET;
            default -> throw new IllegalArgumentException("unsupported payment_method: " + raw);
        };
    }
}
