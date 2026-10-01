package com.payflow.gateway;

import com.payflow.config.PayFlowProperties;
import org.springframework.stereotype.Component;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Random;

/**
 * Simulated gateway client (two-phase: authorise + capture). Outcomes are
 * derived deterministically from HMAC(reference, gateway) so tests are
 * reproducible; behaviour parameters mirror the historical dataset in A3.4.
 * Replace with HTTP clients (RestClient) per gateway in production.
 */
@Component
public class SimulatedGatewayClient implements GatewayClient {

    // failureProb, timeoutProb, meanAuthLatencyMs, meanCaptureLatencyMs, captureFailureProb
    private static final Map<String, double[]> BEHAVIOR = Map.of(
            "razorpay", new double[]{0.02, 0.01, 320, 250, 0.01},
            "stripe",   new double[]{0.012, 0.008, 280, 220, 0.01},
            "payu",     new double[]{0.06, 0.03, 950, 600, 0.02},
            "upi",      new double[]{0.005, 0.002, 180, 150, 0.005});

    private final PayFlowProperties props;

    private static double[] behavior(String gateway) {
        double[] cfg = BEHAVIOR.get(gateway);
        return cfg != null ? cfg : new double[]{0.05, 0.02, 500, 400, 0.01};
    }

    public SimulatedGatewayClient(PayFlowProperties props) {
        this.props = props;
    }

    private Random rng(String gateway, String reference) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((gateway + ":" + reference).getBytes());
            return new Random(new java.math.BigInteger(1, d).longValue());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record Draw(long latencyMs, boolean timedOut, double roll) {}

    @Override
    public AuthResult authorize(String gateway, long amountPaise, String reference)
            throws GatewayException {
        MockControl control = MockControl.current();
        if (control.gatewayDown()) {
            throw new GatewayTimeout(gateway); // unreachable: hold the attempt budget open
        }
        sleepQuietly(control.delayMs());
        double[] b = behavior(gateway);
        if (control.forced() != null) {
            return handleForced(control, gateway, reference);
        }
        Random r = rng(gateway, reference);
        long latency = (long) Math.min(3000, -Math.log(1 - r.nextDouble()) * b[2]);
        if (r.nextDouble() < b[1] || latency > props.attemptTimeoutMillis()) {
            sleepQuietly(props.attemptTimeoutMillis() - latency + 10);
            throw new GatewayTimeout(gateway);
        }
        if (r.nextDouble() < b[0]) {
            throw new GatewayException(gateway, "DECLINED", "gateway declined " + reference);
        }
        return new AuthResult(true, reference + ":" + gateway.substring(0, 3), null, latency);
    }

    @Override
    public CaptureResult capture(String gateway, String reference, long amountPaise)
            throws GatewayException {
        MockControl control = MockControl.current();
        if (control.gatewayDown()) {
            throw new GatewayTimeout(gateway);
        }
        sleepQuietly(control.delayMs());
        double[] b = behavior(gateway);
        if (control.forced() != null) {
            return handleForcedCapture(control, gateway, amountPaise);
        }
        Random r = rng(gateway, reference + ":cap");
        long latency = (long) Math.min(3000, -Math.log(1 - r.nextDouble()) * Math.max(1, b[3]));
        if (r.nextDouble() < b[1] || latency > props.attemptTimeoutMillis()) {
            throw new GatewayTimeout(gateway);
        }
        double roll = r.nextDouble();
        if (roll < b[4]) {
            throw new GatewayException(gateway, "CAPTURE_FAILED", "HTTP 502 Bad Gateway");
        }
        if (roll < b[4] + 0.004) {
            return new CaptureResult(true, amountPaise / 2, "PARTIAL", latency);
        }
        return new CaptureResult(true, amountPaise, null, latency);
    }

    private AuthResult handleForced(MockControl control, String gateway, String reference)
            throws GatewayException {
        return switch (control.forced()) {
            case SUCCESS, PARTIAL -> new AuthResult(true, reference + ":" + gateway, null, control.delayMs());
            case TIMEOUT -> throw new GatewayTimeout(gateway);
            case SERVER_ERROR -> throw new GatewayException(gateway, "HTTP_502", "Bad Gateway");
            case DECLINE -> throw new GatewayException(gateway, "DECLINED", "insufficient funds");
            case RATE_LIMIT -> throw new GatewayException(gateway, "HTTP_429", "rate limited");
        };
    }

    private CaptureResult handleForcedCapture(MockControl control, String gateway, long amountPaise)
            throws GatewayException {
        return switch (control.forced()) {
            case SUCCESS -> new CaptureResult(true, amountPaise, null, control.delayMs());
            case PARTIAL -> new CaptureResult(true, amountPaise / 2, "PARTIAL", control.delayMs());
            case TIMEOUT -> throw new GatewayTimeout(gateway);
            case SERVER_ERROR -> throw new GatewayException(gateway, "HTTP_502", "Bad Gateway");
            case DECLINE -> throw new GatewayException(gateway, "DECLINED", "insufficient funds");
            case RATE_LIMIT -> throw new GatewayException(gateway, "HTTP_429", "rate limited");
        };
    }

    @Override
    public boolean refund(String gateway, String reference, long amountPaise) {
        return true;
    }

    @Override
    public void voidAuthorisation(String gateway, String reference) {
        // Releasing the hold is a no-op in simulation; real adapters call the void API.
    }

    /**
     * A5.5 step 2. In simulation the gateway reports the state implied by the
     * reference, so reconciliation exercises the real mismatch path without
     * network access.
     */
    @Override
    public String fetchStatus(String gateway, String reference) {
        if (reference == null) return "UNKNOWN";
        if (reference.contains("expired")) return "EXPIRED";
        if (reference.contains("failed")) return "FAILED";
        if (reference.contains("reversed")) return "REVERSED";
        if (reference.contains("auth")) return "AUTHORISED";
        return "CAPTURED";
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(Math.min(ms, 3000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
