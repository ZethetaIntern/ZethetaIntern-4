package com.payflow.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.payflow.domain.HealthStatus;
import com.payflow.domain.PaymentMethod;
import com.payflow.entity.GatewayConfig;
import java.util.List;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The A3.2 scoring formula with known inputs and hand-computed expected outputs. */
class GatewayRouterTest {

    private static final RoutingConfigService.Weights DEFAULT =
            new RoutingConfigService.Weights(0.35, 0.20, 0.20, 0.15, 0.10);

    private static GatewayRouter.Inputs in(String gw, double rate, int p95, long fee, HealthStatus h) {
        return new GatewayRouter.Inputs(gw, new GatewayMetricsService.Estimate(rate, p95, 100, "LIVE"), fee, h);
    }

    @Test
    void scoresMatchTheFormula() {
        // normalised latency: A 0, B 0.5, C 1 ; normalised cost: A 0.5, B 0, C 1
        List<GatewayRouter.Candidate> ranked = GatewayRouter.score(List.of(
                in("A", 0.98, 300, 5000, HealthStatus.HEALTHY),
                in("B", 0.95, 500, 4000, HealthStatus.HEALTHY),
                in("C", 0.90, 700, 6000, HealthStatus.DEGRADED)), DEFAULT);

        // A = .35*.98 + .20*(1-0) + .20*(1-.5) + .15*1 + .10*1 = 0.893
        // B = .35*.95 + .20*(1-.5) + .20*(1-0) + .15*1 + .10*1 = 0.8825
        // C = .35*.90 + .20*0 + .20*0 + .15*.5 + .10*1        = 0.490
        assertThat(ranked).extracting(GatewayRouter.Candidate::gateway).containsExactly("A", "B", "C");
        assertThat(ranked.get(0).score()).isCloseTo(0.893, within(1e-9));
        assertThat(ranked.get(1).score()).isCloseTo(0.8825, within(1e-9));
        assertThat(ranked.get(2).score()).isCloseTo(0.490, within(1e-9));
        assertThat(ranked.get(2).breakdown()).containsEntry("health_score", 0.5).containsEntry("normalized_cost", 1.0);
    }

    @Test
    void singleCandidateGetsFullLatencyAndCostPoints() {
        List<GatewayRouter.Candidate> ranked = GatewayRouter.score(
                List.of(in("upi", 0.99, 180, 0, HealthStatus.HEALTHY)), DEFAULT);
        assertThat(ranked.get(0).score()).isCloseTo(0.35 * 0.99 + 0.20 + 0.20 + 0.15 + 0.10, within(1e-9));
    }

    @Test
    void normaliseHandlesEqualMinAndMax() {
        assertThat(GatewayRouter.normalise(5, 5, 5)).isZero();
        assertThat(GatewayRouter.normalise(7, 5, 9)).isEqualTo(0.5);
    }

    @Test
    void weightsChangeTheWinner() {
        List<GatewayRouter.Inputs> inputs = List.of(
                in("fast", 0.95, 200, 6000, HealthStatus.HEALTHY),
                in("cheap", 0.95, 900, 1000, HealthStatus.HEALTHY));
        assertThat(GatewayRouter.score(inputs, new RoutingConfigService.Weights(0.2, 0.6, 0.0, 0.1, 0.1)).get(0).gateway())
                .isEqualTo("fast");
        assertThat(GatewayRouter.score(inputs, new RoutingConfigService.Weights(0.2, 0.0, 0.6, 0.1, 0.1)).get(0).gateway())
                .isEqualTo("cheap");
    }

    // --------------------------------------------------------------- decide(): filtering and degraded rule

    private GatewayConfigService configs;
    private RoutingConfigService routingConfig;
    private GatewayMetricsService metrics;
    private CircuitBreakerService circuits;

    @BeforeEach
    void mocks() {
        configs = mock(GatewayConfigService.class);
        routingConfig = mock(RoutingConfigService.class);
        metrics = mock(GatewayMetricsService.class);
        circuits = mock(CircuitBreakerService.class);
        when(routingConfig.current()).thenReturn(new RoutingConfigService.Snapshot(DEFAULT, 15, 20, 0.9, 0.20));
    }

    private static GatewayConfig gateway(String name, String methods, String currencies, int feeBps, boolean enabled) {
        GatewayConfig g = new GatewayConfig();
        g.setGatewayName(name);
        g.setDisplayName(name);
        g.setSupportedMethods(methods);
        g.setSupportedCurrencies(currencies);
        g.setFeeBps(feeBps);
        g.setFixedFeePaise(0);
        g.setEnabled(enabled);
        g.setRateLimitStrategy(GatewayConfig.RateLimitStrategy.TOKEN_BUCKET);
        return g;
    }

