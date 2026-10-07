# ADR-004: Webhook pipeline

Status: accepted

## Context

Webhooks arrive at least once, out of order, late, or never (A5.1). A5.2 asks for signature
verification, deduplication, a persistent queue, a processor, audit logging and notifications; A8.3
asks for a DLQ with replay; B3 asks for receipt-to-commit P95 under 200 ms.

## Decision

- **Table queue** (`webhook_queue`) instead of Redis Streams or LISTEN/NOTIFY: no extra
  infrastructure, the enqueue shares a transaction with the dedup insert, and NOTIFY is not
  persistent (see `errors-found.md`). Consumers claim rows with `FOR UPDATE SKIP LOCKED`; a claimed
  row gets a 60 s lease after which another worker may take it.
- **Dedup**: `INSERT INTO processed_webhook_events ... ON CONFLICT (gateway, event_id) DO NOTHING`;
  0 rows inserted means duplicate, answered with 200 and not processed.
- **Inline fast path**: after enqueueing, the request processes the item itself (to meet 200 ms).
  Under connection-pool pressure (`DatabaseGuard.underPressure()`) it only enqueues and the worker
  (every 1 s) processes it. An inline item gets `next_retry_at = now + 5 s`, so the worker only
  touches it if the fast path failed.
- **Processor** checks transaction id, amount, currency and gateway reference (C4.3), then applies
  transitions with `transitionIfAllowed`. Outcomes: `APPLIED`, `NO_OP` (stale or semantically
  duplicate), `DEFERRED` (event ahead of state; retried after 2 s, 4 s, 8 s), `REJECTED` (DLQ plus a
  security event).
- **DLQ**: reached after `max_retries` (3) or a rejection; every DLQ entry raises an alert; depth at
  `GET /api/v1/admin/webhooks/dlq`, replay with `POST /api/v1/admin/webhooks/dlq/{id}/replay`.
- The payload is stored as JSONB (`{"normalized": …, "raw": …}`). Signatures are not re-verified on
  replay because they were verified at ingestion.

## Consequences

- Queue throughput is bounded by PostgreSQL, which is adequate for about 200K events/day.
- Re-sent events with a new event id but the same meaning (e.g. a refund delivered twice) are caught
  by state checks and `gateway_refund_id`, not by the dedup table.
