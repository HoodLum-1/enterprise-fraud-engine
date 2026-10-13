# 🛡️ Enterprise Fraud Rule Engine Service

An enterprise-grade, real-time transaction fraud evaluation and audit service built for high-throughput financial environments. Designed with strict **Hexagonal Architecture (Ports & Adapters)** and **Domain-Driven Design (DDD)** principles, this service evaluates incoming transactions against configurable fraud rules, guarantees idempotency via client-assigned UUIDs, and provides comprehensive immutable audit trails.

---

## 🛠️ Languages & Tools Used
* **Java 21** (Virtual Threads & Record-based immutability)
* **Spring Boot 3** (WebMVC, Data JPA, Validation, Actuator, AOP)
* **PostgreSQL 16 & Flyway** (Relational storage with optimized sliding-window composite indexes)
* **Redis 7** (Caching for sub-50ms historical lookups)
* **Apache Kafka 7.5** (Event streaming for fraud alerts)
* **Resilience4j** (Circuit Breaker, Rate Limiter, Retry)
* **Docker & Docker Compose** (Containerization & multi-stage security hardening)
* **Testcontainers & JUnit 5 / Mockito** (Real-database integration testing)
* **Postman & OpenAPI 3.0 / Swagger UI** (Interactive API testing and documentation)

---

## 🤓 Prerequisites & Assumptions
To build, run, and test this project locally, ensure you have the following installed:
* **Java 21 Development Kit (JDK)**
* **Docker & Docker Compose** (running and available in your CLI)
* **Postman** (optional, for visual API exploration)
* **cURL & Bash** (for executing automated smoke test scripts)
* **Make** (optional, for convenience targets)

---

## 🚀 Starting the Application

### Option A: Quick Start with `run.sh` (Recommended)
The `run.sh` script boots all infrastructure, builds the app, and starts the service with health checks:

```bash
./run.sh
```

This will:
1. Start PostgreSQL, Redis, Kafka, Zookeeper, and Kafka UI via Docker Compose
2. Wait for all health checks to pass
3. Build the JAR with `./mvnw clean package -DskipTests`
4. Start the application and verify `/actuator/health`
5. Print all available endpoints

### Option B: Using Makefile Targets
```bash
# Start all infrastructure (PostgreSQL, Redis, Kafka, Kafka UI)
make up

# Build the application
make build

# Start the application (after 'make up')
make run

# Or do it all in one:
make build && make run
```

### Option C: Manual Docker Compose
```bash
# Start full stack
docker-compose up -d

# Or just the database for local development
docker-compose up -d postgres redis kafka
```

### Option D: Local Development with Maven
```bash
# Start backing services only
docker-compose up -d postgres redis kafka

# Run Spring Boot app
./mvnw spring-boot:run
```

---

## 📍 Service Endpoints

| Service | URL | Description |
|---------|-----|-------------|
| **Fraud Engine API** | `http://localhost:8080` | Main REST API |
| **Swagger UI** | `http://localhost:8080/swagger-ui.html` | Interactive API docs |
| **OpenAPI Spec** | `http://localhost:8080/v3/api-docs` | OpenAPI 3.0 JSON |
| **Actuator Health** | `http://localhost:8080/actuator/health` | Health checks |
| **Prometheus Metrics** | `http://localhost:8080/actuator/prometheus` | Metrics endpoint |
| **Fraud Cases** | `http://localhost:8080/api/v1/fraud-cases` | List, look up, and update fraud-case review status |
| **Kafka UI** | `http://localhost:8085` | Kafka topic browser |
| **PostgreSQL** | `localhost:5432` | Database (user: postgres, pass: postgres) |
| **Redis** | `localhost:6379` | Cache |

---

## 🧪 Testing & Verification

### 1. Automated Smoke Test Script
```bash
./smoke-test.sh
```
Verifies: low-risk evaluation, high-amount rule triggering, idempotency (409), validation (400), alert retrieval, asynchronous fraud-case lookup and status update, and Prometheus metrics.

### 2. Postman Collection
Import `docs/postman/fraud-engine.postman_collection.json` into Postman. The collection includes:
- Pre-configured environment variables
- Auto-generated UUIDs and timestamps via pre-request scripts
- Requests covering clean/high-amount evaluations, duplicate and validation errors, alerts, fraud cases, and actuator endpoints
- Fraud case lookup/status requests; cases appear after asynchronous Kafka processing

### Fraud Case Management

