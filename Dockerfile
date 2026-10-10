# Stage 1: Build
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

# Copy maven wrapper and pom.xml first for layer caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x ./mvnw

# Resolve dependencies
RUN ./mvnw dependency:go-offline -B || true

# Copy source code and build
COPY src ./src
RUN ./mvnw clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy

# Create non-root system user and group
RUN groupadd -r appgroup && useradd -r -g appgroup -s /bin/false appuser

WORKDIR /app

# Copy jar from builder stage
COPY --from=builder /build/target/*.jar app.jar

# Set permissions
RUN chown -R appuser:appgroup /app

USER appuser

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
