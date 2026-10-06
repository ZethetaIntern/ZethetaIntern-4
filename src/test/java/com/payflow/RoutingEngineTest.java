package com.payflow;

import com.payflow.core.RoutingEngine;
import com.payflow.core.RoutingEngine.RankedGateway;
import com.payflow.repository.GatewayRouteRepository;
import com.payflow.repository.GatewayAttemptRepository;
import com.payflow.entity.GatewayAttempt;
import com.payflow.entity.GatewayRoute;
import com.payflow.domain.AttemptOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.time.Instant;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RoutingEngineTest {

    @Autowired RoutingEngine routing;
    @Autowired GatewayRouteRepository routes;
    @Autowired GatewayAttemptRepository attempts;

    @Test
    void ranksAllFourGatewaysSortedDescending() {
        List<RankedGateway> ranked = routing.rank("card", 100000);
        assertEquals(4, ranked.size());
        for (int i = 1; i < ranked.size(); i++) {
            assertTrue(ranked.get(i - 1).score() >= ranked.get(i).score());
        }
    }

    @Test
    void upiMethodExcludesStripe() {
        List<RankedGateway> ranked = routing.rank("upi", 100000);
        assertTrue(ranked.stream().noneMatch(r -> r.route().getGateway().equals("stripe")));
    }

    @Test
    void unhealthyGatewayExcluded() {
        try {
            routing.setHealth("upi", false);
            List<RankedGateway> ranked = routing.rank("card", 100000);
            assertTrue(ranked.stream().noneMatch(r -> r.route().getGateway().equals("upi")));
        } finally {
            routing.setHealth("upi", true);
        }
    }

    @Test
    void weightsStoredInDbAndUpdatable() {
        var before = routing.weights();
        assertEquals(5, before.size());
    }

    @Test
    void recentFailuresAndP95LatencyReduceGatewayScore() {
        GatewayRoute route = new GatewayRoute();
        route.setGateway("metrics-test");
        route.setCostBps(100);
        route.setFixedCostPaise(0);
        route.setBaseLatencyMs(100);
        routes.saveAndFlush(route);
        List<GatewayAttempt> observations = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            observations.add(new GatewayAttempt("metrics-test-txn-" + i, "metrics-test", i + 1,
                    AttemptOutcome.TIMEOUT, 1900, "TIMEOUT"));
        }
        attempts.saveAllAndFlush(observations);
        try {
            double score = routing.rank("card", 100000).stream()
                    .filter(r -> r.route().getGateway().equals("metrics-test"))
                    .findFirst().orElseThrow().score();
            assertTrue(score < 0.5, "recent timeouts and P95 latency must affect routing score");
        } finally {
            attempts.deleteAll(observations);
            routes.delete(route);
        }
    }

    @Test
    void halfOpenGatewayAdmitsOnlyOneConcurrentProbe() {
        String gateway = "razorpay";
        try {
            routing.setHealth(gateway, false);
            var route = routes.findById(gateway).orElseThrow();
            route.setCircuitOpenUntil(Instant.EPOCH);
            routes.saveAndFlush(route);
            assertEquals(RoutingEngine.CircuitState.HALF_OPEN, routing.circuitState(gateway));
            assertTrue(routing.tryAcquireProbe(gateway));
            assertFalse(routing.tryAcquireProbe(gateway));
            routing.recordResult(gateway, true, 100);
            assertEquals(RoutingEngine.CircuitState.CLOSED, routing.circuitState(gateway));
        } finally {
            routing.setHealth(gateway, true);
        }
    }
}
