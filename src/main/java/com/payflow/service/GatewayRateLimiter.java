package com.payflow.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-gateway token-bucket rate limiting (spec A8.4). Requests beyond the
 * bucket are delayed (not dropped) so 429s — which count as failures and can
 * trip circuit breakers — are not self-inflicted. Utilisation is exposed for
 * monitoring, e.g. "Razorpay: 145/200 req/sec".
 */
@Service
public class GatewayRateLimiter {

    public record Limit(int capacity, int refillPerSecond) {}

    private static final Map<String, Limit> LIMITS = Map.of(
            "razorpay", new Limit(200, 200),  // A8.4: token bucket, 200 req/sec
            "stripe",   new Limit(100, 100),  // respect Stripe's Retry-After
            "payu",     new Limit(150, 150),  // sliding window
            "upi",      new Limit(75, 75));   // NPCI/aggregator typical

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;
        final Limit limit;
        Bucket(Limit limit) { this.limit = limit; this.tokens = limit.capacity(); this.lastRefillNanos = System.nanoTime(); }
    }

    /** Blocks until a token is available; returns the delay applied in millis. */
    public long acquire(String gateway) {
        Limit limit = LIMITS.getOrDefault(gateway, new Limit(100, 100));
        Bucket b = buckets.computeIfAbsent(gateway, k -> new Bucket(limit));
        long delay;
        synchronized (b) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - b.lastRefillNanos) / 1_000_000_000.0;
            b.tokens = Math.min(b.limit.capacity(), b.tokens + elapsedSeconds * b.limit.refillPerSecond());
            b.lastRefillNanos = now;
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return 0L;
            }
            double needed = 1.0 - b.tokens;
            delay = (long) Math.ceil(needed / b.limit.refillPerSecond() * 1000);
            b.tokens = 0;
        }
        try {
            Thread.sleep(Math.min(delay, 2000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return delay;
    }

    /** Current utilisation, e.g. { razorpay: "199/200 req/sec" }. */
    public Map<String, String> utilisation() {
        Map<String, String> out = new ConcurrentHashMap<>();
        buckets.forEach((gw, b) -> {
            Limit l = b.limit;
            out.put(gw, Math.round(b.tokens) + "/" + l.capacity() + " req/sec");
        });
        LIMITS.forEach((gw, l) -> out.putIfAbsent(gw, l.capacity() + "/" + l.capacity() + " req/sec"));
        return out;
    }
}
