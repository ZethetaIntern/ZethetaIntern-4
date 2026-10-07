# Performance (B3)

Measured by GitHub Actions on every push to `main` (workflow `.github/workflows/ci.yml`, job
*docker compose up + smoke tests + k6 benchmarks*): the stack is started with `docker compose up`
(PostgreSQL 15 + PgBouncer + app) on a fresh `ubuntu-latest` runner (4 vCPU, 16 GB), then
`performance/k6-benchmarks.js` runs against it. Figures below are from run
[37584733157](https://github.com/ZethetaIntern/ZethetaIntern-4/actions/runs/37584733157), all thresholds passed.

| Metric | Target | Measured (p95) | How it is met / measured |
|---|---|---|---|
| Payment initiation | < 500 ms, request → gateway call initiated | **67 ms** | Reported by the server in `Server-Timing: gateway_initiated;dur=…` (request start → first gateway call) during the 100 req/s load. Full auth + capture round trip: 147 ms p95. One short transaction before the call; routing inputs are cached / in memory; virtual threads. |
| Failover latency | < 2 s after primary failure detection | **1,055 ms** end to end | Primary forced to hang (`X-Mock-Response: <primary>=timeout`). Includes the 1 s attempt budget that detects the timeout, so the alternate gateway answered ~55 ms after detection. Also asserted in `FailureScenarioTest.fs01_timeoutFailsOver`. |
| Webhook processing | < 200 ms, receipt → transition committed | **9.2 ms** | Server-side time from `Server-Timing` (verify → dedup+enqueue → process → commit). |
| Concurrent payments | 100 req/s sustained for 5 min | **100 req/s for 5 min, 0.22 % non-201** | k6 `constant-arrival-rate`. 0.22 % of requests did not return 201 (the threshold is < 1 %); k6 does not break these down by status code. The data-integrity audit after the run found no duplicate charges or amount violations. |
| Idempotency check | < 10 ms | **2.7 ms** (3.3 ms incl. network) | Replays of a completed request; advisory lock + primary-key read + cached response. Also asserted in `IdempotencyIntegrationTest`. |
| Reconciliation | < 30 s for 10,000 transactions | **~3.2 s** | `ReconciliationIntegrationTest.tenThousandTransactionsReconcileWithinThirtySeconds` (keyset paging, one settlement report per gateway per page, set-based `bulkTransition` with batched audit inserts). |
| Startup | healthy < 60 s after `docker compose up` (B4.1) | passes | CI fails the job if `/api/v1/health` is not 200 within 60 s of `docker compose up`. |

The same CI job also runs the B4.2 warm-up (10 payments, all must succeed), smoke tests for
failover, idempotent replay and webhook dedup/signature rejection, and a data-integrity audit
(no duplicate charges, no over-capture/over-refund, no orphaned audit rows).

## Running locally

```bash
docker compose up --build -d
# wait for http://localhost:8080/api/v1/health to return 200
k6 run performance/k6-benchmarks.js
# or one scenario: k6 run -e SCENARIO=load performance/k6-benchmarks.js
```

The scenarios run sequentially: `load` (100 payments/s, 5 min), then `webhooks`, `idempotency`
and `failover`. Thresholds are the B3 targets, so `k6` exits non-zero if any target is missed.
