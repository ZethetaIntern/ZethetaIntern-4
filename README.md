# PayFlow – Payment Orchestration Layer

[![CI](https://github.com/ZethetaIntern/ZethetaIntern-4/actions/workflows/ci.yml/badge.svg)](https://github.com/ZethetaIntern/ZethetaIntern-4/actions/workflows/ci.yml)

A payment orchestration back-end that routes transactions across Razorpay, Stripe, PayU and UPI
(simulated) by success rate, latency, cost, health and payment-method fit. It fails over within
2 seconds, keeps every request idempotent, ingests and deduplicates webhooks, reconciles against
gateway status and settlement data, and records every state change in an immutable audit trail.

Java 21 · Spring Boot 3.3 · PostgreSQL 15 · Flyway · Docker Compose.

## Quick start

```bash
docker compose up --build
```

- API: `http://localhost:8080`
- Health: `GET http://localhost:8080/api/v1/health` (public)
- Swagger UI: `http://localhost:8080/swagger-ui.html`, OpenAPI JSON at `/v3/api-docs`
  (exported to `docs/api-specification.yaml`)
- Every `/api/v1/**` call except health and webhooks needs `X-API-Key: pk_test_payflow`

### Without Docker

Requires JDK 21 and PostgreSQL 15. Make sure `JAVA_HOME` points to JDK 21 (a JDK 17 `JAVA_HOME`
makes Maven's test JVM fail with `UnsupportedClassVersionError`).

```bash
createdb payflow
export PAYFLOW_DB_URL=jdbc:postgresql://localhost:5432/payflow PAYFLOW_DB_USER=payflow PAYFLOW_DB_PASSWORD=payflow
mvn spring-boot:run
```

Flyway creates the schema and seeds gateways, routing weights, circuit-breaker defaults and the
A3.4 historical dataset on start-up.

### Tests

```bash
mvn verify
```

Runs 728 tests against an embedded PostgreSQL 15 (zonky embedded-postgres; no Docker needed) and
enforces ≥80% line coverage (current: 89.4%). The 15 failure scenarios are in
`src/test/java/com/payflow/FailureScenarioTest.java`.

## Examples

```bash
H='-H X-API-Key:pk_test_payflow -H Content-Type:application/json'

# Create a payment (amounts are integer paise: ₹1,200.00 = 120000)
curl -s $H -H "Idempotency-Key: $(uuidgen)" localhost:8080/api/v1/payments \
  -d '{"merchant_order_id":"ORD-1001","amount_paise":120000,"currency":"INR","payment_method":"CARD","capture_mode":"MANUAL"}'

# Capture ₹800 of the ₹1,200 hold (FS-05), then release the rest
curl -s $H localhost:8080/api/v1/payments/<id>/capture -d '{"amount_paise":80000}'
curl -s $H -X POST localhost:8080/api/v1/payments/<id>/void

# Refund
curl -s $H localhost:8080/api/v1/payments/<id>/refund -d '{"amount_paise":20000,"reason":"returned"}'

# Make Razorpay time out so the payment fails over (FS-01)
curl -s $H -H "Idempotency-Key: $(uuidgen)" -H "X-Mock-Response: razorpay=timeout" \
  localhost:8080/api/v1/payments -d '{"merchant_order_id":"ORD-1002","amount_paise":50000,"payment_method":"CARD"}'

# Sign a webhook body the way a gateway would, then deliver it
BODY='{"event_id":"evt_1","status":"captured","gateway_reference":"pay_xxx","amount":120000,"currency":"INR"}'
curl -s $H localhost:8080/api/v1/mock/webhooks/razorpay/sign -d "$BODY"   # -> {"X-Razorpay-Signature":"..."}
curl -s -H "X-Razorpay-Signature: <sig>" localhost:8080/api/v1/webhooks/razorpay -d "$BODY"
```

Request bodies use snake_case (camelCase aliases are accepted). `X-Merchant-Id` (default
`default`) scopes payments and idempotency keys per merchant.

## Mock gateway control headers (B4.3)

| Header | Values | Effect |
|---|---|---|
| `X-Mock-Response` | `success`, `timeout`, `server-error`, `decline`, `rate-limit`, `pending` | forced outcome |
| `X-Mock-Delay-Ms` | e.g. `2000` | delay before the response |
| `X-Mock-Gateway-Down` | `true` or a list `razorpay,payu` | connection refused |
| `X-Mock-Gateway` (extension) | gateway name | scope bare values to one gateway |
| `X-Mock-Operation` (extension) | `auth`, `capture`, `refund`, `void`, `status` | scope bare values to one operation |
| `X-Mock-Retry-After` (extension) | seconds | Retry-After returned with `rate-limit` |

Per-target syntax: `X-Mock-Response: razorpay=timeout, payu.capture=server-error, refund=decline`
(most specific wins: `gateway.operation`, `gateway`, `operation`, global).

Harness helpers (`/api/v1/mock`, disabled with `PAYFLOW_MOCK_ENABLED=false`):
`POST /gateways/{g}/status` and `/settlement` (`{"reference","status"}`) change what the gateway's
status API / settlement report says; `GET /gateways/{g}/charges`; `POST /upi/{id}/callback`
(`{"status":"SUCCESS|FAILURE|EXPIRED"}`) sends a signed NPCI callback; `POST /webhooks/{g}/sign`;
`POST /reset` clears simulator state.

## Webhook signatures

| Gateway | Endpoint | Header | Scheme | Secret env var |
|---|---|---|---|---|
| Razorpay | `/api/v1/webhooks/razorpay` | `X-Razorpay-Signature` | hex HMAC-SHA256 of raw body | `PAYFLOW_RAZORPAY_WEBHOOK_SECRET` (`rzp_whsec_test`) |
| Stripe | `/api/v1/webhooks/stripe` | `Stripe-Signature: t=..,v1=..` | hex HMAC-SHA256 of `t.body`, 5-min tolerance | `PAYFLOW_STRIPE_WEBHOOK_SECRET` (`whsec_stripe_test`) |
| PayU | `/api/v1/webhooks/payu` | `X-PayU-Signature` | hex HMAC-SHA512 of raw body | `PAYFLOW_PAYU_WEBHOOK_SALT` (`payu_salt_test`) |
| UPI | `/api/v1/webhooks/upi` | `X-NPCI-Signature` | base64 SHA256withRSA of raw body | key pair in `src/main/resources/keys/` (**mock only**; production loads NPCI's certificate via `payflow.webhooks.upi-public-key-pem`) |

Native payload formats (Razorpay events, Stripe events, PayU and UPI callbacks) are parsed, as is a
generic flat format: `{"event_id","status","gateway_reference","transaction_id","amount","currency"}`.

## Endpoints

| # | Method | Path |
|---|---|---|
| 1 | POST | `/api/v1/payments` |
| 2 | GET | `/api/v1/payments/{id}` |
| 3 | GET | `/api/v1/payments?merchant_order_id=` |
| 4 | POST | `/api/v1/payments/{id}/capture` |
| 5 | POST | `/api/v1/payments/{id}/void` |
| 6 | POST | `/api/v1/payments/{id}/refund` |
| 7 | GET | `/api/v1/payments/{id}/refunds` |
| 8 | GET | `/api/v1/payments/{id}/timeline` |
| 9–12 | POST | `/api/v1/webhooks/{razorpay,stripe,payu,upi}` |
| 13 | GET | `/api/v1/gateways` |
| 14 | GET | `/api/v1/gateways/{name}/health` |
| 15 | GET | `/api/v1/gateways/{name}/metrics` |
| 16 | PUT | `/api/v1/gateways/{name}/config` |
| 17 | GET | `/api/v1/routing/config` |
| 18 | PUT | `/api/v1/routing/config` |
| 19 | POST | `/api/v1/reconciliation/trigger` |
| 20 | GET | `/api/v1/reconciliation/reports/{run_id}` |
| 21 | GET | `/api/v1/analytics/success-rate` |
| 22 | GET | `/api/v1/analytics/volume` |
| 23 | GET | `/api/v1/health` |

Extras: `GET /payments/{id}/routing`, `GET /payments/{id}/attempts`, `GET /gateways/{name}/config`,
`GET /routing/preview`, `/admin/webhooks/dlq` (+ `/{id}/replay`), `/admin/rate-limits`,
`/admin/circuits` (+ `/{gw}/{method}/open|reset`), `/admin/anomalies`, `/admin/security-events`,
`/admin/alerts`, `/admin/db-pool`, `/admin/notifications`, and the `/mock` helpers above.

Errors always use the A7.2 format:
`{"error":{"code","message","details","request_id","trace_id","timestamp"}}`.

## Configuration

| Env var | Default | Meaning |
|---|---|---|
| `PAYFLOW_DB_URL` / `_USER` / `_PASSWORD` | `jdbc:postgresql://localhost:5432/payflow`, `payflow`, `payflow` | primary database (PgBouncer in compose) |
| `PAYFLOW_FLYWAY_URL` | = DB URL | direct PostgreSQL URL for migrations |
| `PAYFLOW_DB_POOL_SIZE` | 20 | Hikari pool size |
| `PAYFLOW_DB_PREPARE_THRESHOLD` | 5 | set 0 behind PgBouncer transaction pooling |
| `PAYFLOW_REPLICA_URL` | empty | optional read replica for read-only work |
| `PAYFLOW_API_KEY` | `pk_test_payflow` | API key |
| `PAYFLOW_ATTEMPT_TIMEOUT_MS` | 1000 | per-gateway authorisation budget |
| `PAYFLOW_FAILOVER_WINDOW_MS` | 2000 | failover window after the first attempt |
| `PAYFLOW_CAPTURE_BACKOFF_MS` | 1000 | capture retry backoff base (1 s, 2 s, 4 s) |
| `PAYFLOW_UPI_COLLECT_WINDOW` | 5m | UPI collect mandate window |
| `PAYFLOW_RECON_STALE_THRESHOLD` | 5m | reconciliation stale threshold |
| `PAYFLOW_RECON_INTERVAL_MS` | 900000 | reconciliation interval |
| `PAYFLOW_*_WEBHOOK_*` | test secrets | see table above |
| `PAYFLOW_MOCK_ENABLED` | true | expose `/api/v1/mock` |
| `PAYFLOW_MOCK_LATENCY` | false | simulate A3.4 latencies |

Routing weights, circuit-breaker thresholds and gateway capabilities are stored in the database and
changed through the API, not environment variables.

## Project structure

```
src/main/java/com/payflow
  domain/ entity/ repository/   model, 19 tables
  statemachine/                 TransactionStateMachine, audit writer
  payment/                      orchestration, capture, refund, API facade
  routing/ ratelimit/           router, circuit breaker, metrics, rate limiter
  gateway/                      PaymentGateway + 4 simulated adapters, mock control
  webhook/ reconciliation/      pipeline and reconciliation engine
  idempotency/ jobs/ db/ tracing/ security/ alert/ notification/ web/ error/ config/
src/main/resources/db/migration V1 schema, V2 seed data
performance/k6-benchmarks.js    load test
docs/                           see below
```

## Documentation

- [Architecture](docs/architecture.md) – components, flows, FS-01..FS-15 mapping, case studies
- [State machine](docs/state-machine.md) – states, transitions, Mermaid diagram, audit trail
- [Routing algorithm](docs/routing-algorithm.md)
- [Deliberate errors in the brief](docs/errors-found.md)
- [ADRs](docs/adr/) – language, gateway simulation, locking/idempotency, webhook pipeline, failover budgets
- [API specification](docs/api-specification.yaml) (OpenAPI 3.0)
- [Database schema (DBML)](docs/schema.dbml)
- [Test coverage report](docs/coverage-report.md)
- [Performance](docs/performance.md)
- [Changelog](CHANGELOG.md)
