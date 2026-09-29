-- customer_roles holds the current assignments; role_assignment_events is
-- the append-only history of every assignment and revocation.

ALTER TABLE customer_roles
    ADD COLUMN assigned_at TIMESTAMPTZ NOT NULL DEFAULT now();

-- Existing rows were all assigned at registration (V6 or CustomerService).
UPDATE customer_roles cr
SET assigned_at = c.created_at
FROM customers c
WHERE c.id = cr.customer_id;


CREATE TABLE role_assignment_events (
    id UUID PRIMARY KEY,

    customer_id UUID NOT NULL,

    role_id UUID NOT NULL,

    action VARCHAR(20) NOT NULL,

    -- NULL when the system acted, e.g. the default role on registration.
    performed_by UUID,

    reason VARCHAR(500),

    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_role_assignment_events_customer
        FOREIGN KEY (customer_id)
        REFERENCES customers(id),

    CONSTRAINT fk_role_assignment_events_role
        FOREIGN KEY (role_id)
        REFERENCES roles(id),

    CONSTRAINT fk_role_assignment_events_performed_by
        FOREIGN KEY (performed_by)
        REFERENCES customers(id),

    CONSTRAINT ck_role_assignment_events_action
        CHECK (action IN ('ASSIGNED', 'REVOKED'))
);

CREATE INDEX idx_role_assignment_events_customer
    ON role_assignment_events(customer_id, occurred_at);

CREATE INDEX idx_role_assignment_events_performed_by
    ON role_assignment_events(performed_by);


-- Give every existing assignment its ASSIGNED event.
INSERT INTO role_assignment_events (
    id,
    customer_id,
    role_id,
    action,
    performed_by,
    reason,
    occurred_at
)
SELECT
    paycore_uuid_v7(),
    cr.customer_id,
    cr.role_id,
    'ASSIGNED',
    NULL,
    'Backfilled from existing assignment',
    cr.assigned_at
FROM customer_roles cr;
