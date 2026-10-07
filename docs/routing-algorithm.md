# Routing Algorithm

Implementation: `routing/GatewayRouter.java`, inputs from `GatewayMetricsService`,
`CircuitBreakerService`, `GatewayConfigService`, `RoutingConfigService`.

## 1. Eligibility (before scoring)

For a payment `(method, currency, amount)` each row of `gateway_config` is checked in order and
excluded with a reason:

1. `DISABLED` – `enabled = false`
2. `METHOD_NOT_SUPPORTED` – method not in `supported_methods` (UPI is only served by the `upi` gateway, per A3.1)
3. `CURRENCY_NOT_SUPPORTED` – only Stripe accepts non-INR
4. `CIRCUIT_OPEN` – health for `(gateway, method)` is DOWN

Exclusion happens before normalisation so an unusable gateway cannot stretch the min/max range.
If nothing is eligible and at least one exclusion is `CIRCUIT_OPEN`, the payment is parked for an
asynchronous retry; otherwise it fails with `NO_ELIGIBLE_GATEWAY` (`CREATED -> FAILED`, event
`ROUTE_FAILED`).

## 2. Score (A3.2)

```
Score = W_success * SuccessRate
      + W_latency * (1 - NormalizedLatency)
      + W_cost    * (1 - NormalizedCost)
      + W_health  * HealthScore
      + W_fit     * FitScore

NormalizedLatency = (p95 - min_p95) / (max_p95 - min_p95)   over eligible gateways, 0 if max = min
NormalizedCost    = (fee - min_fee) / (max_fee - min_fee)   over eligible gateways, 0 if max = min
fee               = round(amount * fee_bps / 10000) + fixed_fee_paise   (integer paise, for this amount)
HealthScore       = 1.0 HEALTHY, 0.5 DEGRADED, 0.0 DOWN
FitScore          = 1.0 (unsupported gateways were already excluded)
```

Candidates are sorted by score (ties by name). Every candidate's breakdown (inputs, normalised
values, per-factor contribution) is stored in `gateway_routes.breakdown` and returned by
`GET /api/v1/routing/preview` and `GET /api/v1/payments/{id}/routing`.

### Worked example (`GatewayRouterTest.scoresMatchTheFormula`)

Default weights 0.35 / 0.20 / 0.20 / 0.15 / 0.10.

| Gateway | Success | P95 | Fee (paise) | Health | NormLatency | NormCost | Score |
|---|---|---|---|---|---|---|---|
| A | 0.98 | 300 | 5000 | HEALTHY | 0.0 | 0.5 | .343 + .200 + .100 + .150 + .100 = **0.893** |
| B | 0.95 | 500 | 4000 | HEALTHY | 0.5 | 0.0 | .3325 + .100 + .200 + .150 + .100 = **0.8825** |
| C | 0.90 | 700 | 6000 | DEGRADED | 1.0 | 1.0 | .315 + 0 + 0 + .075 + .100 = **0.490** |

Ranking: A, B, C.

## 3. Inputs

### Success rate and P95 latency

`GatewayMetricsService` keeps a 60-minute ring of per-minute buckets per gateway, fed by:

- every authorisation attempt (latency sample + attempt count; 429s are excluded because they are
  capacity signals), and
- every transition to `CAPTURED`/`PARTIALLY_CAPTURED` (success count).

So success rate = payments that reached capture / authorisation attempts in the last `N` minutes
(`window.minutes`, default 15), and P95 is computed from the window's latency samples.

Cold start / thin traffic: the A3.4 historical band for the current hour in Asia/Kolkata
(`gateway_historical_performance`, seeded from the brief) is used as a prior:

```
success_rate = (hist_rate * k + successes) / (k + attempts),  k = window.min_samples (20)
p95          = live p95 if live samples >= k, else a sample-weighted blend of live and historical p95
```

The breakdown reports the source as `HISTORICAL`, `BLENDED` or `LIVE`.

### Health

From `CircuitBreakerService.health(gateway, method)`:

- circuit `OPEN` → `DOWN` (0.0, and excluded)
- circuit `HALF_OPEN` → `DEGRADED` (0.5)
- circuit `CLOSED` but live success rate below `health.degraded_success_rate` (0.90) with at least
  `min_samples` attempts → `DEGRADED`
- otherwise `HEALTHY` (1.0)

## 4. Degraded preference (A3.2)

If the top candidate is DEGRADED:

- it leads the runner-up by ≤ `routing.degraded_margin` (20%) → the runner-up is preferred;
- it leads by more → it is kept only with probability equal to its health score (0.5), otherwise
  the runner-up is used. A degraded gateway therefore gets traffic proportional to its health score
  (FS-07: "PayU receives reduced traffic").

The decision note explains which branch applied.

## 5. Configuration (no redeploy)

`routing_config` (key/value, cached 5 s, updated through `PUT /api/v1/routing/config`):

| Key | Default |
|---|---|
| `weight.success_rate` | 0.35 |
| `weight.latency` | 0.20 |
| `weight.cost` | 0.20 |
| `weight.health` | 0.15 |
| `weight.method_fit` | 0.10 |
| `window.minutes` | 15 |
| `window.min_samples` | 20 |
| `health.degraded_success_rate` | 0.90 |
| `routing.degraded_margin` | 0.20 |

Weights must each be in [0, 1] and sum to 1.0 (± 0.001), else 422 `ROUTING_WEIGHTS_INVALID`.
The PUT body accepts `{"weights": {"success_rate": 0.4, ...}}` or flat keys/aliases.

## 6. Circuit breaker (A3.3)

One circuit per `(gateway, payment_method)` in `circuit_breaker_state` (shared by all instances).
Settings come from `circuit_breaker_config`; `'*'` rows are wildcards and the most specific match
wins. Default `('*','*')`: `failure_threshold` 5, `open_timeout_ms` 30000, `half_open_max_requests` 1.
They are changed through `PUT /api/v1/gateways/{name}/config` (`circuit_payment_method`,
`circuit_failure_threshold`, `circuit_open_timeout_ms`, `circuit_half_open_max_requests`).

- CLOSED: consecutive health failures (timeout, unreachable, 5xx) are counted; a success or a
  decline (the gateway answered) resets the count; at the threshold → OPEN.
- OPEN: requests rejected until `open_timeout_ms` has passed → HALF_OPEN.
- HALF_OPEN: up to `half_open_max_requests` probes; that many successes → CLOSED, any failure → OPEN
  with a fresh timeout. A probe slot stuck longer than the open timeout is reclaimed.

## 7. Rate limiting (A8.4)

`GatewayRateLimiter`, limits from `gateway_config.rate_limit_per_sec` / `rate_limit_strategy`:

| Gateway | Limit | Strategy |
|---|---|---|
| Razorpay | 200/s | `TOKEN_BUCKET` – waits briefly for a token (queued, not dropped) |
| Stripe | 100/s | `RETRY_AFTER_BACKOFF` – token bucket; after a 429 the gateway pauses for max(Retry-After, 250 ms × 2^n + jitter) |
| PayU | 150/s | `SLIDING_WINDOW` – at most N requests in any 1 s window |
| UPI | 100/s | `TOKEN_BUCKET` |

Every strategy honours a gateway's `Retry-After`. The orchestrator waits at most 50 ms for
capacity before moving to the next gateway. Utilisation (e.g. `145/200 req/sec`) is at
`GET /api/v1/admin/rate-limits`.
