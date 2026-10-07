package com.payflow.routing;

import com.payflow.domain.AttemptOutcome;
import com.payflow.entity.GatewayHistoricalPerformance;
import com.payflow.repository.GatewayHistoricalPerformanceRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Per-minute sliding window of gateway performance (A3.1 "per-minute sliding
 * window") fed by every authorisation attempt and every capture.
 *
 * <ul>
 *   <li>success rate = payments that reached CAPTURED / authorisation attempts in the last N minutes</li>
 *   <li>P95 latency = 95th percentile of authorisation response times in the last N minutes</li>
 * </ul>
 *
 * <p>When live traffic is thin the A3.4 historical band for the current
 * time of day (IST) acts as a prior: the success rate is a Bayesian blend
 * {@code (hist * k + successes) / (k + attempts)} with {@code k = min_samples},
 * which converges to the live rate as samples accumulate.</p>
 */
@Service
public class GatewayMetricsService {

    public static final ZoneId MERCHANT_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int MAX_LATENCY_SAMPLES_PER_MINUTE = 2_000;
    private static final int RING_MINUTES = 60;

    /** Aggregated live numbers over a window. */
    public record WindowStats(int attempts, int successes, int p95LatencyMs, int avgLatencyMs, int latencySamples) {}

    /** What the router uses for one gateway. */
    public record Estimate(double successRate, int p95LatencyMs, int liveAttempts, String source) {}

    private static final class Bucket {
        long minute = -1;
        int attempts;
        int successes;
        int latencyCount;
        long latencySum;
        long[] latencies = new long[64];

        void reset(long newMinute) {
            minute = newMinute;
            attempts = 0;
            successes = 0;
            latencyCount = 0;
            latencySum = 0;
        }

        void addLatency(long ms) {
            latencySum += ms;
            if (latencyCount < MAX_LATENCY_SAMPLES_PER_MINUTE) {
                if (latencyCount == latencies.length) latencies = Arrays.copyOf(latencies, latencies.length * 2);
                latencies[latencyCount] = ms;
            }
            latencyCount++;
        }
    }

    private static final class Ring {
        final Bucket[] buckets = new Bucket[RING_MINUTES];

        Ring() {
            for (int i = 0; i < RING_MINUTES; i++) buckets[i] = new Bucket();
        }

        synchronized Bucket at(long minute) {
            Bucket b = buckets[(int) (minute % RING_MINUTES)];
            if (b.minute != minute) b.reset(minute);
            return b;
        }
    }

    private final Map<String, Ring> rings = new ConcurrentHashMap<>();
    private final GatewayHistoricalPerformanceRepository historical;
    private volatile Map<String, List<GatewayHistoricalPerformance>> historicalCache;

    public GatewayMetricsService(GatewayHistoricalPerformanceRepository historical) {
        this.historical = historical;
    }

    /** Records an authorisation attempt. 429s are capacity signals, not performance, and are excluded. */
    public void recordAuthAttempt(String gateway, AttemptOutcome outcome, long latencyMs) {
        recordAuthAttempt(gateway, outcome, latencyMs, Instant.now());
    }

    /** As above, at an explicit time (used to replay or backfill a window). */
    public void recordAuthAttempt(String gateway, AttemptOutcome outcome, long latencyMs, Instant at) {
        if (outcome == AttemptOutcome.RATE_LIMITED) return;
        Ring ring = rings.computeIfAbsent(gateway, g -> new Ring());
        synchronized (ring) {
            Bucket b = ring.at(minuteOf(at));
            b.attempts++;
            b.addLatency(latencyMs);
        }
    }

    /** Records that a payment routed to {@code gateway} reached CAPTURED. */
    public void recordCaptured(String gateway) {
        Ring ring = rings.computeIfAbsent(gateway, g -> new Ring());
        synchronized (ring) {
            ring.at(minuteOf(Instant.now())).successes++;
        }
    }

    public WindowStats window(String gateway, int minutes) {
        return window(gateway, minuteOf(Instant.now()) - minutes + 1, minuteOf(Instant.now()));
    }

    /** Stats for the closed range of minute numbers [fromMinute, toMinute]. */
    public WindowStats window(String gateway, long fromMinute, long toMinute) {
        Ring ring = rings.get(gateway);
        if (ring == null) return new WindowStats(0, 0, 0, 0, 0);
        int attempts = 0;
        int successes = 0;
        long latencySum = 0;
        int latencyCount = 0;
        long[] samples = new long[0];
        synchronized (ring) {
            for (long m = Math.max(fromMinute, toMinute - RING_MINUTES + 1); m <= toMinute; m++) {
                Bucket b = ring.buckets[(int) (m % RING_MINUTES)];
                if (b.minute != m) continue;
                attempts += b.attempts;
                successes += b.successes;
                latencySum += b.latencySum;
                latencyCount += b.latencyCount;
                int kept = Math.min(b.latencyCount, MAX_LATENCY_SAMPLES_PER_MINUTE);
                int offset = samples.length;
                samples = Arrays.copyOf(samples, offset + kept);
                System.arraycopy(b.latencies, 0, samples, offset, kept);
            }
        }
        int p95 = percentile(samples, 0.95);
        int avg = latencyCount == 0 ? 0 : (int) (latencySum / latencyCount);
        return new WindowStats(attempts, Math.min(successes, attempts), p95, avg, samples.length);
    }

    /** Router input for a gateway: live window blended with the historical prior. */
    public Estimate estimate(String gateway, int windowMinutes, int minSamples) {
        WindowStats live = window(gateway, windowMinutes);
        GatewayHistoricalPerformance band = historicalBand(gateway, Instant.now());
        double priorRate = band == null ? 0.95 : band.getSuccessRate().doubleValue();
        int priorP95 = band == null ? 500 : band.getP95LatencyMs();
        int k = Math.max(1, minSamples);
        double successRate = (priorRate * k + live.successes()) / (k + live.attempts());
        int p95 = live.latencySamples() >= minSamples ? live.p95LatencyMs()
                : (int) Math.round((priorP95 * (double) (k - live.latencySamples())
                        + live.p95LatencyMs() * (double) live.latencySamples()) / k);
        String source = live.attempts() >= minSamples ? "LIVE" : live.attempts() == 0 ? "HISTORICAL" : "BLENDED";
        return new Estimate(successRate, p95, live.attempts(), source);
    }

    /** The A3.4 band covering the current IST hour. */
    public GatewayHistoricalPerformance historicalBand(String gateway, Instant at) {
        int hour = ZonedDateTime.ofInstant(at, MERCHANT_ZONE).getHour();
        return historicalFor(gateway).stream().filter(b -> b.covers(hour)).findFirst().orElse(null);
    }

    public List<GatewayHistoricalPerformance> historicalFor(String gateway) {
        Map<String, List<GatewayHistoricalPerformance>> cache = historicalCache;
        if (cache == null) {
            cache = new ConcurrentHashMap<>();
            for (GatewayHistoricalPerformance h : historical.findAll()) {
                cache.computeIfAbsent(h.getGateway(), g -> new java.util.ArrayList<>()).add(h);
            }
            historicalCache = cache;
        }
        return cache.getOrDefault(gateway, List.of());
    }

    /** Clears live windows (used by the test harness between scenarios). */
    public void reset() {
        rings.clear();
    }

    public static long minuteOf(Instant t) {
        return t.getEpochSecond() / 60;
    }

    static int percentile(long[] values, double p) {
        if (values.length == 0) return 0;
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        return (int) sorted[Math.max(0, Math.min(idx, sorted.length - 1))];
    }
}
