# =============================================================================
# AB Bank Notification Gateway
# Multi-stage build: Gradle build → lean JRE runtime image
# =============================================================================

# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM gradle:8.7-jdk17 AS builder
LABEL stage=builder

WORKDIR /app
COPY settings.gradle build.gradle ./
RUN gradle dependencies --no-daemon -q || true

COPY src ./src
RUN gradle shadowJar --no-daemon -x test

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-jammy AS runtime

LABEL maintainer="AB Bank Engineering <platform@abbank.com>"
LABEL description="AB Bank Notification Gateway — multi-channel alert delivery"
LABEL version="1.0.0"

RUN groupadd -r gateway && useradd -r -g gateway gateway

WORKDIR /app
COPY --from=builder /app/build/libs/notification-gateway-service-*.jar notification-gateway-service.jar

USER gateway

EXPOSE 8081

ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:InitialRAMPercentage=50.0 \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom"

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -sf http://localhost:8081/health || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar notification-gateway-service.jar"]
