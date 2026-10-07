package com.payflow.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.config.PayFlowProperties;
import com.payflow.gateway.GatewayStatus;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A5.3 signature schemes for all four gateways and the per-gateway payload formats (A5.4). */
class WebhookSignatureAndParserTest {

    private final PayFlowProperties props = new PayFlowProperties(null, null, null, null, null, null, null);
    private final WebhookSignatureVerifier verifier = new WebhookSignatureVerifier(props);
    private final WebhookPayloadParser parser = new WebhookPayloadParser();

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> lower(Map<String, String> h) {
        Map<String, String> m = new HashMap<>();
        h.forEach((k, v) -> m.put(k.toLowerCase(Locale.ROOT), v));
        return m;
    }

    @ParameterizedTest
    @ValueSource(strings = {"razorpay", "stripe", "payu", "upi"})
    void validSignatureIsAccepted(String gateway) {
        byte[] body = bytes("{\"event_id\":\"evt_1\",\"status\":\"captured\",\"amount\":100}");
        assertThat(verifier.verify(gateway, lower(verifier.sign(gateway, body)), body).valid()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"razorpay", "stripe", "payu", "upi"})
    void tamperedBodyIsRejected(String gateway) {
        byte[] body = bytes("{\"event_id\":\"evt_1\",\"amount\":100}");
        Map<String, String> headers = lower(verifier.sign(gateway, body));
        var v = verifier.verify(gateway, headers, bytes("{\"event_id\":\"evt_1\",\"amount\":10000}"));
        assertThat(v.valid()).isFalse();
        assertThat(v.reason()).isEqualTo("signature_mismatch");
    }

    @ParameterizedTest
    @ValueSource(strings = {"razorpay", "stripe", "payu", "upi"})
    void missingSignatureIsRejected(String gateway) {
        var v = verifier.verify(gateway, Map.of(), bytes("{}"));
        assertThat(v.valid()).isFalse();
        assertThat(v.reason()).isEqualTo("missing_signature");
    }

    @Test
    void signatureIsOverRawBytesNotReserialisedJson() {
        byte[] spaced = bytes("{ \"event_id\" : \"evt_1\" }");
        Map<String, String> headers = lower(verifier.sign("razorpay", spaced));
        assertThat(verifier.verify("razorpay", headers, spaced).valid()).isTrue();
        assertThat(verifier.verify("razorpay", headers, bytes("{\"event_id\":\"evt_1\"}")).valid()).isFalse();
    }

    @Test
    void stripeTimestampOutsideToleranceIsRejected() throws Exception {
        byte[] body = bytes("{\"id\":\"evt_old\"}");
        long old = Instant.now().getEpochSecond() - 3_600;
        String sig = WebhookSignatureVerifier.hmacHex("HmacSHA256", props.webhooks().stripeSecret(),
                bytes(old + "." + new String(body, StandardCharsets.UTF_8)));
        var v = verifier.verify("stripe", Map.of("stripe-signature", "t=" + old + ",v1=" + sig), body);
        assertThat(v.valid()).isFalse();
        assertThat(v.reason()).isEqualTo("timestamp_outside_tolerance");
        assertThat(verifier.verify("stripe", Map.of("stripe-signature", "garbage"), body).reason())
                .isEqualTo("malformed_signature_header");
        assertThat(verifier.verify("stripe", Map.of("stripe-signature", "t=abc,v1=00"), body).reason())
                .isEqualTo("malformed_signature_header");
    }

