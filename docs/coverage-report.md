# Test Coverage Report

Produced by JaCoCo during `mvn verify` (`target/site/jacoco/index.html`). The build fails below 80%
line coverage.

| Metric | Coverage |
|---|---|
| Lines | **89.4%** (3,707 / 4,148) |
| Instructions | 90.1% |
| Branches | 73.0% |

Tests: 728 (all passing), on an embedded PostgreSQL 15.

## Per package (line coverage)

| Package | Lines |
|---|---|
| `alert`, `config`, `domain`, `notification`, `util`, `web.dto` | 100% |
| `routing` | 98.9% |
| `web` | 98.7% |
| `statemachine` | 98.4% |
| `ratelimit` | 97.0% |
| `error` | 96.6% |
| `payment` | 91.2% |
| `gateway` | 89.2% |
| `idempotency` | 88.4% |
| `security` | 86.7% |
| `reconciliation` | 85.2% |
| `webhook` | 84.6% |
| `entity` | 83.0% |
| `tracing` | 75.9% |
| `db` | 66.7% |
| `jobs` | 61.4% |
| `com.payflow` (application class) | 33.3% |

## What is covered, by test class

| Test class | Tests | Scope |
|---|---|---|
| `FailureScenarioTest` | 20 | FS-01..FS-15 end to end over HTTP |
| `TransactionStateTransitionTest` | 549 | 38 named valid + 22 named invalid transitions, every (from, to) pair, terminal states |
| `StateMachineAuditTest` | 9 | audit fields, PII redaction, immutability trigger, rejected/ignored transitions, bulk transitions |
| `GatewayRouterTest` | 7 | formula with hand-computed scores, filtering, degraded-preference rule |
| `RoutingAndCircuitBreakerIntegrationTest` | 12 | DB weights change routing, weight validation, historical prior, circuit CLOSED/OPEN/HALF_OPEN per method |
| `GatewayAdapterIntegrationTest` | 27 | 4 gateways × 5 response types through the state machine, reference formats, gateway idempotency |
| `IdempotencyIntegrationTest` | 9 | replay < 10 ms, mismatch, retry after failure, expiry and purge, merchant scoping |
| `WebhookSignatureAndParserTest` | 22 | 4 signature schemes, tolerance, raw bytes, native payload parsing |
| `WebhookPipelineIntegrationTest` | 13 | C4.3 rejections, out-of-order deferral, DLQ + replay, reversal, dispute, orphan charge |
| `ApiEndpointsIntegrationTest` | 28 | every A7.1 endpoint, happy and error paths, A7.2 format, OpenAPI export |
| `ReconciliationIntegrationTest` | 9 | stale overrides per state, settlement, 10k performance |
| `MaintenanceAndSchemaIntegrationTest` | 12 | background jobs, DB guard, required tables, BIGINT money, audit columns, seed data |
| `UnitComponentsTest` | 11 | PII sanitiser, mock headers, rate limiter strategies, metrics window, error mapping |

## What is not covered, and why

- `ScheduledJobs` (wrappers that only schedule `MaintenanceService` methods) — scheduling is disabled
  in tests so jobs run deterministically; the job logic itself is tested through `MaintenanceService`.
  This is most of the `jobs` gap.
- `PayFlowApplication.main` — Spring tests boot the context without calling `main`.
- The read-replica branch of `DataSourceConfig` (`PAYFLOW_REPLICA_URL` set) and
  `DatabaseGuard.underPressure()` returning true — they need a second database / a saturated pool.
  This is the `db` gap.
- Defensive catch branches: gateway status API unavailable during late-success polling or
  reconciliation, failed orphan void, security-log persistence failure, interrupted sleeps, the
  `TraceContext` fallback for a non-UUID trace id.
- Some webhook processor branches for rare gateway events (void webhook from `AUTHORISED`,
  refund webhook while a synchronous refund is in flight).
- Accessors on entities that the API never reads.
