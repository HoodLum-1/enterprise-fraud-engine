-- Flyway Migration V2: Seed Demo Data (Idempotent)
-- Inserts realistic sample transactions and fraud alerts for demo account ACC-DEMO-1001

-- ============================================================
-- Sample Transactions for ACC-DEMO-1001
-- ============================================================

INSERT INTO transactions (id, account_id, amount, currency, category, merchant_name, location, timestamp, created_at) VALUES
    ('a1b2c3d4-e5f6-7890-abcd-ef1234567890', 'ACC-DEMO-1001', 125.50, 'ZAR', 'GROCERIES', 'Woolworths Sandton', 'Johannesburg', '2025-01-15 08:30:00+02', '2025-01-15 08:30:00+02'),
    ('b2c3d4e5-f6a7-8901-bcde-f23456789012', 'ACC-DEMO-1001', 89.99, 'ZAR', 'DINING', 'Mugg & Bean Rosebank', 'Johannesburg', '2025-01-15 12:45:00+02', '2025-01-15 12:45:00+02'),
    ('c3d4e5f6-a7b8-9012-cdef-345678901234', 'ACC-DEMO-1001', 210.00, 'ZAR', 'TRANSPORT', 'Uber', 'Johannesburg', '2025-01-15 18:20:00+02', '2025-01-15 18:20:00+02'),
    ('d4e5f6a7-b8c9-0123-defa-456789012345', 'ACC-DEMO-1001', 45.00, 'ZAR', 'GROCERIES', 'Checkers Norwood', 'Johannesburg', '2025-01-16 09:15:00+02', '2025-01-16 09:15:00+02'),
    ('e5f6a7b8-c9d0-1234-efab-567890123456', 'ACC-DEMO-1001', 320.00, 'ZAR', 'SHOPPING', 'Edgars Mall of Africa', 'Midrand', '2025-01-16 14:30:00+02', '2025-01-16 14:30:00+02')
ON CONFLICT (id) DO NOTHING;

INSERT INTO transactions (id, account_id, amount, currency, category, merchant_name, location, timestamp, created_at) VALUES
    ('f6a7b8c9-d0e1-2345-fabc-678901234567', 'ACC-DEMO-1001', 75000.00, 'ZAR', 'ELECTRONICS', 'iStore Sandton City', 'Johannesburg', '2025-01-17 10:00:00+02', '2025-01-17 10:00:00+02')
ON CONFLICT (id) DO NOTHING;

INSERT INTO transactions (id, account_id, amount, currency, category, merchant_name, location, timestamp, created_at) VALUES
    ('a7b8c9d0-e1f2-3456-abcd-789012345678', 'ACC-DEMO-1001', 50.00, 'ZAR', 'GROCERIES', 'Quick Mart', 'Johannesburg', '2025-01-18 10:00:00+02', '2025-01-18 10:00:00+02'),
    ('b8c9d0e1-f2a3-4567-bcde-890123456789', 'ACC-DEMO-1001', 50.00, 'ZAR', 'GROCERIES', 'Quick Mart', 'Johannesburg', '2025-01-18 10:00:30+02', '2025-01-18 10:00:30+02'),
    ('c9d0e1f2-a3b4-5678-cdef-901234567890', 'ACC-DEMO-1001', 50.00, 'ZAR', 'GROCERIES', 'Quick Mart', 'Johannesburg', '2025-01-18 10:01:00+02', '2025-01-18 10:01:00+02'),
    ('d0e1f2a3-b4c5-6789-defa-012345678901', 'ACC-DEMO-1001', 50.00, 'ZAR', 'GROCERIES', 'Quick Mart', 'Johannesburg', '2025-01-18 10:01:30+02', '2025-01-18 10:01:30+02'),
    ('e1f2a3b4-c5d6-7890-efab-123456789012', 'ACC-DEMO-1001', 50.00, 'ZAR', 'GROCERIES', 'Quick Mart', 'Johannesburg', '2025-01-18 10:02:00+02', '2025-01-18 10:02:00+02')
ON CONFLICT (id) DO NOTHING;

INSERT INTO transactions (id, account_id, amount, currency, category, merchant_name, location, timestamp, created_at) VALUES
    ('f2a3b4c5-d6e7-8901-fabc-234567890123', 'ACC-DEMO-1001', 180.00, 'ZAR', 'DINING', 'The Test Kitchen', 'Cape Town', '2025-01-19 12:00:00+02', '2025-01-19 12:00:00+02'),
    ('a3b4c5d6-e7f8-9012-abcd-345678901234', 'ACC-DEMO-1001', 95.00, 'ZAR', 'GROCERIES', 'Pick n Pay', 'Johannesburg', '2025-01-19 12:05:00+02', '2025-01-19 12:05:00+02')
ON CONFLICT (id) DO NOTHING;

INSERT INTO transactions (id, account_id, amount, currency, category, merchant_name, location, timestamp, created_at) VALUES
    ('b4c5d6e7-f8a9-0123-bcde-456789012345', 'ACC-DEMO-1002', 250.00, 'ZAR', 'SHOPPING', 'Canal Walk', 'Cape Town', '2025-01-20 11:00:00+02', '2025-01-20 11:00:00+02'),
    ('c5d6e7f8-a9b0-1234-cdef-567890123456', 'ACC-DEMO-1002', 150.00, 'ZAR', 'DINING', 'Willoughby & Co', 'Cape Town', '2025-01-20 13:30:00+02', '2025-01-20 13:30:00+02')
ON CONFLICT (id) DO NOTHING;

-- ============================================================
-- Corresponding Fraud Alerts
-- ============================================================

INSERT INTO fraud_alerts (id, transaction_id, account_id, rule_name, risk_score, severity, reason, flagged_at) VALUES
    ('aa11bb22-cc33-dd44-ee55-ff6677889900', 'f6a7b8c9-d0e1-2345-fabc-678901234567', 'ACC-DEMO-1001', 'HighAmountRule', 40, 'HIGH', 'Transaction amount 75000.00 exceeds configured threshold of 50000.00', '2025-01-17 10:00:01+02'),
    ('bb22cc33-dd44-ee55-ff66-001122334455', 'e1f2a3b4-c5d6-7890-efab-123456789012', 'ACC-DEMO-1001', 'TransactionVelocityRule', 50, 'CRITICAL', 'Potential velocity fraud: 5 transactions occurred within the last 2 minutes', '2025-01-18 10:02:01+02'),
    ('cc33dd44-ee55-ff66-0011-223344556677', 'a3b4c5d6-e7f8-9012-abcd-345678901234', 'ACC-DEMO-1001', 'LocationMismatchRule', 60, 'CRITICAL', 'Location mismatch detected: transaction at location Johannesburg occurred within 10 minutes of previous transaction at location Cape Town', '2025-01-19 12:05:01+02')
ON CONFLICT (id) DO NOTHING;