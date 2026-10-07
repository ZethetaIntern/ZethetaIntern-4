package com.payflow.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.config.PayFlowProperties;
import com.payflow.entity.SecurityAuditLog;
import com.payflow.error.ErrorResponse;
import com.payflow.security.SecurityAuditService;
import com.payflow.tracing.TraceFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * API-key authentication middleware (Day 11-12). Merchant APIs require
 * {@code X-API-Key} (or {@code Authorization: Bearer <key>}). Webhook receivers
 * authenticate by signature instead; the health check and API docs are public.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class ApiKeyFilter extends OncePerRequestFilter {

    private final PayFlowProperties props;
    private final SecurityAuditService security;
    private final ObjectMapper mapper;

    public ApiKeyFilter(PayFlowProperties props, SecurityAuditService security, ObjectMapper mapper) {
        this.props = props;
        this.security = security;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.startsWith("/api/v1/webhooks/") || path.equals("/api/v1/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader("X-API-Key");
        String auth = request.getHeader("Authorization");
        if (key == null && auth != null && auth.startsWith("Bearer ")) key = auth.substring(7).trim();
        if (key != null && MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),
                props.apiKey().getBytes(StandardCharsets.UTF_8))) {
            chain.doFilter(request, response);
            return;
        }
        security.record(SecurityAuditLog.API_KEY_INVALID, null, TraceFilter.clientIp(request),
                request.getHeader("User-Agent"), request.getRequestURI(),
                Map.of("reason", key == null ? "missing_api_key" : "invalid_api_key"));
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ErrorResponse.body("UNAUTHORIZED",
                "A valid X-API-Key header is required.", Map.of()));
    }
}
