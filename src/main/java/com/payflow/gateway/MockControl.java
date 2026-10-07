package com.payflow.gateway;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mock gateway control interface (spec B4.3). The test harness drives failure
 * conditions through request headers:
 *
 * <pre>
 * X-Mock-Response:     success | timeout | server-error | decline | rate-limit | pending
 * X-Mock-Delay-Ms:     2000
 * X-Mock-Gateway-Down: true
 * </pre>
 *
 * <p>Extensions so a single request can make <em>one</em> gateway fail while the
 * alternate succeeds (needed for FS-01, FS-04, FS-07):</p>
 * <ul>
 *   <li>per-gateway / per-operation values: {@code X-Mock-Response: razorpay=timeout, payu.capture=server-error}</li>
 *   <li>{@code X-Mock-Gateway: razorpay} scopes bare values to one gateway</li>
 *   <li>{@code X-Mock-Operation: capture} scopes bare values to one operation (auth, capture, refund, void)</li>
 *   <li>{@code X-Mock-Gateway-Down: razorpay,payu} takes specific gateways down</li>
 *   <li>{@code X-Mock-Retry-After: 2} seconds returned with {@code rate-limit}</li>
 * </ul>
 */
public final class MockControl {

    public enum Mode { SUCCESS, TIMEOUT, SERVER_ERROR, DECLINE, RATE_LIMIT, PENDING }

    public static final Set<String> OPERATIONS = Set.of("auth", "capture", "refund", "void", "status");

    private static final ThreadLocal<MockControl> CURRENT = new ThreadLocal<>();
    private static final MockControl NONE = new MockControl(Map.of(), Map.of(), Set.of(), false, 1000);

    private final Map<String, Mode> modes;
    private final Map<String, Long> delays;
    private final Set<String> downGateways;
    private final boolean allDown;
    private final long retryAfterMs;

    private MockControl(Map<String, Mode> modes, Map<String, Long> delays, Set<String> downGateways,
                        boolean allDown, long retryAfterMs) {
        this.modes = modes;
        this.delays = delays;
        this.downGateways = downGateways;
        this.allDown = allDown;
        this.retryAfterMs = retryAfterMs;
    }

    public static MockControl none() {
        return NONE;
    }

    public static MockControl current() {
        MockControl c = CURRENT.get();
        return c == null ? NONE : c;
    }

    public static void bind(MockControl control) {
        CURRENT.set(control);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public boolean isEmpty() {
        return modes.isEmpty() && delays.isEmpty() && downGateways.isEmpty() && !allDown;
    }

    /** Parses the harness headers (header names are case-insensitive). */
    public static MockControl fromHeaders(Map<String, String> headers) {
        Map<String, String> h = new HashMap<>();
        headers.forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v));
        String scopeGateway = lower(h.get("x-mock-gateway"));
        String scopeOperation = lower(h.get("x-mock-operation"));
        String bareSelector = scopeGateway == null ? scopeOperation
                : scopeOperation == null ? scopeGateway : scopeGateway + "." + scopeOperation;

        Map<String, Mode> modes = new HashMap<>();
        parseEntries(h.get("x-mock-response"), bareSelector).forEach((selector, value) -> {
            Mode mode = parseMode(value);
            if (mode != null) modes.put(selector, mode);
        });

        Map<String, Long> delays = new HashMap<>();
        parseEntries(h.get("x-mock-delay-ms"), bareSelector).forEach((selector, value) -> {
            long ms = parseLong(value);
            if (ms > 0) delays.put(selector, ms);
        });

        boolean allDown = false;
        Set<String> down = new java.util.HashSet<>();
        String downHeader = h.get("x-mock-gateway-down");
        if (downHeader != null) {
            for (String part : downHeader.split(",")) {
                String p = part.trim().toLowerCase(Locale.ROOT);
                if (p.isEmpty() || p.equals("false")) continue;
                if (p.equals("true")) {
                    if (scopeGateway == null) {
                        allDown = true;
                    } else {
                        down.add(scopeGateway);
                    }
                } else if (p.contains("=")) {
                    String[] kv = p.split("=", 2);
                    if (Boolean.parseBoolean(kv[1].trim())) down.add(kv[0].trim());
                } else {
                    down.add(p);
                }
            }
        }
        long retryAfter = parseLong(h.get("x-mock-retry-after"));
        return new MockControl(Map.copyOf(modes), Map.copyOf(delays), Set.copyOf(down), allDown,
                retryAfter > 0 ? retryAfter * 1000 : 1000);
    }

    /** Most specific match wins: gateway.operation, gateway, operation, global. */
    public Mode modeFor(String gateway, String operation) {
        return resolve(modes, gateway, operation);
    }

    public long delayMsFor(String gateway, String operation) {
        Long v = resolve(delays, gateway, operation);
        return v == null ? 0 : v;
    }

    public boolean isDown(String gateway) {
        return allDown || downGateways.contains(gateway);
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }

    private static <T> T resolve(Map<String, T> map, String gateway, String operation) {
        T v = map.get(gateway + "." + operation);
        if (v == null) v = map.get(gateway);
        if (v == null) v = map.get(operation);
        if (v == null) v = map.get("*");
        return v;
    }

    private static Map<String, String> parseEntries(String header, String bareSelector) {
        Map<String, String> out = new HashMap<>();
        if (header == null || header.isBlank()) return out;
        for (String part : header.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            if (p.contains("=")) {
                String[] kv = p.split("=", 2);
                out.put(kv[0].trim().toLowerCase(Locale.ROOT), kv[1].trim());
            } else {
                out.put(bareSelector == null ? "*" : bareSelector, p);
            }
        }
        return out;
    }

    private static Mode parseMode(String value) {
        if (value == null) return null;
        try {
            return Mode.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String lower(String v) {
        return v == null || v.isBlank() ? null : v.trim().toLowerCase(Locale.ROOT);
    }

    private static long parseLong(String v) {
        try {
            return v == null ? 0 : Math.max(0, Long.parseLong(v.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
