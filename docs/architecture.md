# Fraud Engine Technical Architecture

### 1. Architectural Overview & Hexagonal Architecture (Ports and Adapters)

The **Fraud Engine** is designed following the **Hexagonal Architecture** (also known as Ports and Adapters) to ensure complete separation of business logic, domain models, and external infrastructure/protocols.

```
+-----------------------------------------------------------------------------------+
|                                 INBOUND ADAPTERS                                  |
|  - REST Controllers (TransactionController, FraudAlertController, FraudCaseController) |
|  - Request / Response DTOs & Jakarta Bean Validation                              |
|  - RFC 7807 Global Exception Handling                                            |
|  - OpenAPI 3.0 / Swagger UI Documentation                                        |
+-----------------------------------------------------------------------------------+
                                          │
                                          ▼ (Inbound Ports / DTOs)
+-----------------------------------------------------------------------------------+
|                              APPLICATION / DOMAIN                                 |
|  - FraudEvaluationService (Orchestrates ingestion, persistence & evaluation)      |
|  - FraudEngine (Rule chain orchestrator)                                          |
|  - Domain Rules (HighAmountRule, TransactionVelocityRule, LocationMismatchRule)   |
|  - Domain Records (Transaction, FraudAlert, FraudCase, RuleEvaluationResult, Severity) |
|  - Resilience4j: Circuit Breaker, Rate Limiter, Retry                            |
+-----------------------------------------------------------------------------------+
                                          │
                ┌───────────────────────────┼───────────────────────────┐
                ▼                           ▼                           ▼
       (Outbound Ports)           (Outbound Ports)           (Outbound Ports)
+-----------------------------------------------------------------------------------+
|                               OUTBOUND ADAPTERS                                   |
|  - Spring Data Repositories (TransactionRepository, FraudAlertRepository, FraudCaseRepository) |
|  - JPA Entities (TransactionEntity, FraudAlertEntity, FraudCaseEntity)            |
|  - PostgreSQL Database & Flyway Schema Migrations (V1, V2, V3)                    |
|  - Redis Cache (Lettuce) — account-history (5-min buckets, 10min TTL)            |
|  - Kafka Producer (Spring Kafka) — fraud-alerts-topic, async CompletableFuture, enable.idempotence, transactional.id |
|  - HikariCP Connection Pooling & Spring Actuator / Prometheus                     |
+-----------------------------------------------------------------------------------+
```

#### Layers & Boundaries:

1. **API / Inbound Adapters (`api.*`)**:
   - HTTP endpoints accepting evaluation requests and providing alert and fraud-case management.
   - Enforces Jakarta Bean Validation on input DTOs (`TransactionRequestDto`).
   - Normalizes errors into standard RFC 7807 `ProblemDetail` structures.
   - Exposes OpenAPI 3.0 spec at `/v3/api-docs` and Swagger UI at `/swagger-ui.html`.

2. **Domain & Engine (`domain.*`, `engine.*`)**:
   - Pure domain business logic and immutable data models (`Transaction`, `FraudAlert`, `FraudCase`, `EvaluationSummary`).
   - Compact constructors enforce validation rules and domain invariants (positive amounts, non-null UUIDs, valid timestamps).
   - `FraudEngine` orchestrates the evaluation without knowing about HTTP or persistence specifics.
   - **Chain of Responsibility** pattern for fraud rules — decoupled, ordered, conditionally activated.

3. **Application Service (`engine.service.FraudEvaluationService`)**:
   - Single `@Transactional` entry point for the full evaluation pipeline.
   - **Resilience4j** fault tolerance: `@CircuitBreaker`, `@RateLimiter`, `@Retry` on `evaluate()`.
   - Fallback method (`evaluateFallback`) provides degraded functionality (basic high-amount check) when the rule engine is unavailable.
   - Idempotency check **before** circuit breaker — business exceptions (`DuplicateTransactionException`, validation errors) are ignored by the breaker via `ignoreExceptions`.
   - Publishes `TransactionEvaluatedEvent` to Kafka asynchronously (non-blocking).
   - Fraud cases are created by the Kafka consumer after the event is consumed; case creation is asynchronous relative to the evaluation response.

