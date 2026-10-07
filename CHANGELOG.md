# Changelog

## 1.0.0

Rework to meet the project brief in full.

### State machine and audit
- 22 states with the brief's names (`ROUTE_SELECTED`, `FAILED`, …) replacing `ROUTING`,
  `RETRYING`, `FAILED_TERMINAL`; added `ABANDONED`, `VOID_INITIATED`, `VOIDED`, `AUTH_EXPIRED`,
  `SETTLED`, `PARTIALLY_REFUNDED`, `REFUND_FAILED`, `DISPUTE_OPENED`, `DISPUTE_RESOLVED`,
  `RECONCILIATION_MISMATCH`. Refunds are no longer allowed from `AUTHORISED`.
- `transaction_state_log` now has the A2.3 columns (`event`, `gateway_reference`,
  `gateway_response` JSONB, `metadata` JSONB, `created_by`), PII-sanitised responses, and a trigger
  that rejects UPDATE/DELETE.
- Rejected transitions are audited as `REJECTED_TRANSITION` and return 409 with the valid
  transitions (FS-15); concurrent duplicates are recorded as `DUPLICATE_TRANSITION_IGNORED` (FS-06).

### Routing and resilience
- A3.2 formula with min-max normalised latency and cost, health 1/0.5/0, method fit, Bayesian blend
  with the A3.4 historical bands; weights and thresholds in key/value `routing_config`.
- Circuit breaker per gateway and payment method with database-stored thresholds; HALF_OPEN probes.
- A1.1 failure matrix (no failover on declines), 1 s attempt budget + 2 s failover window,
  asynchronous retry queue for parked payments, per-gateway rate limiting strategies with Retry-After.
- Capture retries with 1/2/4 s backoff and late-success polling (FS-04); real partial capture and
  remaining-hold release (FS-05); partial/settled refunds, UPI collect flow and expiry (FS-12).

### Idempotency and webhooks
- Idempotency keys keyed `(merchant_id, key)` with cached responses, advisory locks, retry after
  failure; capture/void/refund accept `Idempotency-Key`.
- Per-gateway webhook signatures (HMAC-SHA256, Stripe-Signature with timestamp tolerance,
  HMAC-SHA512, RSA for NPCI) over raw bytes; security audit log with source IP (FS-10).
- Native Razorpay/Stripe/PayU/UPI payload parsing; table-backed event queue, C4.3 checks,
  out-of-order deferral, DLQ with replay, orphan-charge handling.

### Reconciliation
- Stale-transaction polling with `RECONCILIATION_OVERRIDE`, settlement matching with bulk
  `SETTLED` transitions, anomalies + alerts without automatic refunds (FS-11), run reports.
  10,000 transactions reconcile in about 3 s.

### Platform
- Schema rewritten with the A6.1 table names (19 tables), all money as BIGINT paise.
- Structured JSON logs with `trace_id`, trace propagation to gateways, audit and webhooks (A8.5).
- FS-14: fail-fast Hikari pool, database circuit breaker returning 503, PgBouncer in compose,
  optional read replica routing, webhook backpressure, asynchronous logging.
- A7.2 error format everywhere, request validation, OpenAPI 3.0 export, merchant scoping.
- Tests run on embedded PostgreSQL 15: 728 tests, 89.4% line coverage.

### Earlier history
- Initial Spring Boot implementation with DB-configurable routing, webhook dedup and
  reconciliation; PostgreSQL migration and JaCoCo threshold added later. Superseded by the above.
