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

    public ApiKeyFilter(PayFlowProperties props) {
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.startsWith("/webhooks") || path.equals("/health") || path.startsWith("/h2-console")) {
            chain.doFilter(request, response);
            return;
        }
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
}
