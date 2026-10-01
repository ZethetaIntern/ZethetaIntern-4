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

    // gateway -> {supportsUpi, costBps, fixedCostPaise, baseLatencyMs}
    private static final Object[][] SEED = {
            {"razorpay", 1, 200, 200, 520},   // 2.0% + Rs2, p95 ~520ms (12:00-18:00 bucket)
            {"stripe",    0, 250, 300, 350},  // 2.5% + Rs3, p95 ~350ms
            {"payu",      1, 180, 150, 750},  // 1.8% + Rs1.5, p95 ~750ms
            {"upi",       1, 0,   0,   180}   // 0% + Rs0, p95 ~180ms
    };

    @Bean
    CommandLineRunner seedGateways(GatewayRouteRepository routes, RoutingConfigRepository config) {
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
            log.info("gateway routes + routing config seeded");
        };
    }
}
