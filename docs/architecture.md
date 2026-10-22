# Fraud Engine Architecture

## Runtime shape

The service has two inbound adapters: the REST API and a Kafka transaction listener. Both call the same evaluation service. Rule execution and domain records live in `engine` and `domain`; persistence, JWT conversion, Kafka clients, and outbox delivery are infrastructure adapters.

```text
REST / transactions-topic
          |
          v
FraudEvaluationService
   |-- read event-time account history
   |-- evaluate ordered rules
   |-- persist transaction + alerts + outbox row atomically
   `-- return EvaluationSummary
          |
          v
PostgreSQL outbox publisher --acknowledged publish--> Kafka topics
          |
          `-- FraudAlertConsumer: flagged case/notification, audited freeze command
```

This is a layered application with domain-focused packages and ports/adapters at infrastructure boundaries; it is not a claim of strict compile-time hexagonal isolation. REST request/response DTOs are kept separate from domain records and persistence entities.

## Evaluation and rule semantics

`FraudEvaluationService` first checks idempotency, then loads prior account transactions using the incoming transaction's event timestamp and configured `fraud.engine.evaluation.history-window`. The incoming transaction ID is excluded from history. It evaluates before saving the transaction, then persists the transaction, triggered alerts, and `TRANSACTION_EVALUATED` outbox record in one database transaction. A concurrent duplicate that passes the initial check is rejected by the database primary key and normalized to `409 Conflict`.

### Per-account concurrency

Concurrent evaluations of the same account are serialised by a PostgreSQL transaction-scoped advisory lock, taken before the duplicate check and before the history read. Without it, two concurrent evaluations would each read a history that excludes the other's uncommitted write, and the velocity and location-mismatch rules could both be missed.

The lock is acquired with a `lock_timeout` (default `PT2S`, `fraud.engine.lock.timeout`). A contended account fails fast with `AccountLockTimeoutException`, which is reported as `503` with `Retry-After: 1` rather than parking a request on one of the 20 Hikari connections. The timeout is not a `DataAccessException` and is listed in the circuit breaker's `ignoreExceptions`, so contention on one account cannot open the circuit for every other caller.

The evaluation runs at `READ COMMITTED`, pinned on the `@Transactional` annotation, because lock-then-read is only correct under that isolation level. Advisory locks are PostgreSQL-only, so the lock sits behind the `AccountLock` interface and the H2-backed unit tests use a no-op implementation (`fraud.engine.lock.enabled=false`). The decision is recorded in [ADR 0001](adr/0001-per-account-advisory-locking.md).

Rules are independent `FraudRule` components, ordered by Spring `@Order`. Each triggered rule contributes its configured risk points; `FraudEngine` derives overall severity from the combined score. Configuration and enabled flags are in `FraudEngineProperties` and `application.yml`.

| Rule | Inputs and window | Default condition | Default score / severity |
|---|---|---|---|
| `HighAmountRule` | Current amount and currency | Amount exceeds the explicitly configured threshold; default is ZAR 50,000. Unsupported currencies are rejected. | 40 / HIGH |
| `TransactionVelocityRule` | Current transaction plus prior account events in the 2-minute window | More than 3 total transactions; 3 is allowed and the 4th triggers. | 50 / CRITICAL |
| `LocationMismatchRule` | Current location and prior account history at event time | Latest prior transaction differs in location and is within the configured 10-minute gap. Current transaction is never its own comparison event. | 60 / CRITICAL |
| `CategoryBasedRiskRule` | Normalized transaction category | Category is `GAMBLING` or `CRYPTO`. | 30 / MEDIUM |

All windows and thresholds are configurable. Amount comparisons are performed only against a configured threshold for the transaction currency; there is no implicit currency conversion.

## Persistence, event delivery, and case workflow

The evaluation database transaction writes the transaction, its triggered alerts, and a typed event into `event_outbox`. A scheduled publisher claims a batch of due rows in a short transaction, leases them, publishes them to Kafka outside any transaction, and then records the outcome in another short transaction. No database transaction is held open across a Kafka send.

Delivery is at-least-once and unordered. Kafka can receive a duplicate if publishing succeeds but the acknowledgement update fails, and a redelivery or a second publisher may interleave events, so downstream processing must be idempotent by event ID. Rows are keyed by account, so a topic's partitions keep a per-account order in the common case, but it is not guaranteed.

A row that has been claimed `fraud.engine.messaging.max-attempts` times is moved to the `FAILED` parking state with its last error and is never claimed again; this keeps a poison event visible instead of retrying it forever. The lease is `batch size x send timeout + margin`, so a slow but healthy publish is not reclaimed mid-flight, and a publisher that dies leaves the row leased until the lease expires. Delivered rows are removed after `fraud.engine.messaging.retention` in bounded batches. The decision is recorded in [ADR 0002](adr/0002-outbox-delivery-semantics.md).

The transaction input topic defaults to `transactions-topic`. Its JSON payload uses the same fields as `TransactionRequestDto`; the listener invokes the same evaluation path as the REST endpoint. Malformed or repeatedly failing messages follow the configured Kafka error handler/DLQ behavior.

`FraudAlertConsumer` creates a `PENDING_REVIEW` case and logs the flagged-notification placeholder only when the evaluation reports fraud or contains triggered alerts. It does not create a case for a clean evaluation. No external customer-notification provider is integrated. Case status is an enum with explicit allowed transitions; optimistic locking prevents lost concurrent updates, and the updating principal is recorded. Case creation is asynchronous relative to evaluation.

