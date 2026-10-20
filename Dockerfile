# Stage 1: Build
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

# Copy maven wrapper and pom.xml first for layer caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw

# Resolve dependencies and fail the image build if resolution fails.
RUN ./mvnw dependency:go-offline -B

# Copy source code and build
COPY src ./src
RUN ./mvnw clean package -DskipTests
RUN java -Djarmode=tools -jar target/fraud-engine-0.0.1-SNAPSHOT.jar extract --layers --destination target/extracted

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy

# Create non-root system user and group
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r appgroup \
    && useradd -r -g appgroup -s /bin/false appuser

WORKDIR /app

COPY --from=builder /build/target/extracted/dependencies/ ./
COPY --from=builder /build/target/extracted/snapshot-dependencies/ ./
COPY --from=builder /build/target/extracted/application/ ./

# Set permissions
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-cp", "/app/fraud-engine-0.0.1-SNAPSHOT.jar:/app/lib/*", "com.capitec.connect.fraudengine.FraudEngineApplication"]
