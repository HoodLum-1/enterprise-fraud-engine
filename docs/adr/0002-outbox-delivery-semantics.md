# ADR 0002: The outbox delivers at-least-once and unordered, with a parking state

- Status: Accepted
- Date: 2026-10-22

## Context

The outbox decouples the evaluation transaction from Kafka: the evaluation writes a row to
`event_outbox` in the same transaction as the transaction and its alerts, and a scheduled publisher
delivers it afterwards. This removes the dual-write problem, where a committed evaluation could
lose its event.

The publisher previously claimed rows with `SELECT ... FOR UPDATE SKIP LOCKED` and published them
**inside the same transaction**. A batch of 50 rows with a 10 second send timeout per row could hold
a database transaction - and the row locks of the whole batch - open for roughly eight minutes. It
also retried failing rows forever, so a single undeliverable event would be retried until the
end of time, and delivered rows were never removed.

The open question was whether to guarantee ordering. An "one active claim per account" design
gives per-account ordering, but a single poison event would then block that account's entire
stream.

## Decision

Ship **unordered at-least-once delivery**, and make the failure modes explicit.

- **No per-account ordering guarantee.** Rows are keyed by account, so a topic's partitions keep a
  per-account order in the common case, but a redelivery or a second publisher may interleave
  events. Nothing downstream requires cross-event order: the consumers deduplicate on the
  transaction id and the event id, and the freeze command is derived from the evaluation event
  rather than from a stream position. If a future consumer needs strict per-account ordering, the
  claim query is the single place that would change.
- **Claim, publish, acknowledge as three steps.** A short transaction claims a batch and leases it,
  the Kafka sends happen outside any transaction, and a short transaction records the outcome. No
  transaction is held open across a network send.
- **The lease outlives the publish.** The lease is
  `batch size x send timeout + margin`, so a slow but healthy publish is never reclaimed while it
  is still in flight. A publisher that dies leaves the row leased until the lease expires, after
  which another publisher reclaims it.
- **A parking state instead of infinite retries.** A row that has been claimed `max-attempts`
  times (default 10) is moved to `FAILED` with its last error and is never claimed again. Parking
  makes a poison event visible instead of invisible, and it is alerted on through the
  `fraud_outbox_failed_total` metric. Claiming counts as an attempt, so a publisher that keeps
  dying mid-publish cannot retry a row forever.
- **Retention deletes in bounded batches.** Delivered rows are removed after the retention period
  (default 7 days) in batches of 500 per sweep. A single large delete would hold a long
  transaction and lock the table for its duration.

## Consequences

- Published-but-unacknowledged rows may be re-published under failure scenarios, requiring downstream consumer idempotency.
- Parked `FAILED` rows require manual operational review and replay via administrative tooling.

## Alternatives considered

- **One active claim per account, for ordering.** Rejected: it gives a poison event the ability to
  block an account's entire stream, and no consumer needs the ordering. The simpler unordered
  design was preferred, with a parking state so failures stay visible.
- **Exactly-once delivery.** Not available without a distributed transaction between PostgreSQL
  and Kafka. The outbox narrows the window to the publish/acknowledge gap and the consumers
  deduplicate.
- **Keep publishing inside the claim transaction.** Rejected: it holds a connection and a set of
  row locks for the duration of the slowest send in the batch.

## Implementation update

Sends within a claimed batch are started concurrently before any send is awaited, and a poll
continues claiming while batches come back full (up to `max-batches-per-poll`). This removes the
old one-send-at-a-time throughput ceiling without changing the at-least-once, unordered delivery
contract.
