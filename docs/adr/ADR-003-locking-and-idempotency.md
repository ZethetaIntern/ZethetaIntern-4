# ADR-003: Locking and idempotency

Status: accepted

## Context

A8.1 recommends pessimistic locking for transitions without holding locks across gateway calls.
A8.2 recommends advisory locks for idempotency. FS-09 sends identical requests to two servers at
the same instant; FS-13 requires keys scoped per merchant.

## Decision

**State transitions.** Every transition is a short transaction:
`SELECT id FROM transactions WHERE id = ? FOR NO KEY UPDATE`, refresh, validate, update, insert
the audit row, commit. Gateway calls happen between transactions. The entity also carries a
`@Version` column.

We use `FOR NO KEY UPDATE`, not `FOR UPDATE`. `transaction_state_log`, `refunds`, `anomalies` and
others reference `transactions` by foreign key, and an FK insert takes `FOR KEY SHARE` on the
parent row. `FOR UPDATE` conflicts with `KEY SHARE`; `FOR NO KEY UPDATE` does not. This matters
because a rejected transition is audited in a `REQUIRES_NEW` transaction while the caller still
holds the row lock: with `FOR UPDATE` that inner insert would wait on the outer transaction
indefinitely (a self-deadlock). Primary keys never change, so `NO KEY UPDATE` is sufficient.

**Idempotency.** In one transaction:
`pg_advisory_xact_lock(hashtext('idem_' || merchant_id || ':' || key))`, read the key, decide
(proceed / 409 in progress / replay cached response / resume after failure / 422 different body),
and insert the key as `PROCESSING` together with the `CREATED` transaction. The advisory lock is
released at commit; from then on the `PROCESSING` row is the lock. The primary key is
`(merchant_id, key)`, which is also the correctness backstop (`INSERT ... ON CONFLICT DO NOTHING`);
`transactions` additionally has `UNIQUE (merchant_id, idempotency_key)`.

Advisory locks are transaction-scoped, so they work behind PgBouncer in transaction mode.

## Consequences

- Concurrent duplicates serialise on the advisory lock for a few milliseconds; replays are a single
  indexed read (under 10 ms, asserted in `IdempotencyIntegrationTest`).
- `hashtext` is 32-bit; a collision only makes two unrelated keys wait for each other briefly, it
  never merges them, because the row lookup uses the full key.
