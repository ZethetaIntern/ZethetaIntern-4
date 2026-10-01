package com.payflow.web;

import com.payflow.config.PayFlowProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** API-key authentication middleware. Webhook paths are exempt (HMAC-verified). */
@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    private final PayFlowProperties props;
    private final com.payflow.gateway.MockControlContext mockControl;

    public ApiKeyFilter(PayFlowProperties props, com.payflow.gateway.MockControlContext mockControl) {
        this.props = props;
        this.mockControl = mockControl;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        // Webhooks authenticate via HMAC; health and API-docs are public.
        if (path.startsWith("/webhooks") || path.startsWith("/api/v1/webhooks") || path.equals("/health")
                || path.equals("/api/v1/health")
                || path.startsWith("/v3/api-docs") || path.startsWith("/v3/api-docs/")
                || path.startsWith("/swagger-ui") || path.startsWith("/h2-console")) {
            chain.doFilter(request, response);
            return;
        }
        // Mock gateway control headers (spec B4.3) are read by the gateway adapter.
        mockControl.set(com.payflow.gateway.MockControl.fromHeaders(headersOf(request)));
        String key = request.getHeader("X-API-Key");
        if (key == null || !MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8),
                props.apiKey().getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"invalid or missing X-API-Key\"}}");
            return;
        }
        chain.doFilter(request, response);
    }

    private java.util.Map<String, String> headersOf(HttpServletRequest request) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        java.util.Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            map.put(name, request.getHeader(name));
        }
        return map;
    }
}
