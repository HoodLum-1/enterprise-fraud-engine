# Enterprise Fraud Engine

Java 21 / Spring Boot service for synchronous transaction evaluation and asynchronous transaction-event ingestion. Evaluation results and alerts are stored in PostgreSQL; Kafka events are delivered through a transactional database outbox. REST APIs are secured with JWT bearer tokens by default.

## Capabilities

- Ordered, configurable rules: high amount, transaction velocity, recent location mismatch, and high-risk merchant category.
- Rule history is read using the incoming event time, before saving the incoming transaction; the current transaction is excluded from its own history.
- Amount thresholds are currency-specific. The default policy supports ZAR with a threshold of ZAR 50,000; unconfigured currencies are rejected rather than compared as if they were ZAR.
- Velocity counts the incoming transaction plus earlier transactions in the configured window. With the default threshold of 3, 3 total transactions do not trigger and 4 do.
- Gambling and crypto categories add 30 risk points at MEDIUM severity by default.
- Fraud cases and the flagged-notification placeholder log are created only for evaluations that trigger a rule. No external customer-notification provider is integrated. Freeze commands are policy-gated, recorded in `freeze_audit`, and published as typed events.
- Evaluation and freeze events use a PostgreSQL outbox. Publishing is at-least-once; consumers should use the event ID for deduplication.
- REST and Kafka ingestion are both supported. Redis is not used.
- Alert and case retrieval are pageable (maximum page size 100); alerts also support severity, rule-name, and event-time filters.

## Run locally

Requirements: Java 21, Docker with the Compose plugin, and `curl`. Compose starts PostgreSQL, Kafka, Keycloak, Kafka UI, and the application.

```bash
docker compose up --build -d
# or
./run.sh
```

The application uses Flyway migrations and validates the database schema. The `postgres_data` volume persists across `docker compose down`; use `docker compose down -v` only when you intentionally want to delete local database data.
If local port 5432 is already occupied, choose another host port, for example `POSTGRES_PORT=55432 docker compose up --build -d`; containers still connect to PostgreSQL on port 5432.

| Service | Local address |
|---|---|
| Fraud Engine | http://localhost:8080 |
| Swagger UI / OpenAPI | http://localhost:8080/swagger-ui.html / `/v3/api-docs` |
| Health (public) / Prometheus (authenticated) | http://localhost:8080/actuator/health / `/actuator/prometheus` |
| Keycloak | http://localhost:8180 |
| Kafka UI | http://localhost:8085 |
| PostgreSQL | `localhost:5432` (`fraud_db`, local credentials in Compose) |

Security is enabled by default. Compose imports a local Keycloak realm. Use a configured token with the appropriate role; the local Keycloak bootstrap credentials and realm are development-only and must not be reused in deployed environments. For isolated local development without JWT, run with the `dev` profile (which disables security and enables development-only seed data):

```bash
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
```

Start only the dependencies first if running the application from Maven:

```bash
docker compose up -d postgres kafka keycloak
SPRING_PROFILES_ACTIVE=dev \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/fraud_db \
SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
./mvnw spring-boot:run
```

Do not enable the `dev` profile in a deployed environment.

## API and authorization

| Method | Path | Access / behavior |
|---|---|---|
| `POST` | `/api/v1/transactions/evaluate` | `TRANSACTION_INGESTOR` or `FRAUD_ANALYST`; evaluates and persists a transaction (`201`) |
| `GET` | `/api/v1/transactions/{transactionId}` | Account owner or analyst; returns transaction details and alerts |
| `GET` | `/api/v1/fraud-alerts/account/{accountId}` | Account owner or analyst; supports `severity`, `ruleName`, `from`, `to`, `page`, `size`, and `sort` |
| `GET` | `/api/v1/fraud-cases` | `FRAUD_ANALYST`; pageable case list |
| `GET` | `/api/v1/fraud-cases/transaction/{transactionId}` | `FRAUD_ANALYST`; retrieves the case associated with a transaction |
| `PATCH` | `/api/v1/fraud-cases/{transactionId}/status?status=...` | `FRAUD_ANALYST`; case-status update records the actor and uses optimistic locking |

JWT account ownership is checked against the account claim. Public endpoints are limited to health, Swagger UI, and OpenAPI documentation. Evaluation errors use RFC 7807 problem details: `400` for invalid/unsupported input, `401`/`403` for authentication/authorization, `409` for duplicate transaction IDs, `429` with `Retry-After` for rate limiting, and `503` with `Retry-After` when evaluation is unavailable.

### Kafka contracts

- Input topic (default `transactions-topic`): a JSON transaction request with `id`, `accountId`, `amount`, `currency`, `category`, `merchantName`, `location`, and ISO-8601 `timestamp`. Kafka input is processed by the same evaluation service as REST.
- Evaluation outbox output topic (default `fraud-alerts-topic`): typed `TRANSACTION_EVALUATED` events; the fraud-case consumer ignores clean evaluations.
- Freeze outbox output topic (default `account-actions-topic`): typed `ACCOUNT_FREEZE` commands, created only when the configured freeze policy is met and an audit row is written.

Topic names, rule settings, rate limiting, and freeze policy are configurable in `src/main/resources/application.yml`.

## Tests and developer tools

```bash
./mvnw test
./mvnw verify
docker compose config
```

Unit tests use H2. Integration tests use Testcontainers PostgreSQL and Kafka with Flyway enabled; Docker must be available. `application-integration.yml` selects this integration configuration.

Authenticated smoke checks require Keycloak analyst credentials:

```bash
KEYCLOAK_USERNAME='your-analyst-user' \
KEYCLOAK_PASSWORD='your-password' \
./smoke-test-with-auth.sh
```

`smoke-test.sh` is for a separately started `dev`-profile instance. The load test needs an authorized JWT when security is enabled:

```bash
AUTH_TOKEN='eyJ...' TOTAL_REQUESTS=100 CONCURRENCY=10 ./load-test.sh
```

The load test is for development verification, not capacity certification; the service rate limiter is process-local and is not a replacement for gateway-level per-client limits.

## Configuration and data

- Main configuration: `src/main/resources/application.yml`.
- Local-only profile and seed: `application-dev.yml` and `src/main/resources/db/dev/`.
- Versioned schema changes: `src/main/resources/db/migration/` (V1-V4). The historical V2 demo-data migration remains immutable and inserts demo rows into fresh databases; it is not removed by a later production migration. The additional V5 development seed is under `src/main/resources/db/dev/` and runs only when the `dev` profile is active.
- Integration tests use Flyway against PostgreSQL and `ddl-auto: validate`; unit tests use H2 with Flyway disabled and `create-drop`.
- Detailed component boundaries, rule semantics, transaction/outbox behavior, and operational trade-offs are in [docs/architecture.md](docs/architecture.md).
- The maintained API contract is [docs/openapi.yaml](docs/openapi.yaml); an importable Postman collection is in [docs/postman](docs/postman/).

## Design decisions and known boundaries

- The outbox prevents a successful database commit from silently losing its corresponding Kafka event, but delivery is at-least-once rather than exactly-once.
- REST remains available alongside Kafka ingestion for synchronous callers and operational testing.
- Currency policy is intentionally limited to explicitly configured thresholds; add a currency threshold before accepting another currency.
- The in-process rate limiter protects an instance, not a distributed fleet. Production deployments should enforce tenant/client quotas at an API gateway.
- No Redis history cache, retry annotation, virtual-thread mode, distributed tracing, or correlation-ID implementation is included.
- Local Compose credentials, Keycloak development accounts, and sample data are for development only.
