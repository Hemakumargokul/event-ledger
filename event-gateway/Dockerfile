# Build context is the repository root (the module build needs the parent POM,
# and the Gateway's test scope depends on the account-service module).
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
# POMs first so the dependency layer caches across source-only changes
COPY pom.xml .
COPY account-service/pom.xml account-service/
COPY event-gateway/pom.xml event-gateway/
RUN mvn -q -pl event-gateway -am dependency:go-offline
COPY account-service/src account-service/src
COPY event-gateway/src event-gateway/src
RUN mvn -q -pl event-gateway -am package -DskipTests

FROM eclipse-temurin:25-jre
# wget is used by the compose healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends wget \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/event-gateway/target/event-gateway-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
