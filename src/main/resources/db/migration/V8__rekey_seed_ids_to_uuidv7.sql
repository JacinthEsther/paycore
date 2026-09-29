-- PayCore ids are UUIDv7. V6/V7 seeded roles and permissions with
-- gen_random_uuid() (v4); this re-keys those rows to v7 and updates
-- every reference, all inside Flyway's migration transaction.

-- Time-ordered UUIDv7 (RFC 9562) that works on any PostgreSQL version:
-- 48-bit Unix millisecond timestamp + random bits, with the version
-- nibble set to 7. Kept for future seed migrations; PostgreSQL 18+ also
-- provides uuidv7().
CREATE OR REPLACE FUNCTION paycore_uuid_v7()
RETURNS uuid
LANGUAGE sql
VOLATILE
AS $$
    SELECT encode(
        set_bit(
            set_bit(
                overlay(
                    uuid_send(gen_random_uuid())
                    PLACING substring(
                        int8send(
                            floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint
                        )
                        FROM 3
                    )
                    FROM 1 FOR 6
                ),
                52, 1
            ),
            53, 1
        ),
        'hex'
    )::uuid;
$$;


-- References have no ON UPDATE CASCADE, so drop them while re-keying.
ALTER TABLE role_permissions DROP CONSTRAINT fk_role_permissions_role;
ALTER TABLE role_permissions DROP CONSTRAINT fk_role_permissions_permission;
ALTER TABLE customer_roles DROP CONSTRAINT fk_customer_roles_role;


CREATE TEMPORARY TABLE role_id_map ON COMMIT DROP AS
SELECT id AS old_id, paycore_uuid_v7() AS new_id
FROM roles
WHERE substring(id::text, 15, 1) <> '7';

UPDATE roles r
SET id = m.new_id
FROM role_id_map m
WHERE r.id = m.old_id;

UPDATE role_permissions rp
SET role_id = m.new_id
FROM role_id_map m
WHERE rp.role_id = m.old_id;

UPDATE customer_roles cr
SET role_id = m.new_id
FROM role_id_map m
WHERE cr.role_id = m.old_id;


CREATE TEMPORARY TABLE permission_id_map ON COMMIT DROP AS
SELECT id AS old_id, paycore_uuid_v7() AS new_id
FROM permissions
WHERE substring(id::text, 15, 1) <> '7';

UPDATE permissions p
SET id = m.new_id
FROM permission_id_map m
WHERE p.id = m.old_id;

UPDATE role_permissions rp
SET permission_id = m.new_id
FROM permission_id_map m
WHERE rp.permission_id = m.old_id;


ALTER TABLE role_permissions
    ADD CONSTRAINT fk_role_permissions_role
        FOREIGN KEY (role_id)
        REFERENCES roles(id);

ALTER TABLE role_permissions
    ADD CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_id)
        REFERENCES permissions(id);

ALTER TABLE customer_roles
    ADD CONSTRAINT fk_customer_roles_role
        FOREIGN KEY (role_id)
        REFERENCES roles(id);
