# ── Stage 1: Build ──────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build
RUN apk add --no-cache cargo musl-dev rust

# Cache Gradle wrapper
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew --version --no-daemon

# Cache dependencies
COPY api/build.gradle.kts api/
COPY shared/build.gradle.kts shared/
COPY gateway/build.gradle.kts gateway/
COPY composeApp/build.gradle.kts composeApp/
RUN ./gradlew :gateway:dependencies --no-daemon || true

# Copy sources and build
COPY api/ api/
COPY shared/ shared/
COPY gateway/ gateway/
COPY composeApp/ composeApp/
COPY sandbox-native/ sandbox-native/
RUN ./gradlew :gateway:shadowJar :composeApp:wasmJsBrowserDistribution --no-daemon -x test

# ── Stage 2: Runtime ────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# Security: run as non-root
RUN apk add --no-cache bubblewrap coreutils git nodejs python3 ripgrep \
    && addgroup -S promethe \
    && adduser -S promethe -G promethe

WORKDIR /app

# Copy the fat JAR from build stage
COPY --from=builder /build/gateway/build/libs/gateway-all.jar app.jar
COPY --from=builder /build/composeApp/build/dist/wasmJs/productionExecutable /app/web

# Runtime data directory (PrometheHome resolves to ~/.promethe/)
RUN mkdir -p /home/promethe/.promethe /run/promethe \
    && chown -R promethe:promethe /app /home/promethe /run/promethe

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

USER promethe

EXPOSE 8080

ENV PROMETHE_WEB_DIR="/app/web"

ENTRYPOINT ["java", "-Xmx512m", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=200", "-XX:+UseStringDeduplication", "-jar", "/app/app.jar"]