    @Test
    void payuUsesSha512AndUpiUsesRsa() {
        byte[] body = bytes("{}");
        assertThat(verifier.sign("payu", body).get(WebhookSignatureVerifier.PAYU_HEADER)).hasSize(128);
        assertThat(verifier.verify("upi", Map.of("x-npci-signature", "not-base64!!"), body).valid()).isFalse();
        assertThat(verifier.verify("paypal", Map.of(), body).reason()).isEqualTo("unknown_gateway");
        assertThatThrownBy(() -> verifier.sign("paypal", body)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constantTimeComparison() {
        assertThat(WebhookSignatureVerifier.constantTimeEquals("abc", "abc")).isTrue();
        assertThat(WebhookSignatureVerifier.constantTimeEquals("abc", "abd")).isFalse();
        assertThat(WebhookSignatureVerifier.constantTimeEquals("abc", "abcd")).isFalse();
    }

    // ------------------------------------------------------------------- parsers

    @Test
    void parsesRazorpayPaymentCaptured() {
        UUID txn = UUID.randomUUID();
        String json = "{\"entity\":\"event\",\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":"
                + "{\"id\":\"pay_X\",\"amount\":50000,\"currency\":\"INR\",\"notes\":{\"transaction_id\":\"" + txn + "\"}}}},"
                + "\"created_at\":1}";
        NormalizedWebhookEvent e = parser.parse("razorpay", Map.of("x-razorpay-event-id", "evt_rzp"),
                parser.readTree(bytes(json)));
        assertThat(e.eventId()).isEqualTo("evt_rzp");
        assertThat(e.status()).isEqualTo(GatewayStatus.CAPTURED);
        assertThat(e.gatewayReference()).isEqualTo("pay_X");
        assertThat(e.transactionId()).isEqualTo(txn);
        assertThat(e.amountPaise()).isEqualTo(50_000);
        // without the header the id is derived deterministically
        assertThat(parser.parse("razorpay", Map.of(), parser.readTree(bytes(json))).eventId())
                .isEqualTo("payment.captured:pay_X:1");
    }

    @Test
    void parsesRazorpayRefundAndDispute() {
        String refund = "{\"event\":\"refund.processed\",\"payload\":{\"refund\":{\"entity\":{\"id\":\"rfnd_1\","
                + "\"payment_id\":\"pay_X\",\"amount\":1000}}},\"created_at\":2}";
        NormalizedWebhookEvent r = parser.parse("razorpay", Map.of(), parser.readTree(bytes(refund)));
        assertThat(r.status()).isEqualTo(GatewayStatus.REFUNDED);
        assertThat(r.gatewayReference()).isEqualTo("pay_X");
        assertThat(r.refundAmountPaise()).isEqualTo(1_000);
        assertThat(r.gatewayRefundId()).isEqualTo("rfnd_1");
        String dispute = "{\"event\":\"payment.dispute.created\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_X\"}}},"
                + "\"created_at\":3}";
        assertThat(parser.parse("razorpay", Map.of(), parser.readTree(bytes(dispute))).status())
                .isEqualTo(GatewayStatus.DISPUTE_OPENED);
    }

    @Test
    void parsesStripeEvents() {
        String succeeded = "{\"id\":\"evt_s\",\"type\":\"payment_intent.succeeded\",\"data\":{\"object\":{\"id\":\"pi_1\","
                + "\"object\":\"payment_intent\",\"amount\":7500,\"currency\":\"inr\",\"metadata\":{}}}}";
        NormalizedWebhookEvent e = parser.parse("stripe", Map.of(), parser.readTree(bytes(succeeded)));
        assertThat(e.status()).isEqualTo(GatewayStatus.CAPTURED);
        assertThat(e.currency()).isEqualTo("INR");
        assertThat(e.gatewayReference()).isEqualTo("pi_1");
        String refunded = "{\"id\":\"evt_r\",\"type\":\"charge.refunded\",\"data\":{\"object\":{\"object\":\"charge\","
                + "\"payment_intent\":\"pi_1\",\"amount\":7500,\"amount_refunded\":7500}}}";
        NormalizedWebhookEvent r = parser.parse("stripe", Map.of(), parser.readTree(bytes(refunded)));
        assertThat(r.status()).isEqualTo(GatewayStatus.REFUNDED);
        assertThat(r.gatewayReference()).isEqualTo("pi_1");
        assertThat(r.refundAmountPaise()).isEqualTo(7_500);
    }

    @Test
    void parsesPayuAndUpiWithRupeeAmounts() {
        NormalizedWebhookEvent payu = parser.parse("payu", Map.of(), parser.readTree(bytes(
                "{\"mihpayid\":\"12345678901\",\"txnid\":\"x\",\"status\":\"success\",\"unmappedstatus\":\"captured\","
                        + "\"amount\":\"1200.50\"}")));
        assertThat(payu.status()).isEqualTo(GatewayStatus.CAPTURED);
        assertThat(payu.amountPaise()).isEqualTo(120_050);
        assertThat(payu.eventId()).isEqualTo("12345678901:captured");
        NormalizedWebhookEvent upi = parser.parse("upi", Map.of(), parser.readTree(bytes(
                "{\"txnId\":\"400000000001\",\"status\":\"EXPIRED\",\"amount\":\"250.00\"}")));
        assertThat(upi.status()).isEqualTo(GatewayStatus.EXPIRED);
        assertThat(upi.amountPaise()).isEqualTo(25_000);
        assertThat(WebhookPayloadParser.rupeesToPaise("0.01")).isEqualTo(1L);
        assertThatThrownBy(() -> WebhookPayloadParser.rupeesToPaise("1.001"))
                .isInstanceOf(WebhookPayloadParser.MalformedWebhookException.class);
    }

    @Test
    void rejectsMalformedPayloads() {
        assertThatThrownBy(() -> parser.readTree(bytes("not json")))
                .isInstanceOf(WebhookPayloadParser.MalformedWebhookException.class);
        assertThatThrownBy(() -> parser.readTree(bytes("[1,2]")))
                .isInstanceOf(WebhookPayloadParser.MalformedWebhookException.class);
        assertThatThrownBy(() -> parser.parse("stripe", Map.of(), parser.readTree(bytes("{\"type\":\"x\"}"))))
                .isInstanceOf(WebhookPayloadParser.MalformedWebhookException.class);
    }

    @Test
    void normalizedEventRoundTrips() {
        NormalizedWebhookEvent e = new NormalizedWebhookEvent("e", "t", GatewayStatus.SETTLED, "r", UUID.randomUUID(),
                10L, "INR", null, null, "batch");
        assertThat(NormalizedWebhookEvent.fromMap(e.toMap())).isEqualTo(e);
    }
}
