# ADR-001: Java 21, Spring Boot 3.3 and PostgreSQL 15

Status: accepted

## Context

The brief allows Python/FastAPI, Node/TypeScript or Java/Kotlin with Spring Boot (Part E1). The
service is I/O-bound (gateway calls of 200 ms – 30 s), needs strict transactional control over a
state machine, and must sustain 100 payments/s (B3) on a 4 vCPU VM.

## Decision

- **Java 21 + Spring Boot 3.3** (web, data-jpa, validation). Request threads are virtual threads
  (`spring.threads.virtual.enabled`) and gateway calls run on a virtual-thread executor
  (`GatewayCaller`), so blocking on a slow gateway costs no platform thread and a timed-out call can
  be abandoned by interrupting it.
- **PostgreSQL 15** with **Flyway** migrations (`V1__core_schema.sql`, `V2__seed_reference_data.sql`)
  and `ddl-auto=validate`, so entity mappings are checked against the real schema at start-up.
  PostgreSQL features used: `JSONB`, `FOR NO KEY UPDATE`, `FOR UPDATE SKIP LOCKED`,
  `pg_advisory_xact_lock`, `INSERT ... ON CONFLICT DO NOTHING`, `UPDATE ... RETURNING`, CHECK
  constraints and triggers.
- **Tests on real PostgreSQL** via zonky `embedded-postgres` (PostgreSQL 15 binaries from Maven):
  no Docker required, and every run exercises the migrations, locks and JSONB mappings. JUnit 5,
  AssertJ, Mockito, MockMvc; JaCoCo enforces at least 80% line coverage at `mvn verify`.
- **springdoc-openapi** for OpenAPI 3.0; **logstash-logback-encoder** for JSON logs.

## Consequences

- One deployable and one database; horizontal scaling relies on database locks rather than
  in-memory coordination (circuit state, idempotency and queues are all in PostgreSQL).
- JDK 21 is required to build (`JAVA_HOME` must point to it).
- Sliding-window metrics are per instance (in memory); per-minute aggregates are persisted.
