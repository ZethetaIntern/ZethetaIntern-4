# PayFlow — Payment Orchestration Layer (Java 21 / Spring Boot / PostgreSQL 15+)

Payment orchestration back-end routing transactions across **Razorpay, Stripe,
PayU and UPI** with intelligent routing, <2s failover, idempotency, two-phase
authorise/capture lifecycle, webhook reconciliation with deduplication, and a
complete audit trail.

Implements the *Payment Orchestration Layer* brief end to end:

| Brief section | Implementation |
|---|---|
| A3 Routing algorithm | `core/RoutingEngine` — weighted score (success/latency/cost/health/method), Laplace prior, circuit breaker, historical dataset seeded into `gateway_routes` |
| A3.3 Weights in DB | `routing_config` table + `GET/PUT /routing/weights` (tunable without redeploy) |
| A4/A5 State machine | `domain/TransactionState` + `service/StateService` — 13 states, validated transitions, immutable `transaction_state_log` |
| A4.2 Locking | Pessimistic lock around transitions only, `@Version` optimistic locking, no locks during gateway I/O |
| A5 Webhook pipeline | `service/WebhookService` — constant-time HMAC (SHA-256/512), `PK(gateway,event_id)` dedup, reconciliation onto the FSM |
| A5.5 Reconciliation | `service/ReconciliationService` — scheduled batch flagging stuck intermediate states |
| A6 Database | PostgreSQL 15+ schema: `docs/db/postgres-schema.sql` |
| A7 API | 16 endpoints, OpenAPI 3.0.1 at `docs/api-specification.yaml` + live docs `/swagger-ui.html`, `/v3/api-docs` |
| Part B FS-01..FS-15 | All 15 failure scenarios covered in `tests/FailureScenarioTests.java` |
| Part D Quality | JaCoCo coverage report (`target/site/jacoco`), Docker + docker-compose |

## Run

```bash
# Tests (H2 in PostgreSQL-compat mode; no external DB required)
mvnw.cmd test          # Windows  (mvn test elsewhere)
# Coverage report: target/site/jacoco/index.html

# App with embedded H2
mvnw.cmd spring-boot:run

# Full stack: PostgreSQL 15 + app
docker compose up --build          # app :8080, postgres :5432
```

Live API docs: http://localhost:8080/swagger-ui.html

## Failure scenarios (Part B) — all passing

| ID | Scenario | Asserted behaviour |
|---|---|---|
| FS-01 | Auth timeout | Failover completes < 2s, TIMEOUT attempt recorded, payment captured |
| FS-02 | Duplicate webhook ×3 | Processed once; duplicates deduplicated |
| FS-03 | Double submit | Second request resolves to the original transaction |
| FS-04 | 5xx on capture | `CAPTURE_FAILED` + audit entry |
| FS-05 | Partial capture | `PARTIALLY_CAPTURED`, captured amount vs hold tracked |
| FS-06 | Webhook before API response | Unknown outcome reconciled to `AUTHORISED` |
| FS-07 | Cascade failure | Circuit breaker opens, gateway excluded from ranking |
| FS-08 | Refund on settled txn | `REFUNDED`, refund records persisted |
| FS-09 | Concurrent idempotency race | Exactly one transaction; loser replays |
| FS-10 | Webhook replay attack | Tampered body rejected (401) |
| FS-11 | Missing settlement | Reconciliation flags the stuck transaction |
| FS-12 | UPI collect timeout | `failed` webhook reconciles to `AUTH_FAILED` |
| FS-13 | Idempotency key collision | Second merchant gets the original transaction |
| FS-14 | Traffic spike (20 concurrent) | All complete without state corruption |
| FS-15 | State machine corruption | `CREATED → REFUNDED` rejected, state unchanged |


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
