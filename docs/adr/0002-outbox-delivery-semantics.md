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

- Delivery stays at-least-once. A publisher that publishes successfully and then fails to record
  the acknowledgement will publish the row again; consumers must deduplicate on the event id.
- A row can be lost if every publisher dies after publishing but before acknowledging, and the row
  is then parked as `FAILED` after the retry budget. Parking makes that case visible for replay.
- There is no automated replay of parked rows. Replaying a `FAILED` row is an operational action
  and is listed as a known limitation.
- The outbox table is bounded by the retention period rather than growing without limit.

## Alternatives considered

- **One active claim per account, for ordering.** Rejected: it gives a poison event the ability to
  block an account's entire stream, and no consumer needs the ordering. The simpler unordered
  design was preferred, with a parking state so failures stay visible.
- **Exactly-once delivery.** Not available without a distributed transaction between PostgreSQL
  and Kafka. The outbox narrows the window to the publish/acknowledge gap and the consumers
  deduplicate.
- **Keep publishing inside the claim transaction.** Rejected: it holds a connection and a set of
  row locks for the duration of the slowest send in the batch.
