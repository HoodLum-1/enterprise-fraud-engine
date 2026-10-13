CREATE TABLE fraud_cases (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL UNIQUE,
    account_id VARCHAR(64) NOT NULL,
    total_risk_score INT NOT NULL,
    overall_severity VARCHAR(20) NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ
);

CREATE INDEX idx_fraud_cases_account_id ON fraud_cases(account_id);
CREATE INDEX idx_fraud_cases_transaction_id ON fraud_cases(transaction_id);