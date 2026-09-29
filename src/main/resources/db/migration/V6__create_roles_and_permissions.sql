CREATE TABLE roles (
    id UUID PRIMARY KEY,

    name VARCHAR(50) NOT NULL,

    CONSTRAINT uk_roles_name
        UNIQUE (name)
);

CREATE TABLE permissions (
    id UUID PRIMARY KEY,

    name VARCHAR(100) NOT NULL,

    CONSTRAINT uk_permissions_name
        UNIQUE (name)
);

CREATE TABLE role_permissions (
    role_id UUID NOT NULL,

    permission_id UUID NOT NULL,

    CONSTRAINT pk_role_permissions
        PRIMARY KEY (role_id, permission_id),

    CONSTRAINT fk_role_permissions_role
        FOREIGN KEY (role_id)
        REFERENCES roles(id),

    CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_id)
        REFERENCES permissions(id)
);

CREATE TABLE customer_roles (
    customer_id UUID NOT NULL,

    role_id UUID NOT NULL,

    CONSTRAINT pk_customer_roles
        PRIMARY KEY (customer_id, role_id),

    CONSTRAINT fk_customer_roles_customer
        FOREIGN KEY (customer_id)
        REFERENCES customers(id),

    CONSTRAINT fk_customer_roles_role
        FOREIGN KEY (role_id)
        REFERENCES roles(id)
);

CREATE INDEX idx_customer_roles_role
    ON customer_roles(role_id);

INSERT INTO permissions (id, name)
VALUES
    (gen_random_uuid(), 'PROFILE_READ'),
    (gen_random_uuid(), 'PROFILE_UPDATE'),
    (gen_random_uuid(), 'TRANSACTION_READ'),
    (gen_random_uuid(), 'CUSTOMER_READ'),
    (gen_random_uuid(), 'CUSTOMER_UPDATE'),
    (gen_random_uuid(), 'CUSTOMER_SUSPEND'),
    (gen_random_uuid(), 'CUSTOMER_CLOSE');

INSERT INTO roles (id, name)
VALUES
    (gen_random_uuid(), 'CUSTOMER'),
    (gen_random_uuid(), 'SUPPORT'),
    (gen_random_uuid(), 'ADMIN');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON (
    (r.name = 'CUSTOMER' AND p.name IN (
        'PROFILE_READ',
        'PROFILE_UPDATE',
        'TRANSACTION_READ'
    ))
    OR (r.name = 'SUPPORT' AND p.name IN (
        'CUSTOMER_READ',
        'CUSTOMER_UPDATE'
    ))
    OR (r.name = 'ADMIN' AND p.name IN (
        'CUSTOMER_READ',
        'CUSTOMER_UPDATE',
        'CUSTOMER_SUSPEND',
        'CUSTOMER_CLOSE'
    ))
);

-- Existing customers get the default CUSTOMER role.
INSERT INTO customer_roles (customer_id, role_id)
SELECT c.id, r.id
FROM customers c
CROSS JOIN roles r
WHERE r.name = 'CUSTOMER';
