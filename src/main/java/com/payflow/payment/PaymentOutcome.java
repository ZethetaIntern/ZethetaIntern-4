package com.payflow.payment;

import com.payflow.entity.Transaction;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Result of an orchestration step: the transaction as it now stands, the HTTP
 * status the API should answer with, and a translated error when the outcome
 * is a failure (A7.2).
 */
public record PaymentOutcome(Transaction transaction, HttpStatus status, String errorCode, String errorMessage,
                             Map<String, Object> errorDetails) {

    public static PaymentOutcome ok(Transaction t, HttpStatus status) {
        return new PaymentOutcome(t, status, null, null, Map.of());
    }

    public static PaymentOutcome error(Transaction t, HttpStatus status, String code, String message,
                                       Map<String, Object> details) {
        return new PaymentOutcome(t, status, code, message, details);
    }

    public boolean isError() {
        return errorCode != null;
    }
}
