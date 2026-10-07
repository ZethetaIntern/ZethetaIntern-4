package com.payflow.domain;

/** Outcome of a single gateway call (auth or capture attempt). */
public enum AttemptOutcome {
    SUCCESS,
    /** UPI collect accepted; customer approval pending. */
    PENDING,
    /** Hard decline from issuer/gateway (insufficient funds, fraud). Never retried. */
    DECLINED,
    TIMEOUT,
    /** Connection refused / DNS failure: fail over immediately. */
    UNREACHABLE,
    /** HTTP 5xx from the gateway. */
    SERVER_ERROR,
    /** HTTP 429 from the gateway (or local rate limiter). Not a health failure. */
    RATE_LIMITED;

    /** True when the attempt says something about gateway health (feeds the circuit breaker). */
    public boolean isHealthFailure() {
        return this == TIMEOUT || this == UNREACHABLE || this == SERVER_ERROR;
    }
}