4. **Infrastructure / Outbound Adapters (`infrastructure.*`)**:
   - Maps between domain records and database entities using dedicated mappers (`TransactionMapper`, `FraudAlertMapper`, `FraudCaseMapper`).
   - Handles persistence via Spring Data JPA and PostgreSQL.
   - `FraudCaseController` exposes case listing, transaction-ID lookup, and review-status updates under `/api/v1/fraud-cases`.
   - `FraudAlertConsumer` consumes events from `fraud-alerts-topic` and persists one `PENDING_REVIEW` case per transaction if one does not already exist. The case may not be queryable immediately after evaluation returns.
   - Valid case statuses are `PENDING_REVIEW`, `CONFIRMED_FRAUD`, `FALSE_POSITIVE`, and `RESOLVED`.
   - Critical events (severity `CRITICAL` or risk score at least 80) also publish an `ACCOUNT_FREEZE` command to `account-actions-topic`.
   - **Redis Cache** (`CacheConfiguration`): `account-history` cache (10min TTL, 5-minute time buckets) — for sub-50ms historical lookups.
   - **Kafka Producer** (`FraudEventProducer`): async publish to `fraud-alerts-topic` with `CompletableFuture` callback, keyed by `accountId`, idempotent producer with `transactional.id`.
   - Externalizes configuration with `@ConfigurationProperties` (`FraudEngineProperties`).

---

### 2. Chain of Responsibility Pattern for Fraud Rules

The engine uses the **Chain of Responsibility** design pattern to evaluate transactions across a series of decoupled, modular fraud detection rules.

```
Incoming Transaction + 24h Account History
                     │
                     ▼
   +───────────────────────────────────+
   │   FraudEngine Orchestrator        │
   │   - Injects List<FraudRule>       │
   │   - Executes in sorted @Order     │
   +───────────────────────────────────+
         │               │               │
         ▼ (Order 1)     ▼ (Order 2)     ▼ (Order 3)
    ┌───────────┐   ┌───────────┐   ┌───────────┐
    │HighAmount │   │Transaction│   │ Location  │
    │   Rule    │   │ Velocity  │   │ Mismatch  │
    └─────┬─────┘   └─────┬─────┘   └─────┬─────┘
          │               │               │
          └───────────────┼───────────────┘
                          ▼
         Aggregate Risk Score & Severity
                          ▼
                  EvaluationSummary
```

#### Key Characteristics:
- **`FraudRule` Interface**: Standard interface requiring `getRuleName()`, `getOrder()`, and `evaluate(Transaction, List<Transaction>)`.
- **Deterministic Rule Ordering**: Injected via Spring's `@Order` annotations (1→HighAmount, 2→Velocity, 3→LocationMismatch).
- **Dynamic Rule Configuration & Conditional Activation**:
  - Each rule annotated with `@ConditionalOnProperty(name = "fraud.engine.rules.<rule>.enabled", matchIfMissing = true)`.
  - Thresholds, evaluation windows, risk scores, and severities externalized to `application.yml` via `FraudEngineProperties`.
- **Non-blocking Rule Pipeline**: All rules evaluate against the same immutable state; individual triggered rules append evaluation results without halting subsequent rule checks.
- **Rule Wiring Requirement**: Adding a new rule requires three wiring points:
  1. Implement `FraudRule` with `@Component`, `@Order(n)`, `@ConditionalOnProperty(prefix = "fraud.engine.rules.<key>", ...)`
  2. Add nested config class to `FraudEngineProperties`
  3. Add YAML block under `fraud.engine.rules` in `application.yml`
- **Constructor Injection Bug Fix**: Rule constructors taking `FraudEngineProperties` **must** be annotated `@Autowired`; otherwise Spring falls back to the no-arg constructor and YAML config is silently ignored.

---

### 3. Transactional Boundaries & State Management

The transactional lifecycle is managed inside `FraudEvaluationService` using Spring's declarative `@Transactional`:

