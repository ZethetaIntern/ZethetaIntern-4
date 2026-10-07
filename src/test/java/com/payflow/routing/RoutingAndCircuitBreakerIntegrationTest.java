package com.payflow.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.domain.AttemptOutcome;
import com.payflow.domain.HealthStatus;
import com.payflow.domain.PaymentMethod;
import com.payflow.entity.CircuitBreakerState.State;
import com.payflow.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/** Routing weights from the database and the per-(gateway, method) circuit breaker, end to end. */
class RoutingAndCircuitBreakerIntegrationTest extends IntegrationTest {

    @Autowired GatewayRouter router;

    @Test
    void weightsComeFromTheDatabaseAndChangeRoutingWithoutRedeploy() throws Exception {
        // Cost dominant: PayU (1.8% + 1.50) is the cheapest card gateway
        putJson("/api/v1/routing/config", Map.of("weights", Map.of("success_rate", 0.05, "latency", 0.05, "cost", 0.80,
                "health", 0.05, "method_fit", 0.05)));
        assertThat(topGateway("CARD", 500_000)).isEqualTo("payu");
        // Latency dominant: Stripe has the lowest historical P95 for cards in every band
        putJson("/api/v1/routing/config", Map.of("weights", Map.of("success_rate", 0.05, "latency", 0.80, "cost", 0.05,
                "health", 0.05, "method_fit", 0.05)));
        assertThat(topGateway("CARD", 500_000)).isEqualTo("stripe");
        Map<String, Object> cfg = body(getJson("/api/v1/routing/config"));
        assertThat(cfg.get("weights").toString()).contains("latency=0.8");
    }

