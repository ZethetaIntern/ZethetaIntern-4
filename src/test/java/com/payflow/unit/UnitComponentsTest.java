package com.payflow.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.payflow.domain.AttemptOutcome;
import com.payflow.domain.PaymentMethod;
import com.payflow.entity.GatewayConfig;
import com.payflow.error.ApiException;
import com.payflow.error.ErrorCatalog;
import com.payflow.error.ErrorMapper;
import com.payflow.gateway.GatewayStatus;
import com.payflow.gateway.MockControl;
import com.payflow.ratelimit.GatewayRateLimiter;
import com.payflow.repository.GatewayHistoricalPerformanceRepository;
import com.payflow.routing.GatewayConfigService;
import com.payflow.routing.GatewayMetricsService;
import com.payflow.statemachine.InvalidStateTransitionException;
import com.payflow.domain.TransactionState;
import com.payflow.util.PiiSanitizer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/** Plain unit tests for the supporting components (no Spring context). */
class UnitComponentsTest {

    // ------------------------------------------------------------ PII sanitiser
    @Test
    void sanitiserRedactsCardsCvvUpiEmailPhone() {
        String out = PiiSanitizer.sanitize("card 4111 1111 1111 1111 cvv 123 vpa ravi@okicici mail a.b@example.com "
                + "phone +91 9876543210");
        assertThat(out).doesNotContain("4111").doesNotContain("123 ").doesNotContain("ravi@okicici")
                .doesNotContain("example.com").doesNotContain("9876543210");
        assertThat(PiiSanitizer.sanitize((String) null)).isNull();
    }

    @Test
    void sanitiserRedactsNestedMapsByKeyAndValue() {
        Map<String, Object> out = PiiSanitizer.sanitize(Map.of("status", "captured", "card", Map.of("number",
                "4111111111111111", "last4", "1111"), "notes", List.of("call 9876543210"), "email", "x@y.com"));
        assertThat(out).containsEntry("status", "captured").containsEntry("email", PiiSanitizer.REDACTED);
        assertThat(out.get("card")).isEqualTo(PiiSanitizer.REDACTED);
        assertThat(out.get("notes").toString()).doesNotContain("9876543210");
        assertThat(PiiSanitizer.maskCard("4111111111111111")).endsWith("1111").startsWith("****");
        assertThat(PiiSanitizer.maskCard("12")).isEqualTo("[CARD_REDACTED]");
    }

    // ------------------------------------------------------------ mock control headers
    @Test
    void mockControlParsesGlobalAndPerGatewayValues() {
        MockControl global = MockControl.fromHeaders(Map.of("X-Mock-Response", "timeout", "X-Mock-Delay-Ms", "2000"));
        assertThat(global.modeFor("stripe", "auth")).isEqualTo(MockControl.Mode.TIMEOUT);
        assertThat(global.delayMsFor("upi", "capture")).isEqualTo(2000);

        MockControl scoped = MockControl.fromHeaders(Map.of("x-mock-response",
                "razorpay=timeout, payu.capture=server-error, refund=decline"));
        assertThat(scoped.modeFor("razorpay", "auth")).isEqualTo(MockControl.Mode.TIMEOUT);
        assertThat(scoped.modeFor("payu", "auth")).isNull();
        assertThat(scoped.modeFor("payu", "capture")).isEqualTo(MockControl.Mode.SERVER_ERROR);
        assertThat(scoped.modeFor("stripe", "refund")).isEqualTo(MockControl.Mode.DECLINE);

        MockControl target = MockControl.fromHeaders(Map.of("X-Mock-Response", "rate-limit", "X-Mock-Gateway", "stripe",
                "X-Mock-Retry-After", "3"));
        assertThat(target.modeFor("stripe", "auth")).isEqualTo(MockControl.Mode.RATE_LIMIT);
        assertThat(target.modeFor("upi", "auth")).isNull();
        assertThat(target.retryAfterMs()).isEqualTo(3000);
    }