```
Client Request
      │
      ▼
┌────────────────────────────────────────────────────────────────────────┐
│ FraudEvaluationService.evaluateTransaction() [@Transactional]          │
│                                                                        │
│  1. Idempotency Check:                                                │
│     Verify transaction.id does NOT exist in TransactionRepository.     │
│     -> Throws DuplicateTransactionException (409 Conflict) on match.   │
│                                                                        │
│  2. Ingest Transaction:                                                │
│     Map DTO -> Domain Transaction -> TransactionEntity -> Save.       │
│                                                                        │
│  3. Historical Data Fetch (Cached):                                    │
│     Retrieve accountId history within the configured window.           │
│     -> AccountHistoryCacheService.evictAccountHistoryCache() first,    │
│        then @Cacheable getCachedAccountHistory() (Redis, 10min TTL)     │
│                                                                        │
│  4. Evaluate Rules (Resilience4j Protected):                          │
│     @CircuitBreaker(name="fraudEngine")                                │
│     @RateLimiter(name="fraudEngine")                                   │
│     Execute FraudEngine rule chain.                                    │
│                                                                        │
│  5. Persist Alerts:                                                    │
│     Map triggered alerts -> FraudAlertEntity -> Save all alerts.       │
│                                                                        │
│  6. Async Event Publishing:                                            │
│     fraudEventProducer.publishEvaluationEvent() -> Kafka (async).     │
│                                                                        │
│  7. Return Evaluation Summary.                                         │
└────────────────────────────────────────────────────────────────────────┘
```

After the evaluation event is consumed, `FraudAlertConsumer` persists a fraud case with initial status `PENDING_REVIEW`. This asynchronous work does not delay the transaction evaluation response. The unique constraint on `fraud_cases.transaction_id` and consumer duplicate check prevent duplicate case records.

#### Fraud Case API

| Method | Path | Behavior |
|--------|------|----------|
| `GET` | `/api/v1/fraud-cases` | Returns all fraud cases |
| `GET` | `/api/v1/fraud-cases/transaction/{transactionId}` | Returns the case for a transaction, or `404` if not found |
| `PATCH` | `/api/v1/fraud-cases/{transactionId}/status?status={status}` | Updates the case status; valid values are `PENDING_REVIEW`, `CONFIRMED_FRAUD`, `FALSE_POSITIVE`, and `RESOLVED` |

The case response contains `id`, `transactionId`, `accountId`, `totalRiskScore`, `overallSeverity`, `status`, `createdAt`, and `updatedAt`. Status updates return `400` for an unsupported status and `404` when the transaction has no case.

#### Idempotency & Relational Integrity:
- **Idempotency Guarantee**: Primary key UUID collisions caught early. Concurrent duplicates that race past the check fail on the PK constraint and are normalized to 409 Conflict by `GlobalExceptionHandler` (handles `DataIntegrityViolationException`).
- **Cascading Foreign Keys**: `fraud_alerts.transaction_id` references `transactions.id` with `ON DELETE CASCADE`.
- **ACID Compliance**: Entire evaluation, ingestion, and alert generation cycle in a single atomic DB transaction.
- **Circuit Breaker Business Exception Ignorance**: `DuplicateTransactionException`, `ConstraintViolationException`, `MethodArgumentNotValidException` are in `ignoreExceptions` — they don't trip the breaker.

---

### 4. Concurrency & Performance Optimization

To support high-throughput, low-latency transaction processing:

1. **Connection Pool Tuning (HikariCP)**:
   - `maximum-pool-size`: 20 connections (balance parallelism vs. DB contention).
   - `minimum-idle`: 5 connections (instant workload spikes).
   - `connection-timeout`: 20,000 ms (avoid indefinite thread blocking).

2. **Database Indexing Strategy**:
   - `idx_transactions_account_timestamp` on `transactions(account_id, timestamp)`: Accelerates sliding-window historical lookups (window configurable via `fraud.engine.evaluation.history-window`, default `PT24H`).
   - `idx_fraud_alerts_account_flagged_at` on `fraud_alerts(account_id, flagged_at)`: Optimizes alert retrieval queries ordered chronologically.
   - `idx_fraud_alerts_transaction` on `fraud_alerts(transaction_id)`: Fast lookup by transaction.

3. **Stateless Service Design**:
   - `FraudEngine` and all `FraudRule` beans are stateless Spring singletons — concurrent threads evaluate without synchronization locks.

4. **Redis Caching (`infrastructure.cache.CacheConfiguration`)**:
   - **`account-history`** (10min TTL): Caches `AccountHistoryCacheService.getCachedAccountHistory(accountId, historyCutoff)` results — key: `accountId:cutoffEpochMilli/300000` (5-minute bucket). Sub-50ms for repeated lookups; empty results are never cached (`unless = "#result.isEmpty()"`).
   - **Cache Eviction**: `@CacheEvict` on `evictAccountHistoryCache(...)` with the *same* bucket key, invoked right after the new transaction is inserted and before the cached read — so rule evaluation always sees a history that includes the current transaction.
   - Serialization: `StringRedisSerializer` (keys), `GenericJackson2JsonRedisSerializer` + `JavaTimeModule` (values, ISO-8601 timestamps). Cache values must be a mutable `ArrayList`: Jackson cannot write a root type id for `List.of()`/`Stream.toList()` (`ImmutableCollections$ListN`), which would make cache hits fail to deserialize.