Every evaluated transaction publishes a `TransactionEvaluatedEvent` to Kafka. The Kafka consumer creates a fraud case asynchronously with status `PENDING_REVIEW`, so allow a short delay after evaluation before looking it up. Fraud cases are recorded separately from rule-triggered fraud alerts.

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/api/v1/fraud-cases` | List all cases |
| `GET` | `/api/v1/fraud-cases/transaction/{transactionId}` | Find a case by transaction UUID (`404` if not found) |
| `PATCH` | `/api/v1/fraud-cases/{transactionId}/status?status={status}` | Set status to `PENDING_REVIEW`, `CONFIRMED_FRAUD`, `FALSE_POSITIVE`, or `RESOLVED` |

Case responses include the case and transaction IDs, account ID, total risk score, overall severity, status, and creation/update timestamps. Critical events (severity `CRITICAL` or risk score at least 80) also result in an `ACCOUNT_FREEZE` command being published to Kafka.

### 3. Unit & Integration Tests (Testcontainers)
```bash
# All unit and integration tests
./mvnw test

# Or via Make
make test
```
- **Unit tests**: H2 in-memory DB, no Docker required
- **Integration tests**: Testcontainers spin up real PostgreSQL 16, Redis 7, Kafka 7.5

### 4. Makefile Convenience Targets
```bash
make help      # Show all targets
make up        # Start infrastructure
make down      # Stop infrastructure
make build     # Build JAR (skip tests)
make test      # Run all tests
make smoke     # Run smoke test
make clean     # Clean build + Docker volumes
make logs      # Tail container logs
```

---

## 🏗️ Production Architecture

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   Client    │────▶│  Spring MVC │────▶│  PostgreSQL │
│  (HTTP)     │     │  Controller │     │  (Flyway)   │
└─────────────┘     └──────┬──────┘     └─────────────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │  Redis   │ │  Kafka   │ │Resilience│
        │  Cache   │ │ Producer │ │4j (CB/RL)│
        └──────────┘ └──────────┘ └──────────┘
```

### Core Components
| Layer | Package | Responsibility |
|-------|---------|----------------|
| **Inbound** | `api.controller` | REST endpoints, validation, RFC 7807 errors |
| **Domain** | `domain.model`, `engine.rule` | Immutable records, fraud rules (Chain of Responsibility) |
| **Application** | `engine.service` | Orchestration, `@Transactional`, circuit breaker, rate limiter |
| **Outbound** | `infrastructure.*` | JPA, Redis cache, Kafka producer, mappers |

---

## ⚙️ Configuration

All configuration via `src/main/resources/application.yml` with environment variable overrides:

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/fraud_db}
    username: ${SPRING_DATASOURCE_USERNAME:postgres}
    password: ${SPRING_DATASOURCE_PASSWORD:postgres}
  data:
    redis:
      host: ${SPRING_DATA_REDIS_HOST:localhost}
      port: ${SPRING_DATA_REDIS_PORT:6379}
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
```

### Fraud Rules (configurable)
| Rule | Threshold | Risk Score | Severity |
|------|-----------|------------|----------|
| HighAmountRule | 50,000 | 40 | HIGH |
| TransactionVelocityRule | 3 tx / 2min | 50 | CRITICAL |
| LocationMismatchRule | 10 min gap | 60 | CRITICAL |

---

## 🔄 Flyway Migrations
- `V1__init_schema.sql` — Tables, indexes, constraints
- `V2__seed_demo_data.sql` — Demo account `ACC-DEMO-1001` with sample transactions covering all 3 rule triggers
- `V3__add_fraud_cases_table.sql` — Fraud-case review records and indexes

---

## 🐳 Docker

### Multi-stage Dockerfile
1. **Builder**: `eclipse-temurin:21-jdk-jammy` — compiles with Maven wrapper
2. **Runtime**: `eclipse-temurin:21-jre-jammy` — non-root `appuser`, minimal attack surface

### compose.yaml Services
| Service | Image | Port | Health Check |
|---------|-------|------|--------------|
| postgres | `postgres:16-alpine` | 5432 | `pg_isready` |
| redis | `redis:7-alpine` | 6379 | `redis-cli ping` |
| zookeeper | `confluentinc/cp-zookeeper:7.5.0` | 2181 | — |
| kafka | `confluentinc/cp-kafka:7.5.0` | 9092 | broker API |
| kafka-ui | `provectuslabs/kafka-ui:latest` | 8085 | — |

---

## 📚 Architectural Deep Dive

See [`docs/architecture.md`](docs/architecture.md) for detailed documentation on:
- Hexagonal Architecture layers & boundaries
- Chain of Responsibility fraud rule pattern
- Transactional boundaries & idempotency
- Redis caching strategy
- Kafka event publishing
- Resilience4j fault tolerance
- Database indexing & concurrency