    @Test
    void mockControlGatewayDownVariants() {
        assertThat(MockControl.fromHeaders(Map.of("X-Mock-Gateway-Down", "true")).isDown("upi")).isTrue();
        MockControl some = MockControl.fromHeaders(Map.of("X-Mock-Gateway-Down", "razorpay,payu=true,stripe=false"));
        assertThat(some.isDown("razorpay")).isTrue();
        assertThat(some.isDown("payu")).isTrue();
        assertThat(some.isDown("stripe")).isFalse();
        assertThat(MockControl.fromHeaders(Map.of("X-Mock-Response", "bogus")).isEmpty()).isTrue();
        assertThat(MockControl.none().isEmpty()).isTrue();
        MockControl.bind(some);
        assertThat(MockControl.current()).isSameAs(some);
        MockControl.clear();
        assertThat(MockControl.current().isEmpty()).isTrue();
    }

    // ------------------------------------------------------------ rate limiter
    private GatewayRateLimiter limiter(int limit, GatewayConfig.RateLimitStrategy strategy) {
        GatewayConfig cfg = new GatewayConfig();
        cfg.setGatewayName("gw");
        cfg.setRateLimitPerSec(limit);
        cfg.setRateLimitStrategy(strategy);
        GatewayConfigService svc = mock(GatewayConfigService.class);
        when(svc.find("gw")).thenReturn(Optional.of(cfg));
        when(svc.all()).thenReturn(List.of(cfg));
        return new GatewayRateLimiter(svc);
    }

    @Test
    void tokenBucketAdmitsUpToCapacityThenThrottles() {
        GatewayRateLimiter l = limiter(5, GatewayConfig.RateLimitStrategy.TOKEN_BUCKET);
        for (int i = 0; i < 5; i++) assertThat(l.tryAcquire("gw", 0).admitted()).isTrue();
        GatewayRateLimiter.Admission sixth = l.tryAcquire("gw", 0);
        assertThat(sixth.admitted()).isFalse();
        assertThat(sixth.retryAfterMs()).isPositive();
        assertThat(l.tryAcquire("gw", 500).admitted()).as("queued, not dropped").isTrue();
        assertThat(l.utilisation().get("gw").get("utilisation").toString()).endsWith("/5 req/sec");
    }

    @Test
    void slidingWindowLimitsRequestsPerSecond() {
        GatewayRateLimiter l = limiter(3, GatewayConfig.RateLimitStrategy.SLIDING_WINDOW);
        for (int i = 0; i < 3; i++) assertThat(l.tryAcquire("gw", 0).admitted()).isTrue();
        assertThat(l.tryAcquire("gw", 0).admitted()).isFalse();
    }

    @Test
    void retryAfterPausesTheGatewayWithBackoff() {
        GatewayRateLimiter l = limiter(100, GatewayConfig.RateLimitStrategy.RETRY_AFTER_BACKOFF);
        long pause = l.onRateLimited("gw", 1000);
        assertThat(pause).isGreaterThanOrEqualTo(1000);
        GatewayRateLimiter.Admission a = l.tryAcquire("gw", 0);
        assertThat(a.admitted()).isFalse();
        assertThat(l.utilisation().get("gw")).containsEntry("throttled", true);
        l.onSuccess("gw");
        l.reset();
        assertThat(l.tryAcquire("gw", 0).admitted()).isTrue();
    }

