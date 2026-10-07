package com.payflow.error;

import com.payflow.statemachine.InvalidStateTransitionException;
import com.payflow.statemachine.TransactionStateMachine;
import java.sql.SQLTransientConnectionException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Maps any exception to the A7.2 error shape; shared by the exception handler and the idempotency cache. */
public final class ErrorMapper {

    private ErrorMapper() {}

    /** A rendered error: HTTP status plus A7.2 fields. */
    public record Mapped(HttpStatus status, String code, String message, Map<String, Object> details) {
        public Map<String, Object> body() {
            return ErrorResponse.body(code, message, details);
        }
    }

    public static Mapped map(Throwable e) {
        if (e instanceof ApiException a) {
            return new Mapped(a.getStatus(), a.getCode(), a.getMessage(), a.getDetails());
        }
        if (e instanceof InvalidStateTransitionException s) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("transaction_id", String.valueOf(s.getTransactionId()));
            d.put("current_state", s.getFrom().name());
            d.put("attempted_state", s.getTo().name());
            d.put("valid_transitions", s.getValidTransitions().stream().map(Enum::name).sorted().toList());
            return new Mapped(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", s.getMessage(), d);
        }
        if (e instanceof TransactionStateMachine.TransactionNotFoundException) {
            return new Mapped(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), Map.of());
        }
        if (e instanceof MethodArgumentNotValidException v) {
            Map<String, Object> fields = new LinkedHashMap<>();
            v.getBindingResult().getFieldErrors().forEach(f -> fields.put(snake(f.getField()), f.getDefaultMessage()));
            return new Mapped(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request validation failed",
                    Map.of("fields", fields));
        }
        if (e instanceof HandlerMethodValidationException v) {
            return new Mapped(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request validation failed",
                    Map.of("errors", v.getAllErrors().stream().map(x -> String.valueOf(x.getDefaultMessage())).toList()));
        }
        if (e instanceof jakarta.validation.ConstraintViolationException v) {
            return new Mapped(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "request validation failed",
                    Map.of("errors", v.getConstraintViolations().stream()
                            .map(x -> x.getPropertyPath() + ": " + x.getMessage()).toList()));
        }
        if (e instanceof HttpMessageNotReadableException) {
            return new Mapped(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "request body is missing or not valid JSON",
                    Map.of());
        }
        if (e instanceof MissingRequestHeaderException h) {
            return new Mapped(HttpStatus.BAD_REQUEST, "MISSING_HEADER", "required header " + h.getHeaderName()
                    + " is missing", Map.of("header", h.getHeaderName()));
        }
        if (e instanceof MissingServletRequestParameterException p) {
            return new Mapped(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", "required parameter "
                    + p.getParameterName() + " is missing", Map.of("parameter", p.getParameterName()));
        }
        if (e instanceof MethodArgumentTypeMismatchException m) {
            return new Mapped(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "parameter " + m.getName()
                    + " has an invalid value", Map.of("parameter", m.getName()));
        }
        if (isDatabaseUnavailable(e)) {
            return new Mapped(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                    "The service is temporarily overloaded. Please retry shortly.", Map.of("retry_after_seconds", 2));
        }
        if (e instanceof DataIntegrityViolationException) {
            return new Mapped(HttpStatus.CONFLICT, "CONFLICT", "the request conflicts with the current state of the resource",
                    Map.of());
        }
        if (e instanceof IllegalArgumentException) {
            return new Mapped(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage(), Map.of());
        }
        return new Mapped(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. The request can be retried safely with the same Idempotency-Key.",
                Map.of());
    }

    /** Connection-pool exhaustion or an unreachable database (FS-14, C5). */
    public static boolean isDatabaseUnavailable(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof CannotGetJdbcConnectionException || c instanceof SQLTransientConnectionException
                    || c instanceof CannotCreateTransactionException
                    || c instanceof org.hibernate.exception.JDBCConnectionException) {
                return true;
            }
            if (c.getCause() == c) break;
        }
        return false;
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }
}
