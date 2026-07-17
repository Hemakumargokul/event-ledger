# Build context is the repository root (the module build needs the parent POM).
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
# POMs first so the dependency layer caches across source-only changes
COPY pom.xml .
COPY account-service/pom.xml account-service/
COPY event-gateway/pom.xml event-gateway/
RUN mvn -q -pl account-service -am dependency:go-offline
COPY account-service/src account-service/src
RUN mvn -q -pl account-service -am package -DskipTests

FROM eclipse-temurin:25-jre
# wget is used by the compose healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
# The runnable jar carries the exec classifier (plain jar is a test
# dependency of the Gateway's integration test)
COPY --from=build /workspace/account-service/target/account-service-*-exec.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
