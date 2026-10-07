package com.payflow.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.domain.AttemptOutcome;
import com.payflow.domain.PaymentMethod;
import com.payflow.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Day 5-6: every adapter simulates the five response types and each produces the
 * right state transitions through the orchestrator.
 */
class GatewayAdapterIntegrationTest extends IntegrationTest {

    @Autowired GatewayRegistry registry;
    @Autowired GatewayCaller caller;

    static Stream<Arguments> gatewaysAndModes() {
        return Stream.of("razorpay", "stripe", "payu", "upi").flatMap(g -> Stream.of(
                Arguments.of(g, "success", 201, "CAPTURED", "SUCCESS"),
                Arguments.of(g, "timeout", 202, "ROUTE_SELECTED", "TIMEOUT"),
                Arguments.of(g, "server-error", 202, "ROUTE_SELECTED", "SERVER_ERROR"),
                Arguments.of(g, "decline", 402, "FAILED", "DECLINED"),
                Arguments.of(g, "rate-limit", 202, "ROUTE_SELECTED", "RATE_LIMITED")));
    }

    @ParameterizedTest(name = "{0} {1} -> HTTP {2}, state {3}")
    @MethodSource("gatewaysAndModes")
    void adapterResponseDrivesStateMachine(String gateway, String mode, int http, String state, String outcome)
            throws Exception {
        String method = gateway.equals("upi") ? "UPI" : "CARD";
        if (!gateway.equals("upi")) {
            for (String g : List.of("razorpay", "stripe", "payu")) {
                putJson("/api/v1/gateways/" + g + "/config", Map.of("enabled", g.equals(gateway)));
            }
        }
        MvcResult r = createPayment(UUID.randomUUID().toString(), paymentRequest(20_000, method),
                "X-Mock-Response", gateway + "=" + mode);
        assertThat(r.getResponse().getStatus()).isEqualTo(http);
        Map<String, Object> b = body(r);
        Object id = b.containsKey("id") ? b.get("id") : ((Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) b.get("error"))
                .get("details")).get("payment")).get("id");
        assertThat(state(id)).isEqualTo(state);
        List<Map<String, Object>> attempts = list(getJson("/api/v1/payments/" + id + "/attempts"));
        assertThat(attempts.get(0)).containsEntry("gateway", gateway).containsEntry("outcome", outcome);
        if (mode.equals("decline")) {
            assertThat(b.toString()).contains("PAYMENT_AUTH_FAILED").contains("gateway_error_code");
            assertThat(states(id)).endsWith("AUTH_INITIATED", "AUTH_FAILED", "FAILED");
        }
        if (mode.equals("server-error")) {
            assertThat(attempts).filteredOn(a -> "SERVER_ERROR".equals(a.get("outcome"))).hasSize(2); // retried once
        }
    }

    @Test
    void referencesFollowEachGatewaysFormat() throws Exception {
        assertThat(authorise("razorpay").reference()).matches("pay_[0-9a-f]{14}");
        assertThat(authorise("stripe").reference()).matches("pi_[0-9a-f]{14}");
        assertThat(authorise("payu").reference()).matches("\\d{11}");
        assertThat(authorise("upi").reference()).matches("\\d{12}");
        assertThat(authorise("upi").status()).isEqualTo(GatewayStatus.CAPTURED); // UPI has no separate capture
        assertThat(authorise("stripe").status()).isEqualTo(GatewayStatus.AUTHORISED);
    }

    @Test
    void sameIdempotencyTokenNeverCreatesASecondCharge() throws Exception {
        UUID txn = UUID.randomUUID();
        PaymentGateway stripe = registry.get("stripe");
        var req = new PaymentGateway.AuthRequest(txn, 5_000, "INR", PaymentMethod.CARD, null, txn.toString(), "trace-1");
        String first = stripe.authorize(req).reference();
        String second = stripe.authorize(req).reference();
        assertThat(second).isEqualTo(first);
        assertThat(ledger.chargesCreated("stripe")).isEqualTo(1);
    }

    @Test
    void traceIdAndIdempotencyTokenArePropagatedToTheGateway() throws Exception {
        PaymentGateway.AuthResponse r = authorise("razorpay");
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) r.raw().get("_request_headers");
        assertThat(headers).containsKeys("X-Trace-Id", "X-Razorpay-Idempotency-Key");
        @SuppressWarnings("unchecked")
        Map<String, Object> payuHeaders = (Map<String, Object>) registry.get("payu").authorize(request()).raw()
                .get("_request_headers");
        assertThat(payuHeaders).containsKey("txnid");
    }

    @Test
    void callerTurnsAHangingGatewayIntoATimeout() {
        MockControl.bind(MockControl.fromHeaders(Map.of("X-Mock-Response", "timeout")));
        try {
            long start = System.nanoTime();
            assertThatThrownBy(() -> caller.call("razorpay", 150, () -> registry.get("razorpay").authorize(request())))
                    .isInstanceOfSatisfying(GatewayException.class,
                            e -> assertThat(e.getKind()).isEqualTo(AttemptOutcome.TIMEOUT));
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_000);
        } finally {
            MockControl.clear();
        }
    }

    @Test
    void unreachableGatewayFailsImmediately() {
        MockControl.bind(MockControl.fromHeaders(Map.of("X-Mock-Gateway-Down", "true")));
        try {
            assertThatThrownBy(() -> registry.get("payu").authorize(request()))
                    .isInstanceOfSatisfying(GatewayException.class, e -> {
                        assertThat(e.getKind()).isEqualTo(AttemptOutcome.UNREACHABLE);
                        assertThat(e.isRetryable()).isTrue();
                    });
        } finally {
            MockControl.clear();
        }
    }

    @Test
    void captureRefundVoidAndStatusWorkAgainstTheLedger() throws Exception {
        PaymentGateway razorpay = registry.get("razorpay");
        UUID txn = UUID.randomUUID();
        String ref = razorpay.authorize(new PaymentGateway.AuthRequest(txn, 10_000, "INR", PaymentMethod.CARD, null,
                txn.toString(), "t")).reference();
        assertThat(razorpay.capture(new PaymentGateway.CaptureRequest(txn, ref, 6_000, "k", "t")).capturedPaise())
                .isEqualTo(6_000);
        assertThat(razorpay.fetchStatus(ref, txn).status()).isEqualTo(GatewayStatus.CAPTURED);
        assertThat(razorpay.refund(new PaymentGateway.RefundRequest(txn, ref, UUID.randomUUID(), 6_000, "t"))
                .gatewayRefundId()).startsWith("rfnd_");
        assertThat(razorpay.fetchStatus(ref, txn).status()).isEqualTo(GatewayStatus.REFUNDED);
        assertThat(razorpay.settlementReport(List.of(ref)).get(ref).status()).isEqualTo(GatewayStatus.SETTLED);
        assertThat(razorpay.fetchStatus("pay_unknown", null).status()).isEqualTo(GatewayStatus.UNKNOWN);

        UUID other = UUID.randomUUID();
        String held = razorpay.authorize(new PaymentGateway.AuthRequest(other, 1_000, "INR", PaymentMethod.CARD, null,
                other.toString(), "t")).reference();
        razorpay.voidAuthorisation(new PaymentGateway.VoidRequest(other, held, 1_000, "t"));
        assertThat(razorpay.fetchStatus(held, other).status()).isEqualTo(GatewayStatus.VOIDED);
        assertThatThrownBy(() -> razorpay.capture(
                new PaymentGateway.CaptureRequest(UUID.randomUUID(), "pay_missing", 1, "k", "t")))
                .isInstanceOf(GatewayException.class);
    }

    @Test
    void registryRejectsUnknownGateway() {
        assertThat(registry.names()).containsExactlyInAnyOrder("razorpay", "stripe", "payu", "upi");
        assertThatThrownBy(() -> registry.get("paypal")).isInstanceOf(IllegalArgumentException.class);
    }

    private PaymentGateway.AuthResponse authorise(String gateway) throws GatewayException {
        PaymentMethod m = gateway.equals("upi") ? PaymentMethod.UPI : PaymentMethod.CARD;
        UUID txn = UUID.randomUUID();
        return registry.get(gateway).authorize(new PaymentGateway.AuthRequest(txn, 10_000, "INR", m, null,
                txn.toString(), UUID.randomUUID().toString()));
    }

    private static PaymentGateway.AuthRequest request() {
        UUID txn = UUID.randomUUID();
        return new PaymentGateway.AuthRequest(txn, 10_000, "INR", PaymentMethod.CARD, null, txn.toString(), "t");
    }
}
