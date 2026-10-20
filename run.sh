#!/usr/bin/env bash
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-compose.yaml}"
HEALTH_ENDPOINT="${HEALTH_ENDPOINT:-http://localhost:8080/actuator/health}"
MAX_HEALTH_RETRIES="${MAX_HEALTH_RETRIES:-60}"
HEALTH_RETRY_INTERVAL="${HEALTH_RETRY_INTERVAL:-2}"

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
    echo "Docker with the Compose plugin is required." >&2
    exit 1
fi

if ! command -v curl >/dev/null 2>&1; then
    echo "curl is required to check application health." >&2
    exit 1
fi

echo "Building and starting the complete Fraud Engine stack..."
docker compose -f "$COMPOSE_FILE" up --build -d

echo "Waiting for the application health endpoint..."
for ((attempt = 1; attempt <= MAX_HEALTH_RETRIES; attempt++)); do
    if curl --fail --silent "$HEALTH_ENDPOINT" >/dev/null; then
        echo "Fraud Engine is healthy at $HEALTH_ENDPOINT"
        echo "Keycloak: http://localhost:8180"
        echo "Kafka UI: http://localhost:8085"
        exit 0
    fi
    sleep "$HEALTH_RETRY_INTERVAL"
done

echo "Application health check timed out. Recent service logs:" >&2
docker compose -f "$COMPOSE_FILE" logs --tail=100 fraud-engine >&2
exit 1