5. **Async Kafka Publishing**:
   - `KafkaTemplate.send()` returns `CompletableFuture` — non-blocking, fire-and-forget with callback logging.
   - Producer config: `acks=all`, `retries=3`, JSON serializer with trusted packages.

---

### 5. Asynchronous Event Publishing, Fraud Cases & Kafka Integration

#### Event Model: `TransactionEvaluatedEvent`
Published to topic **`fraud-alerts-topic`** for every evaluated transaction (clean or flagged).

```json
{
  "eventId": "uuid",
  "eventType": "TRANSACTION_EVALUATED",
  "timestamp": "2026-01-15T10:00:00.000Z",
  "transactionId": "uuid",
  "accountId": "ACC-DEMO-1001",
  "amount": 75000.00,
  "currency": "ZAR",
  "category": "ELECTRONICS",
  "merchantName": "iStore Sandton City",
  "location": "Johannesburg",
  "totalRiskScore": 40,
  "overallSeverity": "HIGH",
  "isFraudulent": true,
  "triggeredRules": [
    {
      "ruleName": "HighAmountRule",
      "triggered": true,
      "riskScore": 40,
      "severity": "HIGH",
      "reason": "Transaction amount exceeds threshold of 50000.00"
    }
  ],
  "alertsGenerated": 1
}
```

#### Producer Configuration (`application.yml`):
```yaml
spring:
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      properties:
        spring.json.type.mapping: "TransactionEvaluatedEvent:com.capitec.connect.fraudengine.infrastructure.messaging.TransactionEvaluatedEvent"
        spring.json.trusted.packages: "com.capitec.connect.fraudengine.infrastructure.messaging"
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5
        transactional.id: fraud-engine-producer
      retries: 3
      acks: all
```

#### Key Guarantees:
- **At-least-once delivery**: `acks=all` + retries.
- **Non-blocking**: `CompletableFuture` callback — evaluation HTTP response returns before Kafka ack.
- **Keyed by accountId**: Ensures ordering per account for downstream consumers.
- **Trusted packages**: Only `fraud-engine` event types deserialized by consumers.

#### Fraud Case Consumer

`FraudAlertConsumer` listens to `fraud-alerts-topic`. For each transaction evaluation event, it creates a case with the transaction/account IDs, risk score, severity, and `PENDING_REVIEW` status. Existing cases are left unchanged on duplicate event delivery. Cases are created for all evaluated transactions regardless of risk score; fraud alerts remain the separate rule-triggered records.

For `CRITICAL` severity or a risk score of at least 80, the consumer also publishes an account-freeze command to `account-actions-topic`. Case persistence uses `fraud_cases`, whose `transaction_id` column is unique. Case creation is asynchronous and is not part of the transaction evaluation response.

---

### 6. Resilience4j Fault Tolerance

Applied to `FraudEvaluationService.evaluate()` — the critical path.

| Pattern | Config (`fraudEngine` instance) | Purpose |
|---------|--------------------------------|---------|
| **Circuit Breaker** | Sliding window: 10 calls, 50% failure threshold, 30s open state, 3 half-open calls | Prevent cascading failures when DB/Redis/Kafka degrade |
| **Rate Limiter** | 100 calls / 60s, 1s timeout | Protect downstream from traffic spikes |


#### Circuit Breaker State Machine:
```
CLOSED (normal) --(failureRate>=50%)--> OPEN (reject fast) --(30s)--> HALF_OPEN (probe) --(success)--> CLOSED
                                              ^                          |
                                              |--(failure)---------------|
```

#### Ignore Business Exceptions (Critical):
```yaml
resilience4j:
  circuitbreaker:
    instances:
      fraudEngine:
        ignoreExceptions:
          - com.capitec.connect.fraudengine.api.exception.DuplicateTransactionException
          - jakarta.validation.ConstraintViolationException
          - org.springframework.web.bind.MethodArgumentNotValidException
```
Business violations (409, 400) **do not** count as failures — only infrastructure exceptions (DB timeout, Kafka unreachable, Redis down) trip the breaker.

