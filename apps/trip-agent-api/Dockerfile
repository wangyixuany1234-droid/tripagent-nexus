FROM maven:3.9.11-eclipse-temurin-21-alpine AS build

WORKDIR /workspace
COPY pom.xml pom.xml
COPY apps/trip-agent-api/pom.xml apps/trip-agent-api/pom.xml
RUN mvn -B -ntp -pl apps/trip-agent-api -am dependency:go-offline

COPY packages/api-contract packages/api-contract
COPY apps/trip-agent-api/src apps/trip-agent-api/src
RUN mvn -B -ntp -pl apps/trip-agent-api -am package -DskipTests

FROM eclipse-temurin:21.0.8_9-jre-alpine

RUN apk --no-cache upgrade

RUN addgroup -g 10001 -S tripagent \
    && adduser -u 10001 -S -G tripagent tripagent \
    && mkdir -p /var/lib/tripagent/uploads \
    && chown -R tripagent:tripagent /var/lib/tripagent
WORKDIR /app
COPY --from=build /workspace/apps/trip-agent-api/target/trip-agent-api-*.jar app.jar
USER 10001:10001

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
