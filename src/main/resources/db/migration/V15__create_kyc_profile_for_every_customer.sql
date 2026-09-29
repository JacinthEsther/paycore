-- Every customer now gets a KYC profile (NOT_STARTED) at registration.
-- Give existing customers without one the same starting profile.
INSERT INTO kyc_profiles (id, customer_id, status, created_at, updated_at)
SELECT
    paycore_uuid_v7(),
    c.id,
    'NOT_STARTED',
    now(),
    now()
FROM customers c
WHERE NOT EXISTS (
    SELECT 1
    FROM kyc_profiles p
    WHERE p.customer_id = c.id
);
