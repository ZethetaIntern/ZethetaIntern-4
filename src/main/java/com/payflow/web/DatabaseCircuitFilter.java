package com.payflow.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payflow.db.DatabaseGuard;
import com.payflow.error.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Internal circuit breaker on the database (FS-14, C5.4): while the database
 * breaker is open, requests fail fast with 503 + Retry-After rather than piling
 * up behind an exhausted connection pool. The health endpoint is always served.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DatabaseCircuitFilter extends OncePerRequestFilter {

    private final DatabaseGuard guard;
    private final ObjectMapper mapper;

    public DatabaseCircuitFilter(DatabaseGuard guard, ObjectMapper mapper) {
        this.guard = guard;
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || path.equals("/api/v1/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!guard.allowRequest()) {
            response.setStatus(503);
            response.setHeader("Retry-After", "2");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ErrorResponse.body("SERVICE_UNAVAILABLE",
                    "The service is temporarily overloaded. Please retry shortly.",
                    Map.of("reason", "database_circuit_open", "retry_after_seconds", 2)));
            return;
        }
        chain.doFilter(request, response);
        if (response.getStatus() != 503) guard.onSuccess();
    }
}
