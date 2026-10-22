# ADR 0003: No Redis cache in front of the history read

- Status: Accepted
- Date: 2026-10-22

## Context

The evaluation reads the account's transaction history on every request. An earlier version of the
service put a Redis cache in front of that read, and the cache was removed in commit `41fe3c0`.

The reasons the cache was removed:

- **The cache was invalidated on every write.** The history for an account changes whenever a
  transaction is evaluated for that account, which is exactly the write that the cached entry was
  just produced for. The hit rate was therefore low and the invalidation cost was paid on every
  request.
- **The read is already cheap.** The history read is served by
  `idx_transactions_account_timestamp`, a composite index on `(account_id, timestamp)`. With
  201,016 transactions over 20,000 accounts the planner chooses a bitmap index scan over that
  index, and the query returns in 0.58 ms. The full plan and the measurements are in
  [docs/performance.md](../performance.md).
- **It added an operational dependency.** Redis had to be deployed, monitored, secured and
  backed up, and it introduced a second failure mode for a query that the database already answers
  quickly.

## Decision

Do not cache the history read. PostgreSQL is the system of record and answers the query from an
index.

The service is explicit about the consequence: no latency claim is made for the history retrieval
beyond the measured numbers, and the Redis configuration was removed from `application.yml` rather
than left in place disabled.

## Consequences

- The service depends on PostgreSQL only. There is no cache to warm, invalidate or fall back to.
- The evaluation cost grows linearly with the number of transactions in the history window of an
  account. A pathological account with a very large history would be slower than the measured
  numbers; the fix would be to narrow the history window or to partition the table, not to add a
  cache.
- If the measured history read ever becomes a bottleneck, the first thing to try is the index and
  the window, and only then a cache.