An `ACCOUNT_FREEZE` event is generated only when the configurable freeze policy is met (by default, CRITICAL severity or score at least 80). The consumer persists a `freeze_audit` record and typed outbox command in its database transaction. This provides an audit record and reliable command delivery; the policy should be aligned with the institution's customer-impact and authorization requirements.

## HTTP API and authorization

JWT resource-server security is enabled by default. `SecurityConfig` is stateless and converts realm/client/scope authorities through `JwtAuthConverter`.

| Endpoint | Required access |
|---|---|
| `POST /api/v1/transactions/evaluate` | `TRANSACTION_INGESTOR` or `FRAUD_ANALYST` |
| `GET /api/v1/transactions/{transactionId}` | `FRAUD_ANALYST` or the owning account holder |
| `GET /api/v1/fraud-alerts/account/{accountId}` | `FRAUD_ANALYST` or a caller whose JWT account claim matches the path account |
| `/api/v1/fraud-cases/**` | `FRAUD_ANALYST` |
| Health, Swagger UI, OpenAPI | Public; other endpoints require authentication |

Alert queries accept optional `severity`, `ruleName`, `from`, and `to` filters. Alerts and case lists use Spring Data pagination with a default page size of 20 and maximum size of 100. Transaction lookup returns transaction details and its alerts. API errors are normalized to RFC 7807 `ProblemDetail`; rate limiting returns `429` with `Retry-After`, while circuit-breaker or evaluation unavailability returns `503` with `Retry-After`. Duplicate transaction IDs return `409`.

## Resilience and operational boundaries

Resilience4j applies a process-local rate limiter and circuit breaker to evaluation. A rejected request is not persisted or returned as a clean evaluation: rate-limit rejection is `429`, and other unavailable-engine failures are `503`. The rate limiter is not distributed across replicas; enforce client- or tenant-specific quotas at an API gateway in a multi-instance deployment.

PostgreSQL is the system of record. Flyway migrations run in the main and integration configurations; integration tests use PostgreSQL with Flyway on and Hibernate schema validation. The `dev` profile adds development seed data and disables security for local use. V1-V4 are the production migration path; V2 is an immutable historical migration that inserts demo transactions and alerts into fresh databases. The dev-only `db/dev/V5__seed_development_data.sql` migration is included only by the `dev` profile. The V2 seed is not removed by a later migration, so production deployments that need no demo rows must handle that data explicitly.

Redis was removed because the previous cache was invalidated on every write-path lookup and did not provide a useful hit. No latency claim is made for history retrieval. The outbox addresses DB-to-Kafka dual-write loss, but the service does not claim exactly-once delivery.

Actuator exposes health, info, metrics, and Prometheus endpoints; health details are hidden. The application does not currently implement Retry, virtual threads, OpenTelemetry tracing, or correlation IDs.

### Metrics and alerting

`infrastructure.metrics` publishes low-cardinality metrics: the evaluation duration timer and outcome counter, rejection counters split by reason (rate limit, circuit open, lock timeout, engine unavailable), the per-account lock wait timer, freeze decisions, cases created, outbox publish results, and gauges for the outbox backlog, the age of the oldest pending row and the dead letter depth. `resilience4j-micrometer` publishes the circuit breaker state and the rate limiter availability.

The evaluation timer publishes client-side percentiles and a server-side histogram, so a p95 can be read from the histogram buckets across instances. Prometheus alert rules for the outbox backlog, parked events, dead letters, an open circuit breaker, rate limit rejections and lock contention are in [ops/prometheus/alerts.yml](../ops/prometheus/alerts.yml), with the scrape configuration next to it. The `observability` Compose profile starts a Prometheus with those rules.

## Main components

| Concern | Location |
|---|---|
| REST request/response and RFC 7807 errors | `api.controller`, `api.dto`, `api.exception` |
| Evaluation orchestration and rule components | `engine.service`, `engine.rule` |
| Per-account evaluation lock | `engine.service.AccountLock`, `infrastructure.lock` |
| Domain records and case state | `domain.model` |
| JPA entities, repositories, Flyway migrations | `infrastructure.entity`, `infrastructure.repository`, `db/migration` |
| JWT resource server and account ownership | `infrastructure.config.SecurityConfig`, `infrastructure.security` |
| Kafka input, typed output, outbox, fraud consumer | `infrastructure.messaging`, `engine.service.EvaluationOutboxService` |

## Tests

- Unit tests use H2, disable Flyway, and use Hibernate `create-drop`; they do not prove production-schema compatibility.
- Integration tests use Testcontainers PostgreSQL and Kafka, run Flyway, and set Hibernate to `validate`.
- Integration coverage includes the REST location-mismatch flow, Kafka transaction ingestion, outbox publishing, case/freeze processing, and real HTTP rate-limit and unavailable-engine responses.
- Run all configured tests with `./mvnw test`; Docker is required for the Testcontainers suite.

## Deliberate scope limits

- Only configured currencies are accepted; the default deployment configures ZAR only.
- Kafka ingestion complements rather than replaces the synchronous REST API.
- The outbox gives at-least-once event delivery, not distributed exactly-once semantics.
- The global in-process rate limiter is a protective service limit, not a per-client quota.
- Redis caching, automated OpenTelemetry instrumentation, correlation IDs, and a retry annotation are not implemented.
