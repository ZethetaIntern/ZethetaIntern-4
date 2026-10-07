package com.payflow.error;

import com.payflow.tracing.TraceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standard error body (spec A7.2):
 * <pre>
 * {"error": {"code": "PAYMENT_AUTH_FAILED", "message": "...", "details": {...},
 *            "request_id": "req_a1b2c3d4e5f6", "timestamp": "2025-03-19T10:30:00.000Z"}}
 * </pre>
 */
public final class ErrorResponse {

    private ErrorResponse() {}

    public static Map<String, Object> body(String code, String message, Map<String, ?> details) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("details", details == null ? Map.of() : details);
        error.put("request_id", TraceContext.requestId());
        error.put("trace_id", TraceContext.traceId());
        error.put("timestamp", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("error", error);
        return root;
    }
}
