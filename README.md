# PayFlow — Payment Orchestration Layer (Java 21 / Spring Boot / MySQL)

Payment orchestration back-end routing transactions across **Razorpay, Stripe,
PayU and UPI** with intelligent routing, <2s failover, idempotency, webhook
reconciliation with deduplication, and a complete audit trail.

Implements the requirements of the *Payment Orchestration Layer* project brief:
routing algorithm (A3) with **DB-stored, hot-tunable weights**, two-phase
authorise/capture lifecycle state machine (A4/A5), webhook ingestion pipeline
with `PK(gateway, event_id)` deduplication (A5.4), pessimistic locking only
around state transitions (A4.2), reconciliation engine (A5.5), and the
full schema set: `transactions`, `transaction_state_log`, `gateway_routes`,
`routing_config`, `gateway_attempts`, `idempotency_keys`,
`processed_webhook_events`, `reconciliation_log`, `refunds`.

> Note: the brief lists PostgreSQL 15+ as its example DB; this implementation
> targets **MySQL 8.4** per requirement (the DDL in `src/main/resources/schema.sql`
> is portable between both). Switching back to PostgreSQL only requires the
> connector + dialect swap.

## Run

```bash
# Tests (H2 in MySQL mode, no external DB needed)
mvn test

# App with embedded H2
mvn spring-boot:run

# Full stack with MySQL 8.4 via Docker
docker compose up --build        # app on :8080
```

## API quick reference

| Method | Path | Description |
|---|---|---|
| POST | `/payments` | Create + process end-to-end (`X-API-Key` + `Idempotency-Key` required) |
| GET | `/payments/{id}` | Transaction + attempts + audit + refunds |
| GET | `/payments/{id}/audit` | Immutable state-transition log |
| GET | `/payments/{id}/attempts` | Gateway attempt records |
| GET | `/payments/order/{merchantOrderId}` | All transactions for an order |
| POST | `/payments/{id}/capture` | Capture an authorised transaction |
| POST | `/payments/{id}/refund` | Full/partial refund |
| GET | `/payments/{id}/refunds` | Refund records |
| POST | `/webhooks/{gateway}` | Ingest webhook (`X-Payflow-Signature`) |
| GET | `/routing/preview?paymentMethod=&amount=` | Live gateway ranking |
| GET/PUT | `/routing/weights` | Read/update routing weights (no redeploy) |
| GET | `/admin/gateways` | Gateway configs + health metrics |
| POST | `/admin/gateways/{gw}/health` | Force health / failover drills |
| POST | `/admin/reconciliation/run` | Trigger reconciliation batch |
| GET | `/admin/reconciliation` | Reconciliation discrepancy log |

Headers: `X-API-Key: pk_test_payflow` (default). Webhook signature:
HMAC-SHA256 (SHA-512 for PayU) of the raw body with `whsec_test_secret`.

## Architecture

See `docs/architecture.md` and `docs/routing-algorithm.md`.

```
POST /payments ─▶ IdempotencyKey store ─▶ RoutingEngine (DB weights + health)
                    │                          │ ranked gateways
                    ▼                          ▼
              Transaction ◀── StateService ◀─ failover loop (2s/attempt)
              (pessimistic lock                 │ Future.get(2s) per gateway
               on transitions only)             ▼
                                         authorize ─▶ capture (two-phase)
POST /webhooks/{gw} ─▶ HMAC verify ─▶ dedup PK(gateway,event_id) ─▶ reconcile
                                                              onto state machine
@Scheduled reconciliation ─▶ flags stuck intermediate states
```

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `PAYFLOW_API_KEY` | `pk_test_payflow` | API auth |
| `PAYFLOW_WEBHOOK_SECRET` | `whsec_test_secret` | Webhook HMAC secret |
| `PAYFLOW_ATTEMPT_TIMEOUT_MS` | `2000` | Per-gateway failover budget |
| `PAYFLOW_MAX_ATTEMPTS` | `3` | Max gateways tried per transaction |
| `PAYFLOW_DB_URL` | H2 in-mem (MySQL mode) | Use MySQL JDBC URL in prod |
| Profile `mysql` | — | Activates MySQL datasource |
