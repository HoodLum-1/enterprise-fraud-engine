-- Outbox lease, parking state and retention support.
--
-- Version 6 is used because version 5 is taken by the development-only seed in db/dev, which is
-- on the Flyway path when the dev profile is active. The two locations never share a version.

ALTER TABLE event_outbox
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

-- Rows that were already delivered are PUBLISHED, everything else is still pending.
UPDATE event_outbox SET status = 'PUBLISHED' WHERE published_at IS NOT NULL;

-- The old index selected on published_at, which is no longer the pending predicate.
DROP INDEX IF EXISTS idx_event_outbox_pending;

CREATE INDEX idx_event_outbox_claim
    ON event_outbox(status, next_attempt_at, lease_expires_at);

CREATE INDEX idx_event_outbox_published
    ON event_outbox(published_at)
    WHERE status = 'PUBLISHED';
