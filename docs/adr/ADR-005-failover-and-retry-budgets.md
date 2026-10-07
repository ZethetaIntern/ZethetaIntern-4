# ADR-005: Failover and retry budgets

Status: accepted

## Context

Gateways time out after 30–60 s (A1.3), but failover must finish within 2 s (FS-01, B3). Retry
storms caused the C1 incident. A1.1 prescribes different handling per failure type.

## Decision

- **Per-attempt budget** `payflow.orchestration.attempt-timeout-ms` = 1000 ms. `GatewayCaller`
  runs each call on a virtual thread and abandons it at the budget (interrupt), reporting `TIMEOUT`.
- **Failover window** `failover-window-ms` = 2000 ms on top of the first attempt's budget, with at
  most `max-gateway-attempts` (3) gateways per synchronous pass. A hanging primary is detected at
  1 s and the alternate answers within the following 2 s.
- **A1.1 matrix**: unreachable or timeout → fail over; 5xx → one retry on the same gateway after
  100–150 ms if the budget allows, then fail over; 429 → pause that gateway for Retry-After (Stripe
  adds exponential backoff with jitter) and fail over; decline → no retry, `FAILED`.
- **Asynchronous retry queue (C1.4)**: when a pass ends without success for retryable reasons, the
  payment is parked in `ROUTE_SELECTED` with
  `next_retry_at = now + max(Retry-After, base · 2^(n-1)) + jitter` (base 1 s). The payment retry
  worker picks it up; after 3 retries it becomes `FAILED` with `MAX_RETRIES_EXCEEDED`. The API
  answers 202 meanwhile. The customer is not charged twice because each gateway always receives the
  same idempotency token (the transaction id).
- **Capture (FS-04)**: up to 3 retries with backoff 1 s, 2 s, 4 s (`capture-backoff-ms` × 2^n), then
  `CAPTURE_FAILED` and a status poll for a late success.
- **Orphans**: a gateway that timed out may still have authorised. Its late webhook is detected as
  `ORPHAN_GATEWAY_CHARGE` and the orphan authorisation is voided.

## Consequences

- With default budgets a synchronous payment request is bounded at about 3 s of authorisation time,
  plus capture retries in the worst case (about 7 s for FS-04).
- Tests shrink the budgets in `src/test/resources/application-test.yml` to keep the suite fast.
