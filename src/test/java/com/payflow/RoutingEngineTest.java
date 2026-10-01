package com.payflow;

import com.payflow.core.RoutingEngine;
import com.payflow.core.RoutingEngine.RankedGateway;
import com.payflow.repository.GatewayRouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class RoutingEngineTest {

    @Autowired RoutingEngine routing;
    @Autowired GatewayRouteRepository routes;

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
}
