package com.payflow.gateway;

import com.payflow.domain.AttemptOutcome;

/**
 * A failed gateway call, classified so the orchestrator can apply the A1.1
 * failure matrix: unreachable/timeout fail over immediately, 5xx is retried
 * then failed over, 429 respects Retry-After, a decline is never retried.
 */
public class GatewayException extends Exception {

    private final String gateway;
    private final AttemptOutcome kind;
    private final String gatewayErrorCode;
    private final String gatewayErrorDescription;
    private final Integer httpStatus;
    private final long retryAfterMs;

    public GatewayException(String gateway, AttemptOutcome kind, String gatewayErrorCode,
                            String gatewayErrorDescription, Integer httpStatus, long retryAfterMs) {
        super(gateway + " " + kind + ": " + gatewayErrorCode + " " + gatewayErrorDescription);
        this.gateway = gateway;
        this.kind = kind;
        this.gatewayErrorCode = gatewayErrorCode;
        this.gatewayErrorDescription = gatewayErrorDescription;
        this.httpStatus = httpStatus;
        this.retryAfterMs = retryAfterMs;
    }

    public static GatewayException timeout(String gateway, long budgetMs) {
        return new GatewayException(gateway, AttemptOutcome.TIMEOUT, "GATEWAY_TIMEOUT",
                "no response within " + budgetMs + " ms", null, 0);
    }

    public static GatewayException unreachable(String gateway) {
        return new GatewayException(gateway, AttemptOutcome.UNREACHABLE, "CONNECTION_REFUSED",
                "gateway host unreachable", null, 0);
    }

    public static GatewayException serverError(String gateway, int httpStatus, String description) {
        return new GatewayException(gateway, AttemptOutcome.SERVER_ERROR, "HTTP_" + httpStatus, description,
                httpStatus, 0);
    }

    public static GatewayException rateLimited(String gateway, long retryAfterMs) {
        return new GatewayException(gateway, AttemptOutcome.RATE_LIMITED, "HTTP_429",
                "rate limit exceeded, retry after " + retryAfterMs + " ms", 429, retryAfterMs);
    }

    public static GatewayException declined(String gateway, String code, String description) {
        return new GatewayException(gateway, AttemptOutcome.DECLINED, code, description, 400, 0);
    }

    /** True when trying again (same or another gateway) can succeed. */
    public boolean isRetryable() {
        return kind != AttemptOutcome.DECLINED;
    }

    public String getGateway() { return gateway; }
    public AttemptOutcome getKind() { return kind; }
    public String getGatewayErrorCode() { return gatewayErrorCode; }
    public String getGatewayErrorDescription() { return gatewayErrorDescription; }
    public Integer getHttpStatus() { return httpStatus; }
    public long getRetryAfterMs() { return retryAfterMs; }
}
