# Changelog

## 1.0.0

- Added configurable multi-factor gateway routing, rolling capture-success and
  authorization-latency metrics, a circuit breaker, and bounded recovery probes.
- Enforced a shared two-second authorization/failover deadline and added a
  delayed-timeout regression test.
- Scoped idempotency locks to the full reservation transaction and serialized
  webhook deduplication per event, with PostgreSQL advisory locks across replicas.
- Added a versioned PostgreSQL Flyway migration and schema validation in the
  PostgreSQL profile; the Docker image now builds from a clean source checkout.
- Added migration validation, entity coverage, and an enforced 80% JaCoCo line
  coverage threshold.
- Kept the gateway adapters deterministic and simulated; live provider
  integrations and credentials are intentionally not included.
