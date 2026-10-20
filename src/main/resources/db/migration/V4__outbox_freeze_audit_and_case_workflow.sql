ALTER TABLE fraud_cases
    ADD COLUMN updated_by VARCHAR(100),
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_fraud_case_status
        CHECK (status IN ('PENDING_REVIEW', 'CONFIRMED_FRAUD', 'FALSE_POSITIVE', 'RESOLVED'));

DROP INDEX IF EXISTS idx_fraud_cases_transaction_id;

CREATE INDEX idx_fraud_cases_created_at_id ON fraud_cases(created_at, id);

CREATE TABLE event_outbox (
    id UUID PRIMARY KEY,
    event_key VARCHAR(64) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ,
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX idx_event_outbox_pending
    ON event_outbox(next_attempt_at, created_at)
    WHERE published_at IS NULL;

CREATE TABLE freeze_audit (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL UNIQUE,
    event_id UUID NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    total_risk_score INT NOT NULL,
    overall_severity VARCHAR(20) NOT NULL,
    reason VARCHAR(200) NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL
);
