.PHONY: help up down build test smoke smoke-auth clean logs run

help:
	@echo "Fraud Engine - Developer Commands"
	@echo "  make up         Build and start the full Compose stack"
	@echo "  make down       Stop Compose services (keeps database volume)"
	@echo "  make build      Build the application JAR without tests"
	@echo "  make test       Run unit and Testcontainers integration tests"
	@echo "  make smoke      Run smoke tests against a dev-profile instance"
	@echo "  make smoke-auth Run authenticated smoke tests (Keycloak analyst credentials)"
	@echo "  make logs       Tail Compose service logs"
	@echo "  make clean      Clean Maven build output and stop Compose services"

up:
	docker compose up --build -d

down:
	docker compose down

build:
	./mvnw clean package -DskipTests

test:
	./mvnw test

smoke:
	./smoke-test.sh

smoke-auth:
	./smoke-test-with-auth.sh

logs:
	docker compose logs -f

clean:
	./mvnw clean
	docker compose down

run: up
