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

    private Draw draw(String gateway, String reference) {
        double[] b = BEHAVIOR.getOrDefault(gateway, new double[]{0.05, 0.02, 500, 400, 0.01});
        Random r = rng(gateway, reference);
        long latency = (long) Math.min(3000, -Math.log(1 - r.nextDouble()) * b[2]);
        boolean timedOut = r.nextDouble() < b[1] || latency > props.attemptTimeoutMillis();
        return new Draw(Math.min(latency, props.attemptTimeoutMillis() + 50), timedOut, r.nextDouble());
    }

    @Override
    public AuthResult authorize(String gateway, long amountPaise, String reference)
            throws GatewayException {
        Draw d = draw(gateway, reference + ":auth");
        sleepQuietly(d.latencyMs);
        if (d.timedOut) throw new GatewayTimeout(gateway);
        if (d.roll < BEHAVIOR.getOrDefault(gateway, new double[]{0.05})[0]) {
            throw new GatewayException(gateway, "DECLINED", "gateway declined " + reference);
        }
        return new AuthResult(true, reference + ":" + gateway.substring(0, 3), null, d.latencyMs);
    }

    @Override
    public CaptureResult capture(String gateway, String reference, long amountPaise)
            throws GatewayException {
        Draw d = draw(gateway, reference + ":cap");
        sleepQuietly(d.latencyMs);
        if (d.timedOut) throw new GatewayTimeout(gateway);
        double capFail = BEHAVIOR.getOrDefault(gateway, new double[]{0, 0, 0, 0, 0.01})[4];
        if (d.roll < capFail) {
            throw new GatewayException(gateway, "CAPTURE_FAILED", "capture failed for " + reference);
        }
        if (d.roll < capFail + 0.004) {
            return new CaptureResult(true, amountPaise / 2, "PARTIAL", d.latencyMs);
        }
        return new CaptureResult(true, amountPaise, null, d.latencyMs);
    }

    @Override
    public boolean refund(String gateway, String reference, long amountPaise) {
        return true;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(Math.min(ms, 3000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
