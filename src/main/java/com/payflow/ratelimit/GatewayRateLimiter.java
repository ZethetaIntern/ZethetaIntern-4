package com.payflow.ratelimit;

import com.payflow.entity.GatewayConfig;
import com.payflow.routing.GatewayConfigService;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/**
 * Outbound, per-gateway rate limiting (spec A8.4). Staying under each gateway's
 * limit avoids self-inflicted 429s, which would otherwise count as failures.
 *
 * <ul>
 *   <li>{@code TOKEN_BUCKET} (Razorpay, UPI): capacity = limit, refilled continuously;
 *       a request waits briefly for a token (queued, not dropped)</li>
 *   <li>{@code SLIDING_WINDOW} (PayU): at most {@code limit} requests in any 1 s window;
 *       excess requests are delayed until the window frees up</li>
 *   <li>{@code RETRY_AFTER_BACKOFF} (Stripe): token bucket plus, after a 429, the gateway is
 *       paused for {@code max(Retry-After, exponential backoff with jitter)}</li>
 * </ul>
 * Every strategy honours a gateway's Retry-After. Limits come from {@code gateway_config}.
 */
@Service
public class GatewayRateLimiter {

    /** Outcome of asking to send one request. */
    public record Admission(boolean admitted, long retryAfterMs, long waitedMs) {
        static Admission ok(long waited) {
            return new Admission(true, 0, waited);
        }

        static Admission throttled(long retryAfter) {
            return new Admission(false, retryAfter, 0);
        }
    }

    private static final class Limiter {
        final Object lock = new Object();
        int limit;
        GatewayConfig.RateLimitStrategy strategy;
        double tokens;
        long lastRefillNanos = System.nanoTime();
        final Deque<Long> window = new ArrayDeque<>();
        final Deque<Long> admittedLastSecond = new ArrayDeque<>();
        long blockedUntilMillis;
        int consecutive429;
    }

    private final GatewayConfigService gatewayConfigs;
    private final Map<String, Limiter> limiters = new ConcurrentHashMap<>();

    public GatewayRateLimiter(GatewayConfigService gatewayConfigs) {
        this.gatewayConfigs = gatewayConfigs;
    }

    /**
     * Admits a request, waiting at most {@code maxWaitMs} for capacity. Returns a
     * throttled admission (with how long to back off) when capacity does not free
     * up in time or the gateway asked us to back off.
     */
    public Admission tryAcquire(String gateway, long maxWaitMs) {
        Limiter l = limiter(gateway);
        long start = System.currentTimeMillis();
        while (true) {
            long waitMs;
            synchronized (l.lock) {
                long now = System.currentTimeMillis();
                if (now < l.blockedUntilMillis) {
                    return Admission.throttled(l.blockedUntilMillis - now);
                }
                waitMs = switch (l.strategy) {
                    case SLIDING_WINDOW -> slidingWindowWait(l, now);
                    case TOKEN_BUCKET, RETRY_AFTER_BACKOFF -> tokenBucketWait(l);
                };
                if (waitMs == 0) {
                    recordAdmitted(l, now);
                    return Admission.ok(now - start);
                }
            }
            long elapsed = System.currentTimeMillis() - start;
            if (elapsed + waitMs > maxWaitMs) {
                return Admission.throttled(waitMs);
            }
            try {
                Thread.sleep(Math.max(1, waitMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Admission.throttled(waitMs);
            }
        }
    }

    /** A gateway answered 429: pause it for Retry-After (and exponential backoff with jitter). */
    public long onRateLimited(String gateway, long retryAfterMs) {
        Limiter l = limiter(gateway);
        synchronized (l.lock) {
            l.consecutive429++;
            long backoff = 0;
            if (l.strategy == GatewayConfig.RateLimitStrategy.RETRY_AFTER_BACKOFF) {
                long base = 250L * (1L << Math.min(6, l.consecutive429 - 1));
                backoff = base + ThreadLocalRandom.current().nextLong(base / 2 + 1);
            }
            long pause = Math.max(retryAfterMs, backoff);
            l.blockedUntilMillis = Math.max(l.blockedUntilMillis, System.currentTimeMillis() + pause);
            return pause;
        }
    }

    public void onSuccess(String gateway) {
        Limiter l = limiters.get(gateway);
        if (l != null) {
            synchronized (l.lock) {
                l.consecutive429 = 0;
            }
        }
    }

    /** Monitoring view, e.g. {@code razorpay -> "145/200 req/sec"}. */
    public Map<String, Map<String, Object>> utilisation() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (GatewayConfig g : gatewayConfigs.all()) {
            Limiter l = limiter(g.getGatewayName());
            synchronized (l.lock) {
                long now = System.currentTimeMillis();
                prune(l.admittedLastSecond, now - 1000);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("utilisation", l.admittedLastSecond.size() + "/" + l.limit + " req/sec");
                m.put("current_rps", l.admittedLastSecond.size());
                m.put("limit_rps", l.limit);
                m.put("strategy", l.strategy.name());
                m.put("throttled", now < l.blockedUntilMillis);
                m.put("blocked_for_ms", Math.max(0, l.blockedUntilMillis - now));
                out.put(g.getGatewayName(), m);
            }
        }
        return out;
    }

    /** Clears throttling state (harness reset between scenarios). */
    public void reset() {
        limiters.clear();
    }

    private Limiter limiter(String gateway) {
        Limiter l = limiters.computeIfAbsent(gateway, g -> new Limiter());
        GatewayConfig cfg = gatewayConfigs.find(gateway).orElse(null);
        synchronized (l.lock) {
            int limit = cfg == null ? 100 : cfg.getRateLimitPerSec();
            GatewayConfig.RateLimitStrategy strategy = cfg == null
                    ? GatewayConfig.RateLimitStrategy.TOKEN_BUCKET : cfg.getRateLimitStrategy();
            if (l.strategy == null || l.limit != limit) {
                l.tokens = limit;
            }
            l.limit = limit;
            l.strategy = strategy;
        }
        return l;
    }

    private static long tokenBucketWait(Limiter l) {
        long now = System.nanoTime();
        double elapsedSec = (now - l.lastRefillNanos) / 1_000_000_000.0;
        l.tokens = Math.min(l.limit, l.tokens + elapsedSec * l.limit);
        l.lastRefillNanos = now;
        if (l.tokens >= 1.0) {
            l.tokens -= 1.0;
            return 0;
        }
        return (long) Math.ceil((1.0 - l.tokens) * 1000.0 / l.limit);
    }

    private static long slidingWindowWait(Limiter l, long nowMs) {
        prune(l.window, nowMs - 1000);
        if (l.window.size() < l.limit) {
            l.window.addLast(nowMs);
            return 0;
        }
        return Math.max(1, l.window.peekFirst() + 1000 - nowMs);
    }

    private static void recordAdmitted(Limiter l, long nowMs) {
        l.admittedLastSecond.addLast(nowMs);
        prune(l.admittedLastSecond, nowMs - 1000);
    }

    private static void prune(Deque<Long> q, long olderThan) {
        while (!q.isEmpty() && q.peekFirst() <= olderThan) q.pollFirst();
    }
}
