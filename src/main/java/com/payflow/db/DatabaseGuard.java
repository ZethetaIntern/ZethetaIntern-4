package com.payflow.db;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Internal circuit breaker and pool monitor for the database (FS-14, C5.4).
 *
 * <ul>
 *   <li>Connection-acquisition failures are counted; after {@value #FAILURE_THRESHOLD}
 *       consecutive failures the breaker opens and requests fail fast with 503
 *       for {@value #OPEN_MILLIS} ms instead of queueing behind an exhausted pool.</li>
 *   <li>Pool utilisation is exposed for monitoring, and the webhook ingester checks
 *       {@link #underPressure()} to stop processing inline and leave work to the queue.</li>
 * </ul>
 * Errors about the database are logged asynchronously (never written to the database).
 */
@Component
public class DatabaseGuard {

    private static final Logger log = LoggerFactory.getLogger(DatabaseGuard.class);
    static final int FAILURE_THRESHOLD = 5;
    static final long OPEN_MILLIS = 5_000;
    private static final double PRESSURE_RATIO = 0.8;

    private final HikariDataSource primary;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile Instant openUntil = Instant.EPOCH;
    private volatile boolean probeInFlight;

    public DatabaseGuard(@Qualifier("primaryDataSource") DataSource primary) {
        this.primary = primary instanceof HikariDataSource h ? h : null;
    }

    /** False while the breaker is open; one probe request is let through once the open period ends. */
    public boolean allowRequest() {
        Instant now = Instant.now();
        if (now.isBefore(openUntil)) return false;
        if (consecutiveFailures.get() >= FAILURE_THRESHOLD) {
            synchronized (this) {
                if (probeInFlight) return false;
                probeInFlight = true;
            }
        }
        return true;
    }

    public void onSuccess() {
        if (consecutiveFailures.get() > 0 || probeInFlight) {
            consecutiveFailures.set(0);
            probeInFlight = false;
        }
    }

    public void onConnectionFailure() {
        int n = consecutiveFailures.incrementAndGet();
        probeInFlight = false;
        if (n >= FAILURE_THRESHOLD) {
            openUntil = Instant.now().plusMillis(OPEN_MILLIS);
            log.error("database circuit OPEN {} {} {}", kv("component", "db_guard"), kv("alert", true),
                    kv("consecutive_failures", n));
        }
    }

    /** Closes the breaker (operator action / tests). */
    public void reset() {
        consecutiveFailures.set(0);
        probeInFlight = false;
        openUntil = Instant.EPOCH;
    }

    public boolean isOpen() {
        return Instant.now().isBefore(openUntil);
    }

    /** True when the pool is nearly exhausted or threads are already waiting for a connection. */
    public boolean underPressure() {
        HikariPoolMXBean pool = pool();
        if (pool == null) return false;
        int max = primary.getMaximumPoolSize();
        return pool.getThreadsAwaitingConnection() > 0 || pool.getActiveConnections() >= max * PRESSURE_RATIO;
    }

    public Map<String, Object> poolStats() {
        Map<String, Object> m = new LinkedHashMap<>();
        HikariPoolMXBean pool = pool();
        if (pool != null) {
            m.put("active", pool.getActiveConnections());
            m.put("idle", pool.getIdleConnections());
            m.put("total", pool.getTotalConnections());
            m.put("waiting_threads", pool.getThreadsAwaitingConnection());
            m.put("max", primary.getMaximumPoolSize());
            m.put("utilisation", primary.getMaximumPoolSize() == 0 ? 0
                    : Math.round(100.0 * pool.getActiveConnections() / primary.getMaximumPoolSize()) + "%");
        }
        m.put("circuit", isOpen() ? "OPEN" : "CLOSED");
        m.put("consecutive_connection_failures", consecutiveFailures.get());
        return m;
    }

    private HikariPoolMXBean pool() {
        return primary == null ? null : primary.getHikariPoolMXBean();
    }
}
