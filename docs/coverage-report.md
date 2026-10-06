# Test Coverage

Run `mvnw.cmd verify` on Windows (or `mvn verify` elsewhere). The JaCoCo report
is generated at `target/site/jacoco/index.html`, and the Maven `verify` phase
fails if total line coverage falls below 80%.

The current report records **80.26% line coverage (1,130 of 1,408 lines)** and
**53.56% branch coverage**. The project brief sets an overall 80% coverage
target; the build enforces that target against line coverage. Branch coverage
is reported separately and is not currently at 80%.

The test suite includes all 15 project failure scenarios, a delayed timeout
with alternate-gateway success, rolling-metric and half-open probe tests, and
a PostgreSQL-profile startup test that applies Flyway migrations and validates
the JPA mappings using H2 in PostgreSQL compatibility mode. A real PostgreSQL
server and Docker runtime were not available in the development environment;
those deployments still require a PostgreSQL/Docker integration run.
