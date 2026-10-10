UPDATE roles SET name = 'OPERATIONS_OFFICER' WHERE name = 'OPERATIONS';

INSERT INTO roles (id, name)
VALUES
    (paycore_uuid_v7(), 'COMPLIANCE'),
    (paycore_uuid_v7(), 'OPERATIONS_SUPERVISOR');

DELETE FROM role_permissions
WHERE role_id IN (
    SELECT id FROM roles
    WHERE name IN ('ADMIN', 'SUPPORT', 'COMPLIANCE', 'OPERATIONS_OFFICER', 'OPERATIONS_SUPERVISOR')
);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN (
    VALUES
        ('SUPPORT', 'CUSTOMER_READ'),
        ('SUPPORT', 'CUSTOMER_UPDATE'),
        ('SUPPORT', 'ACCOUNT_VIEW_ALL'),

        ('COMPLIANCE', 'CUSTOMER_READ'),
        ('COMPLIANCE', 'ACCOUNT_VIEW_ALL'),
        ('COMPLIANCE', 'KYC_REVIEW'),
        ('COMPLIANCE', 'ACCOUNT_MANAGE'),
        ('COMPLIANCE', 'CUSTOMER_SUSPEND'),
        ('COMPLIANCE', 'CUSTOMER_CLOSE'),

        ('OPERATIONS_OFFICER', 'CUSTOMER_READ'),
        ('OPERATIONS_OFFICER', 'ACCOUNT_VIEW_ALL'),
        ('OPERATIONS_OFFICER', 'LEDGER_REQUEST'),

        ('OPERATIONS_SUPERVISOR', 'CUSTOMER_READ'),
        ('OPERATIONS_SUPERVISOR', 'ACCOUNT_VIEW_ALL'),
        ('OPERATIONS_SUPERVISOR', 'LEDGER_APPROVE'),

        ('ADMIN', 'CUSTOMER_READ'),
        ('ADMIN', 'ROLE_MANAGE')
) AS grants(role_name, permission_name) ON grants.role_name = r.name
JOIN permissions p ON p.name = grants.permission_name;
