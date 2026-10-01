package com.payflow.web;

import com.payflow.service.PaymentService;
import com.payflow.service.StateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

/** Standard error response format (spec A7.2). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public static Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of(
                "code", code, "message", message, "timestamp", Instant.now().toString()));
    }

    @ExceptionHandler(PaymentService.IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> idempotency(PaymentService.IdempotencyConflictException e) {
        return ResponseEntity.status(HttpStatus.OK).body(
                Map.of("replayed", true, "transaction_id", e.existingId));
    }

    @ExceptionHandler(StateService.IllegalTransitionException.class)
    public ResponseEntity<Map<String, Object>> illegalTransition(StateService.IllegalTransitionException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error("ILLEGAL_STATE_TRANSITION", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> notFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(org.springframework.web.bind.MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error("VALIDATION_FAILED", msg));
    }
}
