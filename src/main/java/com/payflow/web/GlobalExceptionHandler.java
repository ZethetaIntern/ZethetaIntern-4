package com.payflow.web;

import com.payflow.service.PaymentService;
import com.payflow.service.StateService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Standard error response format (spec A7.2). */
@RestControllerAdvice
public class GlobalExceptionHandler {

    public static Map<String, Object> error(String code, String message) {
        return error(code, message, Map.of());
    }

    /** A7.2 standard error format: code, message, details, request_id, timestamp. */
    public static Map<String, Object> error(String code, String message, Map<String, Object> details) {
        return Map.of("error", Map.of(
                "code", code,
                "message", message,
                "details", details,
                "request_id", RequestIdFilter.current(),
                "timestamp", Instant.now().toString()));
    }

    /** Per-request correlation id (spec A8.5 distributed tracing). */
    public static final class RequestIdFilter extends org.springframework.web.filter.OncePerRequestFilter {
        private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

        public static String current() {
            String id = CURRENT.get();
            return id == null ? "req_" + UUID.randomUUID().toString().substring(0, 8) : id;
        }

        @Override
        protected void doFilterInternal(jakarta.servlet.http.HttpServletRequest request,
                                        jakarta.servlet.http.HttpServletResponse response,
                                        jakarta.servlet.FilterChain chain)
                throws jakarta.servlet.ServletException, java.io.IOException {
            String incoming = request.getHeader("X-Request-Id");
            String id = (incoming == null || incoming.isBlank())
                    ? "req_" + UUID.randomUUID().toString().substring(0, 8) : incoming;
            CURRENT.set(id);
            response.setHeader("X-Request-Id", id);
            try {
                chain.doFilter(request, response);
            } finally {
                CURRENT.remove();
            }
        }
    }

    @ExceptionHandler(PaymentService.IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> idempotency(PaymentService.IdempotencyConflictException e) {
        if (!e.inProgress) {
            // Completed request replayed: 200 with the original transaction reference.
            return ResponseEntity.ok(Map.of("replayed", true, "transaction_id", e.existingId));
        }
        // FS-03/FS-09: a duplicate that is still in flight gets 409 Conflict.
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
                error("IDEMPOTENCY_CONFLICT",
                        "A request with this Idempotency-Key is already in progress.",
                        Map.of("transaction_id", e.existingId)));
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
