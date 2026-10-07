# Performance (B3)

| Metric | Target | How it is met | Measured |
|---|---|---|---|
| Payment initiation P95 | < 500 ms to gateway call | one short transaction (claim key + insert) and in-memory routing inputs (cached config, in-memory metrics windows) before the first gateway call; virtual threads | k6 — **not yet run here** |
| Failover latency | < 2 s | 1 s attempt budget, call abandoned on timeout, alternate tried immediately | asserted in `FailureScenarioTest.fs01_timeoutFailsOver` (whole request < 2 s with the test budget of 600 ms) |
| Webhook processing P95 | < 200 ms receipt → commit | dedup+enqueue in one transaction, inline processing in the same request | k6 — **not yet run here** |
| Concurrent payments | 100 req/s for 5 min | virtual threads, no DB connection held during gateway calls, 20-connection pool behind PgBouncer | k6 — **not yet run here** |
| Idempotency check | < 10 ms | advisory lock + PK read, cached response | asserted in `IdempotencyIntegrationTest.completedRequestReplaysTheCachedResponseQuickly` (best of 5 replays < 10 ms) |
| Reconciliation | < 30 s for 10,000 | keyset paging, one settlement-report call per gateway per page, set-based `bulkTransition` with batched audit inserts | **~3.2 s** for 10,000 in `ReconciliationIntegrationTest.tenThousandTransactionsReconcileWithinThirtySeconds` (embedded PostgreSQL on a laptop) |

## Running the load tests

The k6 numbers have **not** been measured in this development environment (no Docker available
here). To measure them against the containerised system:

```bash
docker compose up --build -d
# wait for http://localhost:8080/api/v1/health to return 200
k6 run performance/k6-benchmarks.js
# or, without a local k6 install:
docker run --rm -i --network host grafana/k6 run - < performance/k6-benchmarks.js
```

`performance/k6-benchmarks.js` contains scenarios for 100 payments/s for 5 minutes, webhook
latency, idempotent replays and failover (`X-Mock-Response: razorpay=timeout`), with thresholds set
to the B3 targets. Record the results here after running them on the target VM (4 vCPU, 8 GB).