    private void estimate(String gw, double rate, int p95) {
        when(metrics.estimate(eq(gw), anyInt(), anyInt())).thenReturn(new GatewayMetricsService.Estimate(rate, p95, 50, "LIVE"));
    }

    @Test
    void excludesUnsupportedDisabledAndOpenGateways() {
        when(configs.all()).thenReturn(List.of(
                gateway("upi", "UPI", "INR", 0, true),
                gateway("stripe", "CARD", "INR,USD", 250, true),
                gateway("off", "CARD", "INR", 100, false),
                gateway("down", "CARD", "INR", 100, true),
                gateway("razorpay", "CARD", "INR", 200, true)));
        when(circuits.health(any(), any())).thenReturn(HealthStatus.HEALTHY);
        when(circuits.health("down", PaymentMethod.CARD)).thenReturn(HealthStatus.DOWN);
        estimate("stripe", 0.98, 300);
        estimate("razorpay", 0.97, 400);

        GatewayRouter router = new GatewayRouter(configs, routingConfig, metrics, circuits,
                java.util.concurrent.ThreadLocalRandom.current());
        GatewayRouter.Decision card = router.decide(PaymentMethod.CARD, "INR", 100_000);
        assertThat(card.ranked()).extracting(GatewayRouter.Candidate::gateway).containsExactlyInAnyOrder("stripe", "razorpay");
        assertThat(card.excluded()).extracting(GatewayRouter.Exclusion::reason)
                .contains("METHOD_NOT_SUPPORTED", "DISABLED", "CIRCUIT_OPEN");

        GatewayRouter.Decision usd = router.decide(PaymentMethod.CARD, "USD", 100_000);
        assertThat(usd.ranked()).extracting(GatewayRouter.Candidate::gateway).containsExactly("stripe");
    }

    @Test
    void degradedTopGatewayLosesWhenItLeadsByLessThanTheMargin() {
        when(configs.all()).thenReturn(List.of(gateway("a", "CARD", "INR", 200, true), gateway("b", "CARD", "INR", 210, true),
                gateway("c", "CARD", "INR", 300, true)));
        when(circuits.health("a", PaymentMethod.CARD)).thenReturn(HealthStatus.DEGRADED);
        when(circuits.health("b", PaymentMethod.CARD)).thenReturn(HealthStatus.HEALTHY);
        when(circuits.health("c", PaymentMethod.CARD)).thenReturn(HealthStatus.HEALTHY);
        estimate("a", 0.99, 200);
        estimate("b", 0.97, 260);
        estimate("c", 0.90, 400);
        GatewayRouter router = new GatewayRouter(configs, routingConfig, metrics, circuits,
                java.util.concurrent.ThreadLocalRandom.current());
        GatewayRouter.Decision d = router.decide(PaymentMethod.CARD, "INR", 100_000);
        assertThat(d.ranked().get(0).gateway()).isEqualTo("b");
        assertThat(d.note()).contains("DEGRADED").contains("preferring b");
    }

    @Test
    void degradedTopGatewayWithLargeLeadReceivesTrafficProportionalToItsHealthScore() {
        when(configs.all()).thenReturn(List.of(gateway("a", "CARD", "INR", 0, true), gateway("b", "CARD", "INR", 900, true)));
        when(circuits.health("a", PaymentMethod.CARD)).thenReturn(HealthStatus.DEGRADED);
        when(circuits.health("b", PaymentMethod.CARD)).thenReturn(HealthStatus.HEALTHY);
        estimate("a", 0.99, 100);
        estimate("b", 0.50, 900);
        GatewayRouter admits = new GatewayRouter(configs, routingConfig, metrics, circuits, fixed(0.25));
        GatewayRouter sheds = new GatewayRouter(configs, routingConfig, metrics, circuits, fixed(0.75));
        assertThat(admits.decide(PaymentMethod.CARD, "INR", 100_000).ranked().get(0).gateway()).isEqualTo("a");
        assertThat(sheds.decide(PaymentMethod.CARD, "INR", 100_000).ranked().get(0).gateway()).isEqualTo("b");
    }

    private static RandomGenerator fixed(double value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public double nextDouble() {
                return value;
            }
        };
    }
}
