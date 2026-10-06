# ADR-001: Java and Spring Boot

- **Status:** Accepted
- **Decision:** Implement the orchestration service in Java 21 with Spring Boot,
  Spring Data JPA, and PostgreSQL.

## Context

The project needs a typed lifecycle state machine, REST APIs, transactional
database updates, scheduled reconciliation, and integration tests. These are
well-supported by the Spring ecosystem, while Java records and enums model
payment requests and explicit states cleanly.

## Consequences

Spring provides validation, REST, persistence, scheduling, and test support in
one runtime. PostgreSQL is the production-profile database and H2 in PostgreSQL
compatibility mode supports local tests. Schema changes are versioned with
Flyway; the PostgreSQL profile validates rather than mutates the schema at
startup.
