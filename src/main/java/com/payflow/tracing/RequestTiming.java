package com.payflow.tracing;

/**
 * Per-request timing for the B3 benchmark "payment initiation: time from API
 * request to gateway call initiated". The trace filter marks the start; the
 * orchestrator marks the first gateway call; the payment API reports both in a
 * standard {@code Server-Timing} response header.
 */
public final class RequestTiming {

    private static final ThreadLocal<long[]> TIMES = new ThreadLocal<>();

    private RequestTiming() {}

    public static void start() {
        TIMES.set(new long[]{System.nanoTime(), 0L});
    }

    /** Records the first gateway call of this request (later calls are ignored). */
    public static void markGatewayCallInitiated() {
        long[] t = TIMES.get();
        if (t != null && t[1] == 0L) t[1] = System.nanoTime();
    }

    /** {@code Server-Timing} value, e.g. {@code gateway_initiated;dur=12.4, app;dur=230.1}. */
    public static String serverTimingHeader() {
        long[] t = TIMES.get();
        if (t == null) return null;
        long now = System.nanoTime();
        StringBuilder sb = new StringBuilder();
        if (t[1] != 0L) sb.append("gateway_initiated;dur=").append(ms(t[1] - t[0])).append(", ");
        sb.append("app;dur=").append(ms(now - t[0]));
        return sb.toString();
    }

    public static void clear() {
        TIMES.remove();
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000.0);
    }
}