    @Test
    void weightsMustSumToOne() throws Exception {
        MvcResult r = putJson("/api/v1/routing/config", Map.of("weights", Map.of("success_rate", 0.9)));
        assertThat(r.getResponse().getStatus()).isEqualTo(422);
        assertThat(body(r).toString()).contains("ROUTING_WEIGHTS_INVALID");
        assertThat(putJson("/api/v1/routing/config", Map.of("nonsense", 1)).getResponse().getStatus()).isEqualTo(422);
        assertThat(putJson("/api/v1/routing/config", Map.of("window_minutes", 10)).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void methodFitAndCurrencyRestrictCandidates() throws Exception {
        assertThat(rankedGateways("UPI", 10_000)).containsExactly("upi");
        Map<String, Object> usd = body(getJson("/api/v1/routing/preview?payment_method=CARD&currency=USD&amount_paise=10000"));
        assertThat(usd.get("ranked").toString()).contains("stripe").doesNotContain("razorpay");
    }

    @Test
    void coldStartUsesTheHistoricalBandForTheCurrentHour() {
        GatewayRouter.Decision d = router.decide(PaymentMethod.CARD, "INR", 100_000);
        assertThat(d.ranked()).allSatisfy(c -> assertThat(c.breakdown()).containsEntry("success_rate_source", "HISTORICAL"));
        var band = metrics.historicalBand("payu", java.time.Instant.now());
        assertThat(band).isNotNull();
        GatewayRouter.Candidate payu = d.ranked().stream().filter(c -> c.gateway().equals("payu")).findFirst().orElseThrow();
        assertThat((Double) payu.breakdown().get("success_rate")).isEqualTo(band.getSuccessRate().doubleValue());
        assertThat(payu.breakdown()).containsEntry("p95_latency_ms", band.getP95LatencyMs());
    }

    @Test
    void routingDecisionIsPersistedWithItsBreakdown() throws Exception {
        Map<String, Object> p = createOk(100_000, "CARD");
        List<Map<String, Object>> routes = list(getJson("/api/v1/payments/" + p.get("id") + "/routing"));
        assertThat(routes).hasSize(3);
        assertThat(routes).filteredOn(r -> Boolean.TRUE.equals(r.get("selected"))).singleElement()
                .satisfies(r -> assertThat(r).containsEntry("gateway", p.get("gateway")).containsEntry("rank", 1));
        assertThat(routes.get(0).get("breakdown").toString()).contains("normalized_latency").contains("contribution");
    }

    @Test
    void circuitOpensAfterThresholdAndExcludesTheGateway() {
        for (int i = 0; i < 4; i++) circuits.onFailure("razorpay", PaymentMethod.CARD);
        assertThat(circuits.state("razorpay", PaymentMethod.CARD)).isEqualTo(State.CLOSED);
        circuits.onFailure("razorpay", PaymentMethod.CARD);
        assertThat(circuits.state("razorpay", PaymentMethod.CARD)).isEqualTo(State.OPEN);
        assertThat(circuits.acquire("razorpay", PaymentMethod.CARD)).isEqualTo(CircuitBreakerService.Permit.REJECTED);
        assertThat(circuits.health("razorpay", PaymentMethod.CARD)).isEqualTo(HealthStatus.DOWN);
        assertThat(router.decide(PaymentMethod.CARD, "INR", 1000).ranked())
                .noneMatch(c -> c.gateway().equals("razorpay"));
        // Per payment method: the same gateway is still healthy for netbanking
        assertThat(circuits.state("razorpay", PaymentMethod.NETBANKING)).isEqualTo(State.CLOSED);
        assertThat(router.decide(PaymentMethod.NETBANKING, "INR", 1000).ranked())
                .anyMatch(c -> c.gateway().equals("razorpay"));
    }

    @Test
    void halfOpenAllowsOneProbeThenClosesOnSuccess() throws Exception {
        putJson("/api/v1/gateways/stripe/config", Map.of("circuit_payment_method", "CARD", "circuit_failure_threshold", 2,
                "circuit_open_timeout_ms", 150, "circuit_half_open_max_requests", 1));
        circuits.onFailure("stripe", PaymentMethod.CARD);
        circuits.onFailure("stripe", PaymentMethod.CARD);
        assertThat(circuits.state("stripe", PaymentMethod.CARD)).isEqualTo(State.OPEN);
        Thread.sleep(200);
        assertThat(circuits.state("stripe", PaymentMethod.CARD)).isEqualTo(State.HALF_OPEN);
        assertThat(circuits.health("stripe", PaymentMethod.CARD)).isEqualTo(HealthStatus.DEGRADED);
        assertThat(circuits.acquire("stripe", PaymentMethod.CARD)).isEqualTo(CircuitBreakerService.Permit.PROBE);
        assertThat(circuits.acquire("stripe", PaymentMethod.CARD)).isEqualTo(CircuitBreakerService.Permit.REJECTED);
        circuits.onSuccess("stripe", PaymentMethod.CARD);
        assertThat(circuits.state("stripe", PaymentMethod.CARD)).isEqualTo(State.CLOSED);
    }

    @Test
    void failedProbeReopensTheCircuit() throws Exception {
        putJson("/api/v1/gateways/payu/config", Map.of("circuit_failure_threshold", 1, "circuit_open_timeout_ms", 100));
        circuits.onFailure("payu", PaymentMethod.CARD);
        Thread.sleep(150);
        assertThat(circuits.acquire("payu", PaymentMethod.CARD)).isEqualTo(CircuitBreakerService.Permit.PROBE);
        circuits.onFailure("payu", PaymentMethod.CARD);
        assertThat(circuits.state("payu", PaymentMethod.CARD)).isEqualTo(State.OPEN);
    }

    @Test
    void halfOpenNeedsTheConfiguredNumberOfSuccessfulProbes() throws Exception {
        putJson("/api/v1/gateways/razorpay/config", Map.of("circuit_failure_threshold", 1, "circuit_open_timeout_ms", 100,
                "circuit_half_open_max_requests", 2));
        circuits.onFailure("razorpay", PaymentMethod.WALLET);
        Thread.sleep(150);
        assertThat(circuits.acquire("razorpay", PaymentMethod.WALLET)).isEqualTo(CircuitBreakerService.Permit.PROBE);
        assertThat(circuits.acquire("razorpay", PaymentMethod.WALLET)).isEqualTo(CircuitBreakerService.Permit.PROBE);
        circuits.onSuccess("razorpay", PaymentMethod.WALLET);
        assertThat(circuits.state("razorpay", PaymentMethod.WALLET)).isEqualTo(State.HALF_OPEN);
        circuits.onSuccess("razorpay", PaymentMethod.WALLET);
        assertThat(circuits.state("razorpay", PaymentMethod.WALLET)).isEqualTo(State.CLOSED);
    }

    @Test
    void successResetsConsecutiveFailures() {
        for (int i = 0; i < 4; i++) circuits.onFailure("upi", PaymentMethod.UPI);
        circuits.onSuccess("upi", PaymentMethod.UPI);
        for (int i = 0; i < 4; i++) circuits.onFailure("upi", PaymentMethod.UPI);
        assertThat(circuits.state("upi", PaymentMethod.UPI)).isEqualTo(State.CLOSED);
    }

    @Test
    void lowSuccessRateMarksAClosedGatewayDegraded() {
        for (int i = 0; i < 30; i++) {
            metrics.recordAuthAttempt("payu", AttemptOutcome.SUCCESS, 300);
            if (i % 2 == 0) metrics.recordCaptured("payu");
        }
        assertThat(circuits.state("payu", PaymentMethod.CARD)).isEqualTo(State.CLOSED);
        assertThat(circuits.health("payu", PaymentMethod.CARD)).isEqualTo(HealthStatus.DEGRADED);
    }

    @Test
    void operatorCanTripAndResetACircuit() throws Exception {
        Map<String, Object> opened = body(postJson("/api/v1/admin/circuits/upi/UPI/open", null));
        assertThat(opened.get("upi").toString()).contains("UPI=OPEN");
        assertThat(body(getJson("/api/v1/gateways/upi/health")).toString()).contains("DOWN");
        Map<String, Object> reset = body(postJson("/api/v1/admin/circuits/upi/UPI/reset", null));
        assertThat(reset.get("upi").toString()).contains("UPI=CLOSED");
    }
}
