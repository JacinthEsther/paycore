-- Self-service permissions for the CUSTOMER role. KYC_READ / KYC_SUBMIT
-- guard the customer KYC endpoints; ACCOUNT_READ / TRANSACTION_CREATE
-- are seeded now so the role definition is complete before the account
-- and transaction modules exist.

INSERT INTO permissions (id, name)
VALUES
    (paycore_uuid_v7(), 'KYC_READ'),
    (paycore_uuid_v7(), 'KYC_SUBMIT'),
    (paycore_uuid_v7(), 'ACCOUNT_READ'),
    (paycore_uuid_v7(), 'TRANSACTION_CREATE');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN (
    'KYC_READ',
    'KYC_SUBMIT',
    'ACCOUNT_READ',
    'TRANSACTION_CREATE'
)
WHERE r.name = 'CUSTOMER';
