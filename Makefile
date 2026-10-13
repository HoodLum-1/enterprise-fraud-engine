# Fraud Engine - Developer Convenience Makefile
# Usage: make <target>

.PHONY: help up down build test smoke clean logs

# Default target
help:
	@echo "Fraud Engine - Developer Commands"
	@echo "=================================="
	@echo ""
	@echo "Infrastructure:"
	@echo "  make up       - Start all infrastructure (PostgreSQL, Redis, Kafka, Kafka UI)"
	@echo "  make down     - Stop all infrastructure"
	@echo "  make logs     - Tail logs from all containers"
	@echo ""
	@echo "Build & Test:"
	@echo "  make build    - Build JAR (skip tests)"
	@echo "  make test     - Run all tests (unit + integration with Testcontainers)"
	@echo "  make smoke    - Run smoke test against running app (evaluation, alerts, fraud cases, metrics)"
	@echo ""
	@echo "Maintenance:"
	@echo "  make clean    - Clean build artifacts and Docker volumes"
	@echo "  make help     - Show this help"
	@echo ""

# Start all infrastructure services
up:
	@echo "Starting infrastructure..."
	docker compose up -d
	@echo "Waiting for services to be healthy..."
	@sleep 5
	@docker compose ps

# Stop all infrastructure services
down:
	@echo "Stopping infrastructure..."
	docker compose down

# Build the application JAR
build:
	@echo "Building application..."
	./mvnw clean package -DskipTests

# Run all tests (unit + integration)
test:
	@echo "Running tests..."
	./mvnw test

# Run smoke test against local application
smoke:
	@echo "Running smoke tests..."
	./smoke-test.sh

# Tail logs from all containers
logs:
	docker compose logs -f

# Clean everything - build artifacts and Docker volumes
clean:
	@echo "Cleaning build artifacts..."
	./mvnw clean
	@echo "Stopping containers and removing volumes..."
	docker compose down -v
	@echo "Removing target directory..."
	rm -rf target

# Quick development cycle: build and run
run: build
	@echo "Starting application..."
	java -jar target/fraud-engine-0.0.1-SNAPSHOT.jar