#### Fallback Behavior (`evaluateFallback`):
When breaker is OPEN or rate limiter rejects:
1. Re-check idempotency (throw 409 if duplicate).
2. Persist transaction (audit trail).
3. **Basic high-amount check only** (threshold 50,000) — no velocity, no location mismatch.
4. Publish `TransactionEvaluatedEvent` to Kafka (audit continuity).
5. Return minimal `EvaluationSummary` (LOW or HIGH severity only).

---

### 7. Flyway Migration Strategy

| Version | File | Purpose |
|---------|------|---------|
| **V1** | `V1__init_schema.sql` | Tables, PKs, FKs, indexes (`transactions`, `fraud_alerts`) |
| **V2** | `V2__seed_demo_data.sql` | Demo account `ACC-DEMO-1001` with 11 transactions covering all 3 rule triggers + corresponding `fraud_alerts` |
| **V3** | `V3__add_fraud_cases_table.sql` | Creates `fraud_cases` with unique transaction ID, review status, timestamps, and account/transaction indexes |

#### Seed Data Coverage:
- **Clean transactions**: 5 low-risk (GROCERIES, DINING, TRANSPORT, SHOPPING).
- **HighAmountRule**: 1 × 75,000 ZAR (ELECTRONICS).
- **TransactionVelocityRule**: 5 × 50 ZAR within 2 minutes (threshold=3).
- **LocationMismatchRule**: Cape Town → Johannesburg in 5 minutes (threshold=10 min).
- **Second account**: `ACC-DEMO-1002` with 2 clean transactions.

---

### 8. Production Observability

- **Metrics**: `/actuator/prometheus` (JVM, HTTP, DB pool, cache, Kafka, Resilience4j).
- **Health**: `/actuator/health` (DB, Redis, Kafka, disk space, circuit breaker state).
- **Logging**: SLF4J structured logs — correlation IDs, rule evaluation, cache hit/miss, Kafka send status.
- **Tracing Ready**: OpenTelemetry compatible via Micrometer.

---

### 9. Security Compliance

- **Multi-stage Dockerfile**: Builder (JDK) → Runtime (JRE, non-root `appuser:appgroup`).
- **Non-root execution**: `USER appuser` in final image.
- **No secrets in image**: All credentials via environment variables (`SPRING_DATASOURCE_PASSWORD`, etc.).
- **Input validation**: Jakarta Bean Validation on all DTOs — RFC 7807 error responses.
- **Rate limiting**: Resilience4j at service layer + reverse proxy (nginx/Envoy) recommended at edge.

#### Local Keycloak setup and security mode

The application configuration sets `security.enabled=false` by default for the assessment environment. Docker Compose starts Keycloak and imports the starter realm from `config/keycloak/fraud-engine-realm.json`, but the realm, client, and role configuration must be reviewed and completed in the Keycloak Admin Console (`http://localhost:8180/admin`) before enabling application authentication. The local bootstrap account is `admin` / `admin`; these credentials must not be used outside local development.

When security is disabled, the application uses a permit-all filter chain and does not enable method-level authorization, so annotations such as `@PreAuthorize` do not protect routes. This is an intentional setup mode, not a production security posture. After configuring Keycloak, set `SECURITY_ENABLED=true` in the application environment and restart it. Security-enabled mode enforces JWT authentication, requires the `FRAUD_ANALYST` role for fraud-case operations, and keeps the documented public endpoints public. The configured issuer (`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI`) must match the token issuer and be reachable from the application.

---

### 10. Testing Strategy

| Layer | Tool | Scope |
|-------|------|-------|
| **Unit** | JUnit 5, Mockito | Rules, mappers, domain logic, controllers (standalone MockMvc) — H2, no Docker |
| **Integration** | Testcontainers + JUnit 5 | Full stack: PostgreSQL 16, Redis 7, Kafka 7.5 — real Flyway migrations |
| **Contract** | Springdoc + Postman | OpenAPI spec validation, manual exploratory testing |
| **Smoke** | `./smoke-test.sh` | End-to-end against running service (evaluation, alerts, asynchronous case lookup/status update, metrics) |
| **Schema Guard** | `SchemaMigrationDriftTest` | Fails build if entity columns lack Flyway migration |

Run all: `./mvnw test`.