package com.payflow.tracing;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Distributed tracing context (spec A8.5). Values live in the SLF4J MDC, so
 * every structured log line carries {@code trace_id} as a top-level field, and
 * they are copied into audit metadata and outbound gateway headers.
 */
public final class TraceContext {

    public static final String TRACE_ID = "trace_id";
    public static final String REQUEST_ID = "request_id";
    public static final String TRANSACTION_ID = "transaction_id";
    public static final String CLIENT_IP = "client_ip";
    public static final String USER_AGENT = "user_agent";
    public static final String COMPONENT = "component";

    /** Header used to propagate the trace id to gateways and accept it from callers. */
    public static final String TRACE_HEADER = "X-Trace-Id";

    private TraceContext() {}

    public static String traceId() {
        String id = MDC.get(TRACE_ID);
        if (id == null) {
            id = UUID.randomUUID().toString();
            MDC.put(TRACE_ID, id);
        }
        return id;
    }

    public static UUID traceUuid() {
        try {
            return UUID.fromString(traceId());
        } catch (IllegalArgumentException e) {
            UUID fresh = UUID.randomUUID();
            MDC.put(TRACE_ID, fresh.toString());
            return fresh;
        }
    }

    public static String requestId() {
        String id = MDC.get(REQUEST_ID);
        return id == null ? "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) : id;
    }

    public static String clientIp() {
        return MDC.get(CLIENT_IP);
    }

    /** Correlates subsequent log lines with a payment (A8.5 "Correlation"). */
    public static void bindTransaction(UUID transactionId, UUID traceId) {
        if (transactionId != null) MDC.put(TRANSACTION_ID, transactionId.toString());
        if (traceId != null) MDC.put(TRACE_ID, traceId.toString());
    }

    /** Context copied into every audit row's metadata column. */
    public static Map<String, Object> auditMetadata() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(TRACE_ID, traceId());
        putIfPresent(m, REQUEST_ID, MDC.get(REQUEST_ID));
        putIfPresent(m, "ip", MDC.get(CLIENT_IP));
        putIfPresent(m, USER_AGENT, MDC.get(USER_AGENT));
        return m;
    }

    private static void putIfPresent(Map<String, Object> m, String k, String v) {
        if (v != null) m.put(k, v);
    }

    /** Snapshot for handing the context to a worker thread. */
    public static Map<String, String> capture() {
        Map<String, String> copy = MDC.getCopyOfContextMap();
        return copy == null ? Map.of() : copy;
    }

    public static void restore(Map<String, String> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(snapshot);
        }
    }

    /** Runs a background job iteration with a fresh trace id. */
    public static void runWithNewTrace(String component, Runnable task) {
        Map<String, String> previous = capture();
        try {
            MDC.clear();
            MDC.put(TRACE_ID, UUID.randomUUID().toString());
            MDC.put(COMPONENT, component);
            task.run();
        } finally {
            restore(previous);
        }
    }
}
