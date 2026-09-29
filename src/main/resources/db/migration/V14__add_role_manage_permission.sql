-- Lets admins assign and revoke roles through the admin API.

INSERT INTO permissions (id, name)
VALUES (paycore_uuid_v7(), 'ROLE_MANAGE');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name = 'ROLE_MANAGE'
WHERE r.name = 'ADMIN';
