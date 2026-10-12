# 🛡️ Enterprise Fraud Rule Engine Service

An enterprise-grade, real-time transaction fraud evaluation and audit service built for high-throughput financial environments. Designed with strict **Hexagonal Architecture (Ports & Adapters)** and **Domain-Driven Design (DDD)** principles, this service evaluates incoming transactions against configurable fraud rules, guarantees idempotency via client-assigned UUIDs, and provides comprehensive immutable audit trails.

---

## 🛠️ Languages & Tools Used
* **Java 21** (Virtual Threads & Record-based immutability)
* **Spring Boot 3** (WebMVC, Data JPA, Validation, Actuator)
* **PostgreSQL 16 & Flyway** (Relational storage with optimized sliding-window composite indexes)
* **Docker & Docker Compose** (Containerization & multi-stage security hardening)
* **Testcontainers & JUnit 5 / Mockito** (Real-database integration testing)
* **Postman & OpenAPI 3.0** (Interactive API testing and documentation)

---

## 🤓 Prerequisites & Assumptions
To build, run, and test this project locally, ensure you have the following installed:
* **Java 21 Development Kit (JDK)**
* **Docker & Docker Compose** (running and available in your CLI)
* **Postman** (optional, for visual API exploration)
* **cURL & Bash** (for executing automated smoke test scripts)

---

## 🚀 Starting the Application

You can run the application via **Docker Compose** (recommended for production-like infrastructure) or directly via **Maven**.

### Option A: Running via Docker Compose & Multi-Stage Dockerfile
1. Ensure your Docker daemon is running.
2. Build and start the PostgreSQL database and the application container using Docker Compose:
   ```bash
   docker-compose up --build -d
   ```
The application will start up on port 8080, automatically executing Flyway database migrations against PostgreSQL.

To check application health or view container logs:
```bash
docker logs -f fraud_engine_app
curl http://localhost:8080/actuator/health
```

### Option B: Running Locally via Maven & PostgreSQL Container
Start the backing database container:
```bash
docker-compose up -d postgres
```
Run the Spring Boot application using the Maven wrapper:
```bash
./mvnw spring-boot:run
```
The application will start on port 8080.

---

## 🧪 Testing & Verification

### 1. Automated Smoke Test Script
We provide an automated bash script (`smoke-test.sh`) that verifies low-risk evaluation, high-amount rule triggering, idempotency conflict checks, bean validation failures, alert retrieval, and Prometheus metrics in under 3 seconds:
```bash
./smoke-test.sh
```

### 2. Postman Collection
Open Postman and import the collection located at `docs/postman/fraud-engine.postman_collection.json`.

The collection includes pre-configured environment variables and automated pre-request scripts that generate dynamic UUIDs and timestamps.

Execute the requests sequentially:
1. **Evaluate Clean Low-Risk Transaction** (HTTP 201 Created)
2. **Evaluate High-Amount Transaction** (HTTP 201 Created - triggers HighAmountRule)
3. **Evaluate Duplicate Transaction** (HTTP 409 Conflict - verifies idempotency safeguards)
4. **Validation Failure** (HTTP 400 Bad Request - verifies RFC 7807 error structures)
5. **Get Fraud Alerts for Account** (HTTP 200 OK)
6. **Actuator Health & Prometheus Metrics Endpoints** (HTTP 200 OK)

### 3. Running Unit & Integration Tests (Testcontainers)
To run the complete test suite—including unit tests and Testcontainers-based integration tests that spin up ephemeral PostgreSQL 16 containers:
```bash
./mvnw clean verify
```

---

## 🏛️ Architectural Highlights
* **Hexagonal Architecture**: Strict separation between inbound REST controllers (`api`), domain business models and rules (`domain`, `engine`), and outbound persistence entities (`infrastructure`).
* **Chain of Responsibility Pattern**: Fraud rules (`HighAmountRule`, `TransactionVelocityRule`, `LocationMismatchRule`) are modular, decoupled, and evaluated deterministically via Spring's `@Order` annotations.
* **Idempotency & Concurrency**: Client-assigned UUIDv4 primary keys prevent race conditions and duplicate transaction processing. Duplicate submissions return standard RFC 7807 `409 Conflict` responses.
* **Production Observability**: Exposes Prometheus metrics (`/actuator/prometheus`) and detailed health diagnostics (`/actuator/health`).
* **Security Compliance**: Built using a multi-stage Dockerfile that packages the application under a non-root system user (`appuser`) for enhanced container security.