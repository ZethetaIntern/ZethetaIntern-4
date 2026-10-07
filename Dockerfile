# Build stage: dependencies are resolved in their own layer so code changes rebuild fast.
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml checkstyle.xml ./
RUN mvn -B -q dependency:go-offline -DexcludeArtifactIds=embedded-postgres || true
COPY src ./src
RUN mvn -B -DskipTests -Dcheckstyle.skip=true package

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 payflow
COPY --from=build /workspace/target/payflow-orchestration-1.0.0.jar app.jar
USER payflow
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