    // ------------------------------------------------------------ sliding-window metrics
    @Test
    void metricsWindowComputesSuccessRateAndP95() {
        GatewayMetricsService m = new GatewayMetricsService(mock(GatewayHistoricalPerformanceRepository.class));
        for (int i = 1; i <= 100; i++) {
            m.recordAuthAttempt("razorpay", AttemptOutcome.SUCCESS, i * 10L);
            if (i <= 90) m.recordCaptured("razorpay");
        }
        m.recordAuthAttempt("razorpay", AttemptOutcome.RATE_LIMITED, 5); // ignored: capacity, not performance
        GatewayMetricsService.WindowStats w = m.window("razorpay", 15);
        assertThat(w.attempts()).isEqualTo(100);
        assertThat(w.successes()).isEqualTo(90);
        assertThat(w.p95LatencyMs()).isEqualTo(950);
        assertThat(w.avgLatencyMs()).isEqualTo(505);

        GatewayMetricsService.Estimate e = m.estimate("razorpay", 15, 20);
        assertThat(e.source()).isEqualTo("LIVE");
        assertThat(e.successRate()).isBetween(0.89, 0.92);
        assertThat(m.estimate("stripe", 15, 20).source()).isEqualTo("HISTORICAL");
        m.reset();
        assertThat(m.window("razorpay", 15).attempts()).isZero();
    }

    // ------------------------------------------------------------ error mapping / catalogue
    @Test
    void errorMapperProducesA72Codes() {
        assertThat(ErrorMapper.map(new ApiException(HttpStatus.CONFLICT, "X", "m")).code()).isEqualTo("X");
        ErrorMapper.Mapped ist = ErrorMapper.map(new InvalidStateTransitionException(UUID.randomUUID(),
                TransactionState.CREATED, TransactionState.REFUNDED, TransactionState.CREATED.validTargets()));
        assertThat(ist.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ist.details()).containsKey("valid_transitions");
        ErrorMapper.Mapped db = ErrorMapper.map(new CannotGetJdbcConnectionException("pool exhausted"));
        assertThat(db.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(ErrorMapper.map(new IllegalArgumentException("bad")).code()).isEqualTo("INVALID_REQUEST");
        assertThat(ErrorMapper.map(new RuntimeException("boom")).status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(ErrorMapper.map(new org.springframework.dao.DataIntegrityViolationException("x")).code())
                .isEqualTo("CONFLICT");
    }

    @Test
    void errorCatalogTranslatesGatewayFailures() {
        assertThat(ErrorCatalog.forAuthFailure(AttemptOutcome.DECLINED, "card_declined").code()).isEqualTo("PAYMENT_AUTH_FAILED");
        assertThat(ErrorCatalog.forAuthFailure(AttemptOutcome.DECLINED, "AUTH_EXPIRED").code()).isEqualTo("PAYMENT_AUTH_EXPIRED");
        assertThat(ErrorCatalog.forAuthFailure(AttemptOutcome.RATE_LIMITED, null).code()).isEqualTo("GATEWAY_CAPACITY_EXCEEDED");
        assertThat(ErrorCatalog.forAuthFailure(AttemptOutcome.TIMEOUT, null).code()).isEqualTo("GATEWAY_UNAVAILABLE");
        assertThat(ErrorCatalog.details("razorpay", "BAD_REQUEST_ERROR", "declined", "try again"))
                .containsKeys("gateway", "gateway_error_code", "gateway_error_description", "suggestion");
    }

    // ------------------------------------------------------------ small domain helpers
    @Test
    void domainParsing() {
        assertThat(PaymentMethod.parse("credit_card")).isEqualTo(PaymentMethod.CARD);
        assertThat(PaymentMethod.parse("nb")).isEqualTo(PaymentMethod.NETBANKING);
        assertThat(PaymentMethod.UPI.code()).isEqualTo("upi");
        assertThat(GatewayStatus.parse("requires_capture")).isEqualTo(GatewayStatus.AUTHORISED);
        assertThat(GatewayStatus.parse("succeeded")).isEqualTo(GatewayStatus.CAPTURED);
        assertThat(GatewayStatus.parse(null)).isEqualTo(GatewayStatus.UNKNOWN);
        assertThat(AttemptOutcome.TIMEOUT.isHealthFailure()).isTrue();
        assertThat(AttemptOutcome.DECLINED.isHealthFailure()).isFalse();
        GatewayConfig g = new GatewayConfig();
        g.setFeeBps(200);
        g.setFixedFeePaise(200);
        assertThat(g.feeFor(250_000)).isEqualTo(5_200); // 2% + ₹2, integer paise
    }
}
