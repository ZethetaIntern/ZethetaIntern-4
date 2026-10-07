# Deliberate Errors Found in the Brief (Part C6)

Five technical errors, each with the correction applied in this implementation, followed by other
inconsistencies we had to resolve.

## 1. A5.3 – Razorpay signature computed over `JSON.stringify(body)`

**Error.** The sample verifies `HMAC-SHA256(secret, JSON.stringify(body))`, i.e. over a
re-serialised copy of the already-parsed JSON.

**Why it is wrong.** The gateway signs the exact bytes it sent. Parsing and re-serialising changes
whitespace, key order, number formatting and unicode escaping, so genuine webhooks fail verification
(or, worse, teams "fix" it by loosening verification). The sample also passes the two hex strings to
`crypto.timingSafeEqual`, which throws when the buffers differ in length instead of returning false.

**Correction.** `WebhookController` reads the body as `byte[]`; `WebhookSignatureVerifier` computes
the HMAC over those raw bytes and compares with `MessageDigest.isEqual` (constant time, returns
false on length mismatch). Covered by `WebhookSignatureAndParserTest.signatureIsOverRawBytesNotReserialisedJson`.

## 2. A4.2 – partial index `WHERE status != 'COMPLETED'` on `expires_at`

**Error.** The schema creates `idx_idempotency_expires ON idempotency_keys (expires_at) WHERE
status != 'COMPLETED'`, and the next line says a background job runs
`DELETE FROM idempotency_keys WHERE expires_at < NOW()`.

**Why it is wrong.** The cleanup deletes expired rows of every status, and completed keys are the
large majority. A partial index that excludes them cannot be used for that predicate, so the job
sequentially scans a table growing by 100K+ rows/day.

**Correction.** `V1__core_schema.sql` creates a full index on `expires_at`; the purge job runs
`DELETE ... WHERE expires_at < :now` (`IdempotencyKeyRepository.deleteExpired`).

## 3. A8.4 – Stripe rate limits "100 req/sec (test), 10000 (live)"

**Error.** Stripe's documented default limits are roughly 100 read/write operations per second in
live mode and about 25 per second in test mode. The brief inflates live mode by 100× and gives the
live figure for test mode.

**Why it matters.** Configuring 10,000/s for live traffic would let the orchestrator overrun Stripe,
turning 429s into circuit-breaker trips (the self-inflicted outage A8.4 warns about).

**Correction.** Limits live in `gateway_config.rate_limit_per_sec` (Stripe seeded at 100/s, the live
limit) and are changeable at runtime; 429s are handled as capacity signals with Retry-After and
exponential backoff, never as health failures.

## 4. A5.2 – PostgreSQL `LISTEN/NOTIFY` as a "persistent" event queue

**Error.** The pipeline lists "Event Queue (persistent, e.g., PostgreSQL LISTEN/NOTIFY or Redis
Streams)".

**Why it is wrong.** `NOTIFY` is not persistent: a notification is delivered only to sessions
listening at that moment, is lost if no listener is connected or the listener crashes, cannot be
replayed, and has no retry or dead-letter semantics. (It also does not work through PgBouncer in
transaction mode.)

**Correction.** The queue is a table (`webhook_queue`) written in the same transaction as the
dedup insert, consumed with `SELECT ... FOR UPDATE SKIP LOCKED`, with retry counts,
`next_retry_at` backoff and a `DLQ` status (A8.3).

## 5. A3.1 – gateway health described as "Binary (healthy/degraded/down)"

**Error.** The factor table calls health "Binary" and then lists three values; A3.2 assigns
`HealthScore = 1.0 / 0.5 / 0.0`.

**Why it is wrong.** A binary signal cannot express "degraded", which is exactly the half-open
circuit state the router is told to treat specially (prefer the runner-up unless the lead exceeds
20%).

**Correction.** `HealthStatus` is ternary: `DOWN` (circuit OPEN, excluded), `DEGRADED` (HALF_OPEN or
live success rate below `health.degraded_success_rate`), `HEALTHY`; scores 0 / 0.5 / 1.

## Other inconsistencies noted

- **A2.1 vs FS-08.** A2.1 says `REFUND_INITIATED` can fire only from `CAPTURED` or
  `PARTIALLY_CAPTURED`; FS-08 requires `SETTLED -> REFUND_INITIATED -> REFUNDED`. We allow refunds
  from `CAPTURED`, `PARTIALLY_CAPTURED`, `SETTLED`, `PARTIALLY_REFUNDED` and `REFUND_FAILED`.
- **FS-01 vs A2.2.** FS-01 expects `AUTH_INITIATED -> ROUTE_SELECTED` on timeout; the A2.2 table's
  outgoing events for `AUTH_INITIATED` do not include it. We implement FS-01's path.
- **A5.4 vs A5.2.** A5.4 wants the dedup insert and processing in one transaction, while A5.2 puts
  a persistent queue between them. We commit the dedup insert and the queue row atomically (so an
  acknowledged event is never lost), then process in a separate transaction that locks the queue row
  and validates every transition, so re-processing is harmless. With inline processing this still
  happens within the webhook request.
- **A1.3 PayU signature.** The brief says HMAC-SHA512. PayU actually sends a SHA-512 hash of a
  pipe-delimited string that includes the merchant salt (a "reverse hash"), not an HMAC. We follow
  the brief (`X-PayU-Signature` = hex HMAC-SHA512 of the raw body) because the simulated gateway is
  defined by it.
- **B1.1 numbers.** 105,000 transactions/day × ₹2,800 AOV ≈ ₹29.4 Cr/day ≈ ₹882 Cr/month, not
  ₹45 Cr monthly GMV (₹45 Cr would be about 5,400 orders/day).
- **A1.3 UPI partial refunds.** The brief says UPI does not support partial refunds; UPI does allow
  partial refunds in practice. We follow the brief through `gateway_config.supports_partial_refund =
  false` for UPI, which can be flipped without a code change.
