# Fraud Engine Technical Architecture

### 1. Architectural Overview & Hexagonal Architecture (Ports and Adapters)

The **Fraud Engine** is designed following the **Hexagonal Architecture** (also known as Ports and Adapters) to ensure complete separation of business logic, domain models, and external infrastructure/protocols.

```
+-----------------------------------------------------------------------------------+
|                                 INBOUND ADAPTERS                                  |
|  - REST Controllers (TransactionController, FraudAlertController)                 |
|  - Request / Response DTOs & Jakarta Bean Validation                              |
|  - RFC 7807 Global Exception Handling                                            |
+-----------------------------------------------------------------------------------+
                                         │
                                         ▼ (Inbound Ports / DTOs)
+-----------------------------------------------------------------------------------+
|                               APPLICATION / DOMAIN                                |
|  - FraudEvaluationService (Orchestrates ingestion, persistence & evaluation)       |
|  - FraudEngine (Rule chain orchestrator)                                          |
|  - Domain Rules (HighAmountRule, TransactionVelocityRule, LocationMismatchRule)    |
|  - Domain Records (Transaction, FraudAlert, RuleEvaluationResult, Severity)       |
+-----------------------------------------------------------------------------------+
                                         │
                                         ▼ (Outbound Ports / Mappers)
+-----------------------------------------------------------------------------------+
|                                OUTBOUND ADAPTERS                                  |
|  - Spring Data Repositories (TransactionRepository, FraudAlertRepository)         |
|  - JPA Entities (TransactionEntity, FraudAlertEntity)                             |
|  - PostgreSQL Database & Flyway Schema Migrations                                 |
|  - HikariCP Connection Pooling & Spring Actuator / Prometheus                     |
+-----------------------------------------------------------------------------------+
```

#### Layers & Boundaries:
1. **API / Inbound Adapters (`api.*`)**:
   - HTTP endpoints accepting incoming evaluation requests and alert queries.
   - Enforces Jakarta Bean Validation on input DTOs (`TransactionRequestDto`).
   - Normalizes errors into standard RFC 7807 `ProblemDetail` structures.
2. **Domain & Engine (`domain.*`, `engine.*`)**:
   - Pure domain business logic and immutable data models (`Transaction`, `FraudAlert`, `EvaluationSummary`).
   - Compact constructors enforce validation rules and domain invariants (positive amounts, non-null UUIDs, valid timestamps).
   - `FraudEngine` orchestrates the evaluation without knowing about HTTP or persistence specifics.
3. **Infrastructure / Outbound Adapters (`infrastructure.*`)**:
   - Maps between domain records and database entities using dedicated mappers (`TransactionMapper`, `FraudAlertMapper`).
   - Handles persistence via Spring Data JPA and PostgreSQL.
   - Externalizes configuration with `@ConfigurationProperties(prefix = "fraud.engine.rules")`.

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
- **Deterministic Rule Ordering**: Injected via Spring's `@Order` annotations, ensuring deterministic sequential evaluation.
- **Dynamic Rule Configuration & Conditional Activation**:
  - Each rule is annotated with `@ConditionalOnProperty(name = "fraud.engine.rules.<rule>.enabled", matchIfMissing = true)`.
  - Thresholds, evaluation windows, risk scores, and severities are externalized to `application.yml` via `FraudEngineProperties`.
- **Non-blocking Rule Pipeline**: All rules evaluate against the same immutable state; individual triggered rules append evaluation results without halting subsequent rule checks unless designed as terminating rules.

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
│  3. Historical Data Fetch:                                             │
│     Retrieve transactions for accountId within last 24h.               │
│                                                                        │
│  4. Evaluate Rules:                                                    │
│     Execute FraudEngine rule chain.                                    │
│                                                                        │
│  5. Persist Alerts:                                                    │
│     Map triggered alerts -> FraudAlertEntity -> Save all alerts.       │
│                                                                        │
│  6. Return Evaluation Summary.                                         │
└────────────────────────────────────────────────────────────────────────┘
```

#### Idempotency & Relational Integrity:
- **Idempotency Guarantee**: Primary key UUID collisions are caught early before evaluation or side effects, preserving consistency.
- **Cascading Foreign Keys**: `fraud_alerts.transaction_id` references `transactions.id` with `ON DELETE CASCADE` to maintain referential integrity.
- **ACID Compliance**: The entire evaluation, ingestion, and alert generation cycle occurs within a single atomic database transaction.

---

### 4. Concurrency & Performance Optimization

To support high-throughput, low-latency transaction processing:

1. **Connection Pool Tuning (HikariCP)**:
   - `maximum-pool-size`: 20 connections to balance parallelism against database resource contention.
   - `minimum-idle`: 5 connections ready for instant workload spikes.
   - `connection-timeout`: 20,000 ms to avoid indefinite thread blocking.
2. **Database Indexing Strategy**:
   - `idx_transactions_account_timestamp` on `transactions(account_id, timestamp)`: Accelerates 24-hour sliding window historical lookups.
   - `idx_fraud_alerts_account_flagged` on `fraud_alerts(account_id, flagged_at)`: Optimizes alert retrieval queries ordered chronologically.
3. **Stateless Service Design**:
   - `FraudEngine` and all `FraudRule` beans are stateless Spring singletons, allowing concurrent threads to evaluate transactions without synchronization locks.

---

### 5. Production Observability

- **Metrics & Telemetry**: Exposed via `/actuator/prometheus` and `/actuator/metrics`.
- **Health Checks**: Live status with database and disk health details exposed at `/actuator/health`.
- **Structured Logging**: SLF4J logging across rule evaluation, transaction ingestion, and alert generation.
