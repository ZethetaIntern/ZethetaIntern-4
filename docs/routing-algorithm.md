# Routing Algorithm (spec A3)

## Objective

Pick the best gateway per transaction using live success rate, speed, cost and
health, and re-evaluate on every attempt so failover improves as data changes.

## Score

For each candidate gateway `g` and transaction `T`:

```
score(g) = w_success  * success_rate(g)
         + w_latency  * (1 - latency_norm(g))
         + w_cost     * (1 - cost_norm(g, T))
         + w_health   * health(g)
         + w_method   * method_fit(g, T)
```

Default weights (stored in `routing_config`, tunable at runtime via
`PUT /routing/weights` — no redeploy required):

| Weight | Default | Rationale |
|---|---|---|
| success | 0.35 | Losing a payment costs far more than saving fees |
| latency | 0.20 | p95 latency drives conversion |
| cost    | 0.20 | Fees matter at scale |
| health  | 0.15 | Circuit state is a hard-ish signal |
| methodFit | 0.10 | UPI intent should prefer UPI-native rails |

### success_rate

`success / (success + failure)` from `gateway_routes`. Below `MIN_SAMPLES = 10`
observations the live rate is blended with a neutral prior:

```
success_rate = (0.85 * 10 + success) / (10 + total)
```

This prevents brand-new gateways from being either preferred or blacklisted.

### cost_norm

`cost_fraction = costBps/10_000 + fixedCostPaise/amountPaise`, normalised against
a 5% ceiling: `cost_norm = min(1, cost_fraction / 0.05)`.

### latency_norm

`min(1, baseLatencyMs / 2000)` where `baseLatencyMs` is seeded from the
historical hourly dataset (A3.4) and continuously updated with live latencies.

### Exclusions (hard filters, applied before scoring)

1. `healthy = false` or `circuit_open_until` in the future (circuit breaker).
2. Method capability: `payment_method = upi` requires `supports_upi = true`.

### Circuit breaker

5 consecutive failures → gateway marked unhealthy for 30 seconds → excluded
from ranking. Any success resets both the counter and the health flag.

## Failover loop

```
ranked = rank(method, amount)[0..3]
for each gateway in ranked:
    state CREATED -> ROUTING -> AUTH_INITIATED
    try gateway.authorize() with a hard 2s Future.get budget
    success -> AUTHORISED -> CAPTURE_INITIATED -> CAPTURED / PARTIALLY_CAPTURED
    failure/timeout -> record attempt, AUTH_FAILED -> RETRYING -> next gateway
exhausted -> FAILED_TERMINAL
```

The whole authorization and failover loop shares one deadline,
`PAYFLOW_FAILOVER_TIMEOUT_MS` (default 2000ms). Each gateway call is bounded by
the smaller of the remaining time and `PAYFLOW_ATTEMPT_TIMEOUT_MS`; local
rate-limit admission is non-blocking so it cannot extend that deadline. FS-01
uses a delayed primary timeout before succeeding on the alternate gateway and
asserts the complete flow stays **under 2s**.

Success rate is based on transactions reaching `CAPTURED` or `SETTLED` versus
authorization attempts in the preceding 15 minutes. P95 latency uses persisted
authorization-attempt timings from the same window (up to 1,000 observations
per gateway). At cold start, the router uses
the seeded 24-hour hourly history, then the configured baseline if no historical
rows exist. A half-open gateway is limited to one in-flight recovery probe;
when it ranks first, a healthy runner-up is preferred unless the recovering
gateway's score advantage exceeds 20%.

## Worked example (₹2,500 card, cold start)

| Gateway | cost_bps | fixed | p95 | supports UPI | score |
|---|---|---|---|---|---|
| upi      | 0   | ₹0     | 180ms | yes | 0.8275 |
| razorpay | 200 | ₹2     | 520ms | yes | 0.5790 |
| payu     | 180 | ₹1.5   | 750ms | yes | 0.5397 |
| stripe   | 250 | ₹3     | 350ms | no  | 0.5141 |

`upi` wins on cost and speed; `stripe` loses the method-fit term for UPI intent.
After live traffic, success rates and latencies move the ranking automatically —
no configuration change needed.
