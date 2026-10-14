#!/usr/bin/env bash
# Fraud Engine - Startup Script
# Boots infrastructure, builds the application, and starts the service

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
COMPOSE_FILE="compose.yaml"
JAR_PATTERN="target/fraud-engine-*.jar"
HEALTH_ENDPOINT="http://localhost:8080/actuator/health"
MAX_HEALTH_RETRIES=30
HEALTH_RETRY_INTERVAL=2

log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    if ! command -v docker &> /dev/null; then
        log_error "Docker is not installed or not in PATH"
        exit 1
    fi

    if ! docker compose version &> /dev/null; then
        log_error "Docker Compose is not installed or not in PATH"
        exit 1
    fi

    if ! command -v java &> /dev/null; then
        log_error "Java is not installed or not in PATH"
        exit 1
    fi

    if ! command -v ./mvnw &> /dev/null; then
        log_error "Maven wrapper (./mvnw) not found. Run from project root."
        exit 1
    fi

    # Check Java version
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | sed -E 's/.*version "([0-9]+).*/\1/')
    if [[ "$JAVA_VERSION" -lt 21 ]]; then
        log_warn "Java version $JAVA_VERSION detected. Java 21+ recommended."
    fi

    log_success "Prerequisites check passed"
}

# Start infrastructure
start_infrastructure() {
    log_info "Starting all infrastructure services (Postgres, Redis, Kafka, Keycloak)..."
    docker compose -f "$COMPOSE_FILE" up -d

    log_info "Waiting for PostgreSQL to be healthy..."
    local retries=0
    while [[ $retries -lt $MAX_HEALTH_RETRIES ]]; do
        if docker compose -f "$COMPOSE_FILE" exec -T postgres pg_isready -U postgres -d fraud_db &> /dev/null; then
            log_success "PostgreSQL is ready"
            break
        fi
        retries=$((retries + 1))
        sleep $HEALTH_RETRY_INTERVAL
    done

    if [[ $retries -eq $MAX_HEALTH_RETRIES ]]; then
        log_error "PostgreSQL health check timed out"
        exit 1
    fi

    log_info "Waiting for Redis to be healthy..."
    retries=0
    while [[ $retries -lt $MAX_HEALTH_RETRIES ]]; do
        if docker compose -f "$COMPOSE_FILE" exec -T redis redis-cli ping &> /dev/null; then
            log_success "Redis is ready"
            break
        fi
        retries=$((retries + 1))
        sleep $HEALTH_RETRY_INTERVAL
    done

    log_info "Waiting for Keycloak to be ready..."
    retries=0
    while [[ $retries -lt $MAX_HEALTH_RETRIES ]]; do
        if curl -sf "http://localhost:8180/health/ready" &> /dev/null; then
            log_success "Keycloak is ready"
            break
        fi
        retries=$((retries + 1))
        sleep $HEALTH_RETRY_INTERVAL
    done

    log_success "All infrastructure services are healthy"
}

# Build application
build_application() {
    log_info "Building application with Maven..."
    ./mvnw clean package -DskipTests

    # Find the built JAR
    JAR_FILE=$(ls $JAR_PATTERN 2>/dev/null | head -n 1)
    if [[ -z "$JAR_FILE" ]]; then
        log_error "No JAR file found in target/ after build"
        exit 1
    fi

    log_success "Built: $JAR_FILE"
}

# Start application
start_application() {
    log_info "Starting Fraud Engine application..."
    log_info "JAR: $JAR_FILE"
    log_info "Health endpoint: $HEALTH_ENDPOINT"

    # Run in background so we can monitor health
    SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:postgresql://localhost:5432/fraud_db}" \
        java -jar "$JAR_FILE" &
    APP_PID=$!

    # Trap signals to clean up
    trap 'kill $APP_PID 2>/dev/null; exit 1' INT TERM

    # Wait for health endpoint
    log_info "Waiting for application to start..."
    local retries=0
    while [[ $retries -lt $MAX_HEALTH_RETRIES ]]; do
        if curl -sf "$HEALTH_ENDPOINT" &> /dev/null; then
            log_success "Application is healthy!"
            log_info "Swagger UI: http://localhost:8080/swagger-ui.html"
            log_info "Keycloak Realm: http://localhost:8180/realms/fraud-engine"
            log_info "Fraud Cases API: http://localhost:8080/api/v1/fraud-cases"
            log_info "Kafka UI: http://localhost:8085"
            break
        fi
        retries=$((retries + 1))
        sleep $HEALTH_RETRY_INTERVAL
    done

    if [[ $retries -eq $MAX_HEALTH_RETRIES ]]; then
        log_error "Application health check timed out"
        kill $APP_PID 2>/dev/null
        exit 1
    fi

    # Wait for the application process
    wait $APP_PID
}

# Main execution
main() {
    echo "=========================================="
    echo "  Fraud Engine - Full Stack Startup"
    echo "=========================================="
    echo ""

    check_prerequisites
    start_infrastructure
    build_application
    start_application
}

main "$@"