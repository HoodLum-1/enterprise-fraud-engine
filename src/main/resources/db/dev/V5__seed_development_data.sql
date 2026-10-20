INSERT INTO transactions
    (id, account_id, amount, currency, category, merchant_name, location, timestamp, created_at)
VALUES
    ('d11f7c8a-0b8c-42bd-bc86-83c320e3a001', 'ACC-DEMO-1001', 75000.00, 'ZAR',
     'ELECTRONICS', 'Demo Electronics', 'Cape Town',
     '2026-01-15 10:00:00+02', CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

INSERT INTO fraud_alerts
    (id, transaction_id, account_id, rule_name, risk_score, severity, reason, flagged_at)
VALUES
    ('d11f7c8a-0b8c-42bd-bc86-83c320e3a002', 'd11f7c8a-0b8c-42bd-bc86-83c320e3a001',
     'ACC-DEMO-1001', 'HighAmountRule', 40, 'HIGH',
     'Development-only sample alert', CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;
