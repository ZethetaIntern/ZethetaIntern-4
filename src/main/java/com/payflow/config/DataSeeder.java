package com.payflow.config;

import com.payflow.entity.GatewayRoute;
import com.payflow.repository.GatewayRouteRepository;
import com.payflow.repository.RoutingConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Seeds the historical gateway performance dataset (spec A3.4) into
 * gateway_routes, and the default routing weights into routing_config.
 */
@Configuration
public class DataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private com.payflow.repository.GatewayHourlyMetricRepository metrics;

    // gateway -> {supportsUpi, costBps, fixedCostPaise, baseLatencyMs}
    private static final Object[][] SEED = {
            {"razorpay", 1, 200, 200, 520},   // 2.0% + Rs2, p95 ~520ms (12:00-18:00 bucket)
            {"stripe",    0, 250, 300, 350},  // 2.5% + Rs3, p95 ~350ms
            {"payu",      1, 180, 150, 750},  // 1.8% + Rs1.5, p95 ~750ms
            {"upi",       1, 0,   0,   180}   // 0% + Rs0, p95 ~180ms
    };

    @Bean
    CommandLineRunner seedGateways(GatewayRouteRepository routes, RoutingConfigRepository config,
                                   com.payflow.repository.GatewayHourlyMetricRepository metrics) {
        this.metrics = metrics;
        return args -> {
            for (Object[] s : SEED) {
                if (routes.findById((String) s[0]).isEmpty()) {
                    GatewayRoute r = new GatewayRoute();
                    r.setGateway((String) s[0]);
                    r.setSupportsUpi(((Integer) s[1]) == 1);
                    r.setCostBps((Integer) s[2]);
                    r.setFixedCostPaise((Integer) s[3]);
                    r.setBaseLatencyMs((Integer) s[4]);
                    routes.save(r);
                }
            }
            config.findById(1L).orElseGet(() -> config.save(new com.payflow.entity.RoutingConfig()));
            seedHistoricalDataset();
            log.info("gateway routes + routing config + hourly dataset seeded");
        };
    }

    /**
     * A3.4: seed 24 hourly buckets per gateway from the historical dataset so the
     * router has p95 latency / success-rate history before any live traffic.
     * UPI carries the cheapest, fastest rail; PayU is degraded during peak hours.
     */
    private void seedHistoricalDataset() {
        if (metrics == null || metrics.count() > 0) return;
        java.time.Instant hour = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        for (int h = 23; h >= 0; h--) {
            java.time.Instant t = hour.minusSeconds(h * 3600L);
            int hourOfDay = t.atZone(java.time.ZoneOffset.UTC).getHour();
            boolean peak = hourOfDay >= 12 && hourOfDay < 18; // 12:00-18:00 IST peak window
            record("razorpay", t, 0.978 - (peak ? 0.02 : 0), peak ? 520 : 380, 4200);
            record("stripe",   t, 0.991, peak ? 350 : 290, 3100);
            record("payu",     t, peak ? 0.802 : 0.884, peak ? 950 : 640, 1800); // degrades at peak
            record("upi",      t, 0.995, 180, 9800);
        }
    }

    private void record(String gw, java.time.Instant t, double rate, int p95, int count) {
        metrics.save(new com.payflow.entity.GatewayHourlyMetric(gw, t, rate, p95, count));
    }
}
