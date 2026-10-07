package com.payflow.web;

import com.payflow.gateway.MockControl;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the B4.3 mock-gateway control headers ({@code X-Mock-*}) to the request
 * thread; {@link com.payflow.gateway.GatewayCaller} carries them onto the
 * gateway worker threads.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class MockControlFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Map<String, String> mockHeaders = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (name.regionMatches(true, 0, "X-Mock-", 0, 7)) mockHeaders.put(name, request.getHeader(name));
        }
        if (mockHeaders.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        MockControl.bind(MockControl.fromHeaders(mockHeaders));
        try {
            chain.doFilter(request, response);
        } finally {
            MockControl.clear();
        }
    }
}
