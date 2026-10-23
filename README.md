# Enterprise Fraud Engine

Java 21 / Spring Boot service for synchronous transaction evaluation and asynchronous transaction-event ingestion. Evaluation results and alerts are stored in PostgreSQL; Kafka events are delivered through a transactional database outbox. REST APIs are secured with JWT bearer tokens by default.

## Capabilities

- Ordered, configurable rules: high amount, transaction velocity, recent location mismatch, and high-risk merchant category. Category risks can be overridden per category with `fraud.engine.rules.category-risk.overrides`.
- Rule history is read using the incoming event time, before saving the incoming transaction; the current transaction is excluded from its own history.
- Amount thresholds are currency-specific. The default policy supports ZAR (50,000) and USD (2,500); unconfigured currencies are rejected rather than compared as if they were ZAR.
- Velocity counts the incoming transaction plus earlier transactions in the configured window. With the default threshold of 3, 3 total transactions do not trigger and 4 do.
- Gambling and crypto categories add 30 risk points at MEDIUM severity by default.
- Fraud cases and the flagged-notification placeholder log are created only for evaluations that trigger a rule. No external customer-notification provider is integrated. Freeze commands are policy-gated, recorded in `freeze_audit`, and published as typed events.
- Evaluation and freeze events use a PostgreSQL outbox. Publishing is at-least-once and unordered; consumers should use the event ID for deduplication.
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

JWT account ownership is checked against the account claim. Public endpoints are limited to health, Swagger UI, and OpenAPI documentation. Evaluation errors use RFC 7807 problem details: `400` for invalid/unsupported input, `401`/`403` for authentication/authorization, `409` for duplicate transaction IDs, `429` with `Retry-After` for rate limiting, and `503` with `Retry-After` when evaluation is unavailable or the account is busy with other evaluations.

### Kafka contracts

- Input topic (default `transactions-topic`): a JSON transaction request with `id`, `accountId`, `amount`, `currency`, `category`, `merchantName`, `location`, and ISO-8601 `timestamp`. Kafka input is processed by the same evaluation service as REST.
- Evaluation outbox output topic (default `fraud-alerts-topic`): typed `TRANSACTION_EVALUATED` events; the fraud-case consumer ignores clean evaluations.
- Freeze outbox output topic (default `account-actions-topic`): typed `ACCOUNT_FREEZE` commands, created only when the configured freeze policy is met and an audit row is written.

Topic names, rule settings, rate limiting, and freeze policy are configurable in `src/main/resources/application.yml`.

## Tests and developer tools

```bash
./mvnw test        # unit tests only, no Docker needed
./mvnw verify      # unit + integration tests and the coverage gate, what CI runs
docker compose config
```

Unit tests use H2 and run under Surefire. Integration tests use Testcontainers PostgreSQL and Kafka with Flyway enabled and run under Failsafe, so Docker must be available. `application-integration.yml` selects this integration configuration. The coverage gate covers the merged unit and integration coverage.

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

- Main configuration: `src/main/resources/application.yml`. It has **no local defaults**: a deployment must provide `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_KAFKA_BOOTSTRAP_SERVERS` and `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`. The localhost fallbacks live in the `dev` profile only.
- All the variables, with the local development values, are listed in [`.env.example`](.env.example). Copy it to `.env` (`cp .env.example .env`) and load it with `set -a && . ./.env && set +a`. The `.env` file is git ignored, and Docker Compose reads it for the port substitutions.
- Local-only profile and seed: `application-dev.yml` and `src/main/resources/db/dev/`.
- Versioned schema changes: `src/main/resources/db/migration/` (V1-V4, V6 and V7). The historical V2 demo-data migration remains immutable and inserts demo rows into fresh databases; V7 removes those rows again, so a production database is left clean. The V5 development seed is under `src/main/resources/db/dev/` and runs only when the `dev` profile is active, which puts both locations on the Flyway path, so the two must not share a version number.
- Integration tests use Flyway against PostgreSQL and `ddl-auto: validate`; unit tests use H2 with Flyway disabled and `create-drop`.
- Detailed component boundaries, rule semantics, transaction/outbox behavior, and operational trade-offs are in [docs/architecture.md](docs/architecture.md). The decisions are in [docs/adr](docs/adr/), and the measured performance is in [docs/performance.md](docs/performance.md).
- The API contract is [docs/openapi.yaml](docs/openapi.yaml), generated from the running service with `make api-docs` (springdoc exposes it at `/v3/api-docs.yaml`). The curated Postman collection lives in [docs/postman](docs/postman/); `make api-contract` (also a CI step) fails when an endpoint is missing from either artifact.

