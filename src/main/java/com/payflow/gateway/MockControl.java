package com.payflow.gateway;

/**
 * Mock gateway control interface (spec B4.3). The automated test harness drives
 * failure conditions through request headers, which the payment flow copies
 * into a thread-local for the duration of the call:
 *
 * <pre>
 * X-Mock-Response: success | timeout | server-error | decline | rate-limit
 * X-Mock-Delay-Ms: &lt;milliseconds&gt;
 * X-Mock-Gateway-Down: true
 * </pre>
 */
public final class MockControl {

    public enum Mode { SUCCESS, TIMEOUT, SERVER_ERROR, DECLINE, RATE_LIMIT, PARTIAL }

    private static final ThreadLocal<MockControl> CURRENT = new ThreadLocal<>();

    private final Mode forced;
    private final long delayMs;
    private final boolean gatewayDown;

    private MockControl(Mode forced, long delayMs, boolean gatewayDown) {
        this.forced = forced;
        this.delayMs = delayMs;
        this.gatewayDown = gatewayDown;
    }

    /** Default behaviour (no harness headers): deterministic simulation. */
    public static MockControl current() {
        MockControl c = CURRENT.get();
        return c == null ? new MockControl(null, 0, false) : c;
    }

    /** Parse the harness headers; unknown/absent values fall back to simulation. */
    public static MockControl fromHeaders(java.util.Map<String, String> headers) {
        String response = header(headers, "X-Mock-Response");
        String delay = header(headers, "X-Mock-Delay-Ms");
        String down = header(headers, "X-Mock-Gateway-Down");
        Mode mode = null;
        if (response != null) {
            try {
                mode = Mode.valueOf(response.trim().toUpperCase().replace('-', '_'));
            } catch (IllegalArgumentException ignored) {
                // PARTIAL is capture-only; treat unknown values as unforced
                if (!"partial".equalsIgnoreCase(response)) mode = null;
                else mode = Mode.PARTIAL;
            }
        }
        return new MockControl(mode, parseLong(delay), Boolean.parseBoolean(down));
    }

    /** Bind control for the current thread; caller must clear it afterwards. */
    public static void bind(MockControl control) {
        CURRENT.set(control);
    }

    public static void clear() {
        CURRENT.remove();
    }

    private static String header(java.util.Map<String, String> headers, String name) {
        for (var e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    private static long parseLong(String v) {
        try {
            return v == null ? 0 : Math.max(0, Long.parseLong(v.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public Mode forced() { return forced; }
    public long delayMs() { return delayMs; }
    public boolean gatewayDown() { return gatewayDown; }
}
