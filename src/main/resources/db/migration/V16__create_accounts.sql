-- Accounts: the financial relationship a customer holds with PayCore.
--
-- There is deliberately no balance column. Balances will be derived from
-- the ledger, which records every monetary movement, so no code can ever
-- "set" a balance directly.

CREATE TABLE accounts (
    id UUID PRIMARY KEY,

    customer_id UUID NOT NULL,

    -- Customer-facing identifier: 10 digits, the last a NUBAN check digit.
    -- Never the internal UUID.
    account_number VARCHAR(10) NOT NULL,

    account_type VARCHAR(30) NOT NULL,

    status VARCHAR(30) NOT NULL,

    -- ISO 4217 code, e.g. NGN. Never a symbol.
    currency VARCHAR(3) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL,

    updated_at TIMESTAMPTZ NOT NULL,

    closed_at TIMESTAMPTZ,

    -- Optimistic locking: two concurrent status changes cannot both win.
    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_accounts_customer
        FOREIGN KEY (customer_id)
        REFERENCES customers(id),

    -- The final protection against duplicate account numbers; application
    -- checks alone can race.
    CONSTRAINT uk_accounts_account_number
        UNIQUE (account_number),

    CONSTRAINT ck_accounts_account_number
        CHECK (account_number ~ '^[0-9]{10}$'),

    CONSTRAINT ck_accounts_type
        CHECK (account_type IN ('PERSONAL')),

    CONSTRAINT ck_accounts_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'FROZEN', 'CLOSED')),

    CONSTRAINT ck_accounts_currency
        CHECK (currency ~ '^[A-Z]{3}$'),

    -- closed_at is set exactly when the account is closed.
    CONSTRAINT ck_accounts_closed_at
        CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

-- Product rule: at most one open account per customer, type and currency.
-- Partial, so a closed account does not stop the customer opening a new one.
CREATE UNIQUE INDEX uk_accounts_open_customer_type_currency
    ON accounts (customer_id, account_type, currency)
    WHERE status <> 'CLOSED';

CREATE INDEX idx_accounts_customer_id
    ON accounts (customer_id);


-- Append-only history of every account status change, with who made it
-- and why. Rows are never updated or deleted.
CREATE TABLE account_status_events (
    id UUID PRIMARY KEY,

    account_id UUID NOT NULL,

    event_type VARCHAR(20) NOT NULL,

    -- NULL only for OPENED, which has no previous status.
    from_status VARCHAR(30),

    to_status VARCHAR(30) NOT NULL,

    -- The customer who opened the account, or the staff member who
    -- changed it.
    performed_by UUID NOT NULL,

    -- Required for staff actions; NULL when the customer opened it.
    reason VARCHAR(500),

    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_account_status_events_account
        FOREIGN KEY (account_id)
        REFERENCES accounts(id),

    CONSTRAINT fk_account_status_events_performed_by
        FOREIGN KEY (performed_by)
        REFERENCES customers(id),

    CONSTRAINT ck_account_status_events_type
        CHECK (event_type IN ('OPENED', 'FROZEN', 'UNFROZEN', 'CLOSED')),

    CONSTRAINT ck_account_status_events_from_status
        CHECK ((event_type = 'OPENED') = (from_status IS NULL))
);

CREATE INDEX idx_account_status_events_account
    ON account_status_events (account_id, occurred_at);

CREATE INDEX idx_account_status_events_performed_by
    ON account_status_events (performed_by);


-- Permissions. ACCOUNT_READ was seeded for CUSTOMER in V12.
--   ACCOUNT_OPEN      customers open their own accounts
--   ACCOUNT_VIEW_ALL  staff read any customer's accounts and history
--   ACCOUNT_MANAGE    admins freeze, unfreeze and close accounts
INSERT INTO permissions (id, name)
VALUES
    (paycore_uuid_v7(), 'ACCOUNT_OPEN'),
    (paycore_uuid_v7(), 'ACCOUNT_VIEW_ALL'),
    (paycore_uuid_v7(), 'ACCOUNT_MANAGE');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON (
    (r.name = 'CUSTOMER' AND p.name = 'ACCOUNT_OPEN')
    OR (r.name = 'SUPPORT' AND p.name = 'ACCOUNT_VIEW_ALL')
    OR (r.name = 'ADMIN' AND p.name IN ('ACCOUNT_VIEW_ALL', 'ACCOUNT_MANAGE'))
);
