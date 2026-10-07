package com.payflow.payment;

import com.payflow.domain.AttemptOutcome;
import com.payflow.entity.GatewayAttempt;
import com.payflow.entity.Transaction;
import com.payflow.gateway.GatewayException;
import com.payflow.ratelimit.GatewayRateLimiter;
import com.payflow.repository.GatewayAttemptRepository;
import com.payflow.routing.CircuitBreakerService;
import com.payflow.routing.GatewayMetricsService;
import org.springframework.stereotype.Component;

/**
 * One place that turns a gateway call result into its side effects: the
 * {@code gateway_attempts} row, the sliding-window metrics, the circuit
 * breaker and the rate limiter. Declines prove the gateway is up, so they
 * reset the breaker; 429s are capacity signals handled by the rate limiter.
 */
@Component
public class AttemptRecorder {

    private final GatewayAttemptRepository attempts;
    private final GatewayMetricsService metrics;
    private final CircuitBreakerService circuits;
    private final GatewayRateLimiter rateLimiter;

    public AttemptRecorder(GatewayAttemptRepository attempts, GatewayMetricsService metrics,
                           CircuitBreakerService circuits, GatewayRateLimiter rateLimiter) {
        this.attempts = attempts;
        this.metrics = metrics;
        this.circuits = circuits;
        this.rateLimiter = rateLimiter;
    }

    public void success(Transaction t, String gateway, GatewayAttempt.Operation op, int attemptNo,
                        AttemptOutcome outcome, long latencyMs) {
        attempts.save(new GatewayAttempt(t.getId(), gateway, t.getPaymentMethod(), op, attemptNo, outcome,
                latencyMs, 200, null));
        if (op == GatewayAttempt.Operation.AUTH) metrics.recordAuthAttempt(gateway, outcome, latencyMs);
        circuits.onSuccess(gateway, t.getPaymentMethod());
        rateLimiter.onSuccess(gateway);
    }

    /** Returns the pause applied when the gateway rate-limited us, otherwise 0. */
    public long failure(Transaction t, String gateway, GatewayAttempt.Operation op, int attemptNo,
                        GatewayException e, long latencyMs) {
        attempts.save(new GatewayAttempt(t.getId(), gateway, t.getPaymentMethod(), op, attemptNo, e.getKind(),
                latencyMs, e.getHttpStatus(), e.getGatewayErrorCode()));
        if (op == GatewayAttempt.Operation.AUTH) metrics.recordAuthAttempt(gateway, e.getKind(), latencyMs);
        if (e.getKind().isHealthFailure()) {
            circuits.onFailure(gateway, t.getPaymentMethod());
        } else if (e.getKind() == AttemptOutcome.DECLINED) {
            circuits.onSuccess(gateway, t.getPaymentMethod());
        }
        if (e.getKind() == AttemptOutcome.RATE_LIMITED) {
            return rateLimiter.onRateLimited(gateway, e.getRetryAfterMs());
        }
        return 0;
    }

    public void captured(String gateway) {
        metrics.recordCaptured(gateway);
    }
}