## Timestamps

A transaction timestamp may be up to `fraud.engine.evaluation.max-future-skew` (5 minutes by default) ahead of the service clock and up to `fraud.engine.evaluation.max-past-skew` (7 days by default) in the past. These bounds absorb producer clock skew and reject nonsense event times with a `400`; within them, backdated events are evaluated against the history as of their own event time.

Backdated transactions within that bound are accepted. They are evaluated against the history as of their own event time, and they are visible to later events of the same account. Every rule window is relative to the incoming event time, never to the wall clock.

## Design decisions

- The outbox prevents a successful database commit from silently losing its corresponding Kafka event, but delivery is at-least-once rather than exactly-once, and unordered across events. The reasoning is in [ADR 0002](docs/adr/0002-outbox-delivery-semantics.md).
- Concurrent evaluations of one account are serialised by a PostgreSQL advisory lock, because a lock-then-read is only correct under `READ COMMITTED`. A contended account is rejected with `503` and a `Retry-After` rather than queueing on the connection pool. The reasoning is in [ADR 0001](docs/adr/0001-per-account-advisory-locking.md).
- There is no Redis cache in front of the history read. The read is served by an index, and the measurements are in [docs/performance.md](docs/performance.md). The reasoning is in [ADR 0003](docs/adr/0003-no-redis-cache.md).
- REST remains available alongside Kafka ingestion for synchronous callers and operational testing.
- Currency policy is intentionally limited to explicitly configured thresholds; add a currency threshold before accepting another currency.
- The in-process rate limiter protects an instance, not a distributed fleet. Production deployments should enforce tenant/client quotas at an API gateway.

## Known limitations

- **Freeze thresholds are placeholders.** The freeze policy (`fraud.engine.freeze-policy`) triggers on a score or severity, and the resulting `ACCOUNT_FREEZE` command is published to Kafka for a downstream system to act on. There is no account state in this service, so a freeze is a command and an audit record, not an enforced block. The thresholds have not been calibrated against real fraud data.
- **No dead letter replay tooling.** Messages that exhaust the Kafka error handler are published to a `<topic>-dlq` topic. They are surfaced by the `fraud_kafka_dlq_messages` metric and the `FraudEngineKafkaDeadLetters` alert, but there is no tool to inspect or replay them; that is a manual operation against the topic.
- **No replay for parked outbox events.** An outbox row that exhausts `fraud.engine.messaging.max-attempts` is parked as `FAILED` and is never retried automatically. Replaying it means updating the row by hand.
- **The case status API is a workflow stub.** `PATCH /api/v1/fraud-cases/{transactionId}/status` moves a case between `PENDING_REVIEW`, `CONFIRMED_FRAUD`, `FALSE_POSITIVE` and `RESOLVED` with optimistic locking. It records who changed the case and when, but there is no assignment, comment, or investigation workflow behind it.
- **The outbox publisher is bounded per poll.** It starts the sends for a claimed `batch-size` batch concurrently, then keeps claiming while batches come back full, up to `max-batches-per-poll` batches per poll. A sustained ingestion rate above the publisher's capacity can still build a backlog; the `FraudEngineOutboxBacklog` alert fires when the oldest pending row is more than five minutes old.
- **No distributed tracing, correlation IDs, or retry annotation** are implemented.
- Local Compose credentials, Keycloak development accounts, and sample data are for development only.
