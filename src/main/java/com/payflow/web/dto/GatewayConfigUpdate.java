package com.payflow.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Body of {@code PUT /api/v1/gateways/{name}/config}. Every field is optional;
 * circuit breaker fields apply to {@code circuit_payment_method} (default all methods).
 */
@Schema(example = "{\"enabled\":true,\"fee_bps\":190,\"rate_limit_per_sec\":200,"
        + "\"circuit_failure_threshold\":5,\"circuit_open_timeout_ms\":30000,\"circuit_half_open_max_requests\":1}")
public record GatewayConfigUpdate(
        Boolean enabled,
        @Pattern(regexp = "^[A-Z,_ ]+$") String supportedMethods,
        @Pattern(regexp = "^[A-Z, ]+$") String supportedCurrencies,
        @PositiveOrZero @Max(10_000) Integer feeBps,
        @PositiveOrZero Long fixedFeePaise,
        @Positive @Max(10_000) Integer rateLimitPerSec,
        @Pattern(regexp = "TOKEN_BUCKET|SLIDING_WINDOW|RETRY_AFTER_BACKOFF") String rateLimitStrategy,
        Boolean supportsPartialRefund,
        @Min(0) @Max(30) Integer authHoldDays,
        @Pattern(regexp = "^(\\*|CARD|UPI|NETBANKING|WALLET)$") String circuitPaymentMethod,
        @Positive @Max(1000) Integer circuitFailureThreshold,
        @Positive Integer circuitOpenTimeoutMs,
        @Positive @Max(100) Integer circuitHalfOpenMaxRequests) {}
