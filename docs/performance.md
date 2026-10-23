# Performance evidence

Measured on a development laptop (macOS, Apple Silicon, 8 cores, 16 GB), Docker Desktop, against
the Compose stack. These numbers are a development signal, not a capacity certification. The
service runs on a single instance with a local PostgreSQL, Kafka and the application, so the
database and the broker share the same host as the service.

## History query

The evaluation reads the account's transaction history with:

```sql
SELECT * FROM transactions
WHERE account_id = ?
  AND timestamp BETWEEN ? AND ?
ORDER BY timestamp DESC
```

`V1__init_schema.sql` creates `idx_transactions_account_timestamp ON transactions(account_id, timestamp)`,
which covers the predicate and the ordering.

With 201,016 transactions spread over 20,000 accounts:

```text
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM transactions
WHERE account_id = 'ACC-SCALE-4242'
  AND timestamp BETWEEN now() - interval '24 hours' AND now()
ORDER BY timestamp DESC;

 Sort  (cost=43.20..43.23 rows=10 width=81) (actual time=0.469..0.470 rows=10 loops=1)
   Sort Key: "timestamp" DESC
   Sort Method: quicksort  Memory: 26kB
   Buffers: shared hit=16
   ->  Bitmap Heap Scan on transactions  (cost=4.56..43.04 rows=10 width=81) (actual time=0.152..0.385 rows=10 loops=1)
         Heap Blocks: exact=10
         Buffers: shared hit=13
         ->  Bitmap Index Scan on idx_transactions_account_timestamp  (cost=0.00..4.55 rows=10 width=0) (actual time=0.103..0.103 rows=10 loops=1)
 Planning Time: 1.041 ms
 Execution Time: 0.582 ms
```

The query is index-backed and returns in well under a millisecond, and the plan stays an index
scan as the table grows. The history read is not the bottleneck, which is the main reason the
Redis cache was removed (see [ADR 0003](adr/0003-no-redis-cache.md)).

## Load test

`./load-test.sh` against a `dev` profile instance (security disabled), 20 concurrent clients:

| Run | Requests | Duration | Throughput | p50 | p95 | p99 | Success |
|---|---|---|---|---|---|---|---|
| Small table (1,016 transactions, 52 accounts) | 500 | 2.46 s | 204 req/s | 15.2 ms | 34.1 ms | 63.9 ms | 100% |
| Large table (201,016 transactions, 20,000 accounts) | 1,000 | 5.49 s | 182 req/s | 14.6 ms | 60.3 ms | 122.3 ms | 100% |
| Outbox drain verification (fresh dev database, warm JVM) | 1,000 | 5.85 s | 170.8 req/s | 14.9 ms | 33.6 ms | 57.4 ms | 100% |

Every request was answered with `201`; there were no `429`, `503` or `409` responses.

The in-service evaluation time, taken from the `fraud_evaluation_duration_seconds` histogram that
the service publishes, is slightly lower than the client-side latency, as expected. The values
below are from the outbox drain verification run:

| Percentile | Client side (warm run) | In service (histogram) |
|---|---|---|
| p50 | 14.9 ms | 11.3 ms |
| p95 | 33.6 ms | 47.1 ms |
| p99 | 57.4 ms | 93.2 ms |

The p95 is therefore in the order of 30-50 ms on this hardware, with a throughput of roughly
170 requests per second on a single instance against a co-located database.

## What the numbers show

- **The history read is cheap.** A bitmap index scan over the covering index answers the query in
  under a millisecond with 200,000 rows. There is no case for a cache in front of it.
- **The write path dominates.** The evaluation writes the transaction, its alerts and the outbox
  row in one transaction, plus the advisory lock. The evaluation time is dominated by those writes
  and by the per-transaction commit, not by the history read.
- **The outbox publisher is no longer the ceiling.** A poll starts every Kafka send in the claimed
  batch before it awaits any of them, and keeps claiming while batches come back full. In the
  verification run, 1,000 evaluations produced 1,000 outbox rows; the first (cold) run left 146
  rows pending immediately after the load test and drained to zero within 2 seconds, and the warm
  run left 22 rows and drained in under 1 second. With 20 sends in flight per batch and up to
  `fraud.engine.messaging.max-batches-per-poll` batches per poll, the publisher can drain a burst
  faster than the ~170 req/s ingestion rate observed here. If sustained ingestion still outruns it,
  raise the batch size, the poll frequency, or the number of publisher instances.
- **The rate limiter is not the limiting factor here.** The default limit is 1,000 requests per
  second per process, well above the measured throughput.
- **The lock did not become a bottleneck.** All 2,000 lock acquisitions in the run succeeded with
  the `acquired` outcome, and no request was rejected with a lock timeout. The load test spreads its
  requests over 50 accounts, so contention is low by construction; a single hot account is the case
  that the lock timeout and the `FraudEngineAccountLockContention` alert exist for.

## Reproducing

```bash
docker compose up -d postgres kafka
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run
TOTAL_REQUESTS=1000 CONCURRENCY=20 ./load-test.sh
curl -s http://localhost:8080/actuator/prometheus | grep fraud_evaluation_duration_seconds
```

The `EXPLAIN` needs a table with a realistic number of rows per account; the numbers above used
200,000 generated transactions.
