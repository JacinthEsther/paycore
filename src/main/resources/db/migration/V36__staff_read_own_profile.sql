INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name = 'PROFILE_READ'
WHERE r.name IN ('SUPPORT', 'COMPLIANCE', 'OPERATIONS_OFFICER', 'OPERATIONS_SUPERVISOR', 'ADMIN')
ON CONFLICT DO NOTHING;
