-- System accounts: PayCore's own side of the ledger.
--
-- A deposit is not complete double-entry with only the customer's CREDIT;
-- the other side is PayCore's settlement account, the pool of real money
-- PayCore holds at its settlement bank:
--
--   deposit     DEBIT settlement   CREDIT customer
--   withdrawal  DEBIT customer     CREDIT settlement
--
-- System accounts live in the accounts table so ledger entries keep one
-- foreign key (and the currency guarantee of fk_ledger_entries_account).
-- They belong to no customer and have no account number, so they can never
-- be opened by a customer or named as a transfer destination.

ALTER TABLE accounts
    ALTER COLUMN customer_id DROP NOT NULL,
    ALTER COLUMN account_number DROP NOT NULL;

ALTER TABLE accounts
    DROP CONSTRAINT ck_accounts_type;

ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_type
        CHECK (account_type IN ('PERSONAL', 'SETTLEMENT'));

-- Settlement accounts, and only they, have no owner...
ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_system_owner
        CHECK ((account_type = 'SETTLEMENT') = (customer_id IS NULL));

-- ...and no customer-facing account number.
ALTER TABLE accounts
    ADD CONSTRAINT ck_accounts_system_number
        CHECK ((customer_id IS NULL) = (account_number IS NULL));

-- One system account of each type per currency. Also the conflict target
-- that lets the application create it on first use, race-free.
CREATE UNIQUE INDEX uk_accounts_system_type_currency
    ON accounts (account_type, currency)
    WHERE customer_id IS NULL;


-- LEDGER_POST: admins post deposits and withdrawals to customer accounts.
INSERT INTO permissions (id, name)
VALUES (paycore_uuid_v7(), 'LEDGER_POST');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name = 'LEDGER_POST'
WHERE r.name = 'ADMIN';
