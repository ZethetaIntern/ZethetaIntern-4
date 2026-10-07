package com.payflow.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * First filter in the chain: generates a UUID v4 trace id for each request
 * (or accepts {@code X-Trace-Id}), plus a short request id used in error
 * responses (A7.2), and records the client IP for audit and security logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = validUuid(request.getHeader(TraceContext.TRACE_HEADER));
        String requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        MDC.put(TraceContext.TRACE_ID, traceId);
        MDC.put(TraceContext.REQUEST_ID, requestId);
        MDC.put(TraceContext.CLIENT_IP, clientIp(request));
        String ua = request.getHeader("User-Agent");
        if (ua != null) MDC.put(TraceContext.USER_AGENT, ua.length() > 200 ? ua.substring(0, 200) : ua);
        response.setHeader(TraceContext.TRACE_HEADER, traceId);
        response.setHeader("X-Request-Id", requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private static String validUuid(String candidate) {
        if (candidate != null) {
            try {
                return UUID.fromString(candidate.trim()).toString();
            } catch (IllegalArgumentException ignored) {
                // not a UUID: issue our own
            }
        }
        return UUID.randomUUID().toString();
    }

    /** First hop of X-Forwarded-For when behind a proxy, else the socket address. */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
