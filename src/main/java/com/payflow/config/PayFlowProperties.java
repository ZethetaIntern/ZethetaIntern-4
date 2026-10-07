package com.payflow.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application settings ({@code payflow.*}). Business configuration that must
 * change without redeployment (routing weights, circuit breaker thresholds,
 * gateway capabilities) lives in the database instead.
 */
@ConfigurationProperties(prefix = "payflow")
public record PayFlowProperties(
        String apiKey,
        Orchestration orchestration,
        Upi upi,
        Reconciliation reconciliation,
        Webhooks webhooks,
        Mock mock,
        Jobs jobs) {

    public PayFlowProperties {
        if (apiKey == null || apiKey.isBlank()) apiKey = "pk_test_payflow";
        if (orchestration == null) orchestration = new Orchestration(0, 0, 0, 0, 0, 0, 0);
        if (upi == null) upi = new Upi(null);
        if (reconciliation == null) reconciliation = new Reconciliation(null, 0, 0);
        if (webhooks == null) webhooks = new Webhooks(true, 0, null, null, null, null, null);
        if (mock == null) mock = new Mock(false, false);
        if (jobs == null) jobs = new Jobs(null);
    }

    /**
     * Failover and retry budgets.
     *
     * @param attemptTimeoutMs    per-gateway authorisation budget; a gateway that has not answered by
     *                            then is treated as timed out (A1.1: much shorter than the gateway's own 30 s)
     * @param failoverWindowMs    time allowed after the first failure for alternates to respond (< 2 s)
     * @param maxGatewayAttempts  maximum distinct gateways tried synchronously for one payment
     * @param captureMaxRetries   capture retries on 5xx/timeout before CAPTURE_FAILED (FS-04)
     * @param captureBackoffMs    base of the capture exponential backoff (1 s, 2 s, 4 s)
     * @param paymentMaxRetries   asynchronous retries for a payment parked because every gateway throttled (FS-07)
     * @param paymentRetryBaseMs  base delay of the asynchronous payment retry (exponential, with jitter)
     */
    public record Orchestration(long attemptTimeoutMs, long failoverWindowMs, int maxGatewayAttempts,
                                int captureMaxRetries, long captureBackoffMs, int paymentMaxRetries,
                                long paymentRetryBaseMs) {
        public Orchestration {
            if (attemptTimeoutMs <= 0) attemptTimeoutMs = 1000;
            if (failoverWindowMs <= 0) failoverWindowMs = 2000;
            if (maxGatewayAttempts <= 0) maxGatewayAttempts = 3;
            if (captureMaxRetries <= 0) captureMaxRetries = 3;
            if (captureBackoffMs <= 0) captureBackoffMs = 1000;
            if (paymentMaxRetries <= 0) paymentMaxRetries = 3;
            if (paymentRetryBaseMs <= 0) paymentRetryBaseMs = 1000;
        }
    }

    /** @param collectWindow UPI collect mandate window (FS-12, default 5 minutes) */
    public record Upi(Duration collectWindow) {
        public Upi {
            if (collectWindow == null) collectWindow = Duration.ofMinutes(5);
        }
    }

    /**
     * @param staleThreshold         how long AUTH_INITIATED / CAPTURE_INITIATED may sit before polling (A5.5)
     * @param settlementLookbackDays how far back captured transactions are checked against settlement data
     * @param batchSize              page size for reconciliation scans
     */
    public record Reconciliation(Duration staleThreshold, int settlementLookbackDays, int batchSize) {
        public Reconciliation {
            if (staleThreshold == null) staleThreshold = Duration.ofMinutes(5);
            if (settlementLookbackDays <= 0) settlementLookbackDays = 7;
            if (batchSize <= 0) batchSize = 1000;
        }
    }

    /**
     * Webhook verification secrets, one per gateway (A5.3).
     *
     * @param inlineProcessing   process a webhook in the request thread right after it is queued
     *                           (the queue worker still picks up anything that fails or is deferred)
     * @param stripeToleranceSec maximum age of a Stripe-Signature timestamp (replay protection)
     * @param upiPublicKeyPem    NPCI certificate public key used to verify UPI callbacks (RSA-SHA256)
     * @param upiPrivateKeyPem   simulator-only key used by the mock NPCI switch to sign callbacks
     */
    public record Webhooks(boolean inlineProcessing, long stripeToleranceSec, String razorpaySecret,
                           String stripeSecret, String payuSecret, String upiPublicKeyPem,
                           String upiPrivateKeyPem) {
        public Webhooks {
            if (stripeToleranceSec <= 0) stripeToleranceSec = 300;
            if (razorpaySecret == null) razorpaySecret = "rzp_whsec_test";
            if (stripeSecret == null) stripeSecret = "whsec_stripe_test";
            if (payuSecret == null) payuSecret = "payu_salt_test";
        }
    }

    /**
     * Mock gateway behaviour (B4.3). Without control headers every call succeeds.
     *
     * @param simulateLatency sleep for a fraction of the A3.4 P95 latency on each call
     * @param randomFailures  inject the A3.4 failure rates (off by default so the harness is deterministic)
     */
    public record Mock(boolean simulateLatency, boolean randomFailures) {}

    /** @param abandonAfter CREATED transactions never routed after this long become ABANDONED */
    public record Jobs(Duration abandonAfter) {
        public Jobs {
            if (abandonAfter == null) abandonAfter = Duration.ofMinutes(30);
        }
    }
}
