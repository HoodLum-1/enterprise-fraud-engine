# ADR 0001: Serialise concurrent evaluations with a per-account advisory lock

- Status: Accepted
- Date: 2026-10-22

## Context

`FraudEvaluationService.evaluate` reads an account's transaction history, evaluates the incoming
transaction against that history, and then persists the transaction, its alerts and the outbox
record. The history read happens before the write, and the write is not visible to other
transactions until it commits.

Two concurrent evaluations for the same account therefore both read a history that excludes the
other's uncommitted transaction. The velocity rule counts transactions in a window and the
location-mismatch rule compares against the most recent prior transaction; both can be evaluated
against a stale view, and both can miss a rule that should have fired.

The service also has a small connection pool (Hikari, 20 connections) and a process-local rate
limiter. A naive fix - "read again after the write" or "retry on conflict" - would add round trips
and still not guarantee a consistent view.

## Decision

Take a PostgreSQL transaction-scoped advisory lock keyed on the account id before the duplicate
check and before the history read.

- `pg_advisory_xact_lock` is released automatically when the transaction commits or rolls back,
  so a crashed or timed-out evaluation cannot strand the lock.
- The key is derived from the account id with SHA-256 (`AccountLockKeys`), so it is stable across
  processes and restarts, and lives in its own namespace.
- A `lock_timeout` is set for the transaction before the lock is requested (default 2s). A
  contended account fails fast with `AccountLockTimeoutException`, which is mapped to `503` with
  `Retry-After: 1`. Without the timeout, waiting requests would each park a Hikari connection and
  the pool would be exhausted by a single hot account.
- The timeout is deliberately **not** a `DataAccessException`. The circuit breaker records
  `DataAccessException`s, and a lock timeout surfaces from PostgreSQL as one. Contention on one
  account must not open the circuit for every other caller, so the timeout has its own type and is
  listed in the circuit breaker's `ignoreExceptions`.
- The evaluation runs at `READ COMMITTED`, pinned explicitly on the `@Transactional` annotation.
  Lock-then-read is only correct under `READ COMMITTED`: under `REPEATABLE READ` the second
  evaluation would take a snapshot from before the first one committed and would not see the
  transaction it is supposed to be compared against. A unit test guards the annotation.
- The lock sits behind the `AccountLock` interface. Advisory locks are PostgreSQL-only, so the
  H2-backed unit tests use `NoOpAccountLock` through `fraud.engine.lock.enabled=false`; the
  integration tests run against real PostgreSQL and use the real implementation.

## Consequences

- Concurrent evaluations of the same account are serialised; evaluations of different accounts are
  unaffected.
- Under sustained contention for one account, requests are rejected with `503` rather than
  queueing. The caller is expected to retry after the advertised `Retry-After`.
- The lock adds two statements to each evaluation (one to set the timeout, one to take the lock).
  The wait time is measured by the `fraud.account.lock.wait` timer.
- A per-account lock only orders evaluations that reach the database. It does not order the
  evaluation against the asynchronous outbox delivery, which remains at-least-once and unordered
  (see ADR 0002).

## Alternatives considered

- **Optimistic retry on conflict.** Would need a retry loop and a conflict signal from the
  database, and would still read a stale history on the first attempt.
- **Serialise the whole transaction with `SELECT ... FOR UPDATE`.** Not possible here: the row
  being locked is the transaction being inserted, and there is no row to lock before the read.
- **Pessimistic row lock on the account.** There is no account table; the lock is on the
  transactions, and a missing row cannot be locked.
- **A distributed lock (Redis).** Redis was removed from this service; adding a new dependency for
  a lock that PostgreSQL already provides transactionally is not justified.
