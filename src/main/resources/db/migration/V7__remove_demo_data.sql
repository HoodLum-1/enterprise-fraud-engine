-- Remove the historical ACC-DEMO-* rows introduced by V2.
--
-- V2 is kept because it is part of the applied migration history, but its demo data must not be
-- left behind in a production database. The development-only seed in db/dev/V5 uses different
-- transaction ids, so it is deliberately not matched here and still runs in the dev profile.

DELETE FROM transactions
WHERE id IN (
    'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
    'b2c3d4e5-f6a7-8901-bcde-f23456789012',
    'c3d4e5f6-a7b8-9012-cdef-345678901234',
    'd4e5f6a7-b8c9-0123-defa-456789012345',
    'e5f6a7b8-c9d0-1234-efab-567890123456',
    'f6a7b8c9-d0e1-2345-fabc-678901234567',
    'a7b8c9d0-e1f2-3456-abcd-789012345678',
    'b8c9d0e1-f2a3-4567-bcde-890123456789',
    'c9d0e1f2-a3b4-5678-cdef-901234567890',
    'd0e1f2a3-b4c5-6789-defa-012345678901',
    'e1f2a3b4-c5d6-7890-efab-123456789012',
    'f2a3b4c5-d6e7-8901-fabc-234567890123',
    'a3b4c5d6-e7f8-9012-abcd-345678901234',
    'b4c5d6e7-f8a9-0123-bcde-456789012345',
    'c5d6e7f8-a9b0-1234-cdef-567890123456'
);
