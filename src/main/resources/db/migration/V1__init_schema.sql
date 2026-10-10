CREATE TABLE transactions (
    id UUID PRIMARY KEY,
    account_id VARCHAR(64) NOT NULL,
    amount DECIMAL(15, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    category VARCHAR(50) NOT NULL,
    merchant_name VARCHAR(100),
    location VARCHAR(100),
    timestamp TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE fraud_alerts (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES transactions(id) ON DELETE CASCADE,
    account_id VARCHAR(64) NOT NULL,
    rule_name VARCHAR(100) NOT NULL,
    risk_score INT NOT NULL,
    severity VARCHAR(20) NOT NULL,
    reason TEXT NOT NULL,
    flagged_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_transactions_account_timestamp ON transactions(account_id, timestamp);
CREATE INDEX idx_fraud_alerts_account_flagged_at ON fraud_alerts(account_id, flagged_at);
CREATE INDEX idx_fraud_alerts_transaction ON fraud_alerts(transaction_id);
