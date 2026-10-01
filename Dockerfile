FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/payflow-orchestration-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar", "--spring.profiles.active=mysql"]
