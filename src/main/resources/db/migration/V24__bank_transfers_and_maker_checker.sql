-- Money moves the way it does at a real bank.
--
-- 1. Money enters and leaves through payment rails, never by a staff
--    member typing an amount: card top-ups (V23), transfers in from other
--    banks and transfers out to other banks.
-- 2. Staff can no longer post deposits or withdrawals. Corrections
--    (reversals, manual adjustments) go through maker-checker: one
--    operations officer requests, a different one approves, and only the
--    approval posts to the ledger.

-- ============================================================
-- PROVIDERS AND COUNTERPARTIES
-- ============================================================

-- V23's funding provider becomes any external provider: the card
-- processor for a top-up, the bank rail for a transfer in or out.
ALTER TABLE ledger_transactions RENAME COLUMN funding_provider TO provider;

ALTER TABLE ledger_transactions
    RENAME CONSTRAINT ck_ledger_transactions_provider_pair TO ck_ledger_transactions_provider_reference_pair;

ALTER TABLE ledger_transactions
    RENAME CONSTRAINT uk_ledger_transactions_provider_reference TO uk_ledger_transactions_provider_payment;

-- The other side of a transfer to or from another bank, as the rail
-- reported it (inbound) or as name enquiry confirmed it (outbound).
ALTER TABLE ledger_transactions
    ADD COLUMN counterparty_name VARCHAR(200),
    ADD COLUMN counterparty_bank VARCHAR(100),
    ADD COLUMN counterparty_account_number VARCHAR(20);

-- Inbound transfers are initiated outside PayCore: nobody here asked for
-- them, so there is no initiator. Their idempotency is the rail's session
-- id (provider + provider_reference), not a customer's key.
ALTER TABLE ledger_transactions
    ALTER COLUMN initiated_by DROP NOT NULL;

-- For ADJUSTMENT and staff REVERSAL: the operations officer who approved
-- the request. Always someone other than the officer who made it.
ALTER TABLE ledger_transactions
    ADD COLUMN approved_by UUID;

ALTER TABLE ledger_transactions
    ADD CONSTRAINT fk_ledger_transactions_approved_by
        FOREIGN KEY (approved_by)
        REFERENCES customers (id);

-- ============================================================
-- TYPES AND THEIR RULES
-- ============================================================

-- DEPOSIT is now only a card top-up; WITHDRAWAL stays for history.
ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_type;

ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_type
        CHECK (type IN (
            'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL',
            'INBOUND_TRANSFER', 'OUTBOUND_TRANSFER', 'ADJUSTMENT'
        ));

ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_provider_deposit;
ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_staff_note;

-- Only an inbound transfer has no initiator.
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_initiator
        CHECK (initiated_by IS NOT NULL OR type = 'INBOUND_TRANSFER');

-- Provider-driven transactions: card top-ups, bank transfers in and out,
-- and the automatic reversal of an outbound transfer the rail rejected.
-- No staff member is involved in any of them.
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_provider_type
        CHECK (provider IS NULL OR (
            type IN ('DEPOSIT', 'INBOUND_TRANSFER', 'OUTBOUND_TRANSFER', 'REVERSAL')
            AND staff_note IS NULL
            AND approved_by IS NULL
        ));

-- Bank transfers always come through a rail and always name the other side.
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_bank_transfer
        CHECK (type NOT IN ('INBOUND_TRANSFER', 'OUTBOUND_TRANSFER') OR (
            provider IS NOT NULL
            AND counterparty_name IS NOT NULL
            AND counterparty_bank IS NOT NULL
            AND counterparty_account_number IS NOT NULL
        ));

-- Staff-made money movements (adjustments, and reversals not made by a
-- rail) record why, and who approved them; never the person who asked.
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_staff_note
        CHECK (
            type IN ('TRANSFER', 'INBOUND_TRANSFER', 'OUTBOUND_TRANSFER')
            OR provider IS NOT NULL
            OR staff_note IS NOT NULL
        );

ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_adjustment_approved
        CHECK (type <> 'ADJUSTMENT' OR (approved_by IS NOT NULL AND staff_note IS NOT NULL));

ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_four_eyes
        CHECK (approved_by IS NULL OR approved_by <> initiated_by);

-- ============================================================
-- ROLES: OPERATIONS INSTEAD OF ADMIN FOR MONEY
-- ============================================================

-- Nobody posts deposits or withdrawals by hand any more.
DELETE FROM role_permissions
WHERE permission_id IN (SELECT id FROM permissions WHERE name = 'LEDGER_POST');

DELETE FROM permissions WHERE name = 'LEDGER_POST';

INSERT INTO permissions (id, name)
VALUES
    (paycore_uuid_v7(), 'LEDGER_REQUEST'),
    (paycore_uuid_v7(), 'LEDGER_APPROVE');

INSERT INTO roles (id, name)
VALUES (paycore_uuid_v7(), 'OPERATIONS');

-- Operations officers find customers, accounts and transactions, request
-- corrections and approve other officers' requests. They do not manage
-- customers, roles or KYC: that stays with ADMIN.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name IN ('CUSTOMER_READ', 'ACCOUNT_VIEW_ALL', 'LEDGER_REQUEST', 'LEDGER_APPROVE')
WHERE r.name = 'OPERATIONS';

-- ============================================================
-- MAKER-CHECKER REQUESTS
-- ============================================================

CREATE TABLE operations_requests (
    id UUID PRIMARY KEY,

    -- ADJUSTMENT: credit or debit a customer account against settlement.
    -- REVERSAL: undo a posted transaction.
    type VARCHAR(20) NOT NULL,

    status VARCHAR(20) NOT NULL,

    -- ADJUSTMENT only.
    account_id UUID,
    direction VARCHAR(10),
    amount_minor BIGINT,
    currency VARCHAR(3),

    -- REVERSAL only.
    transaction_id UUID,

    -- Why, for staff (becomes the transaction's staff note), and what the
    -- customer reads on their statement (null: a neutral default).
    reason VARCHAR(500) NOT NULL,
    customer_description VARCHAR(500),

    requested_by UUID NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,

    decided_by UUID,
    decided_at TIMESTAMPTZ,
    decision_note VARCHAR(500),

    -- The ledger transaction an approval posted.
    result_transaction_id UUID,

    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_operations_requests_account
        FOREIGN KEY (account_id) REFERENCES accounts (id),

    CONSTRAINT fk_operations_requests_transaction
        FOREIGN KEY (transaction_id) REFERENCES ledger_transactions (id),

    CONSTRAINT fk_operations_requests_requested_by
        FOREIGN KEY (requested_by) REFERENCES customers (id),

    CONSTRAINT fk_operations_requests_decided_by
        FOREIGN KEY (decided_by) REFERENCES customers (id),

    CONSTRAINT fk_operations_requests_result
        FOREIGN KEY (result_transaction_id) REFERENCES ledger_transactions (id),

    CONSTRAINT ck_operations_requests_type
        CHECK (type IN ('ADJUSTMENT', 'REVERSAL')),

    CONSTRAINT ck_operations_requests_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),

    CONSTRAINT ck_operations_requests_shape
        CHECK (
            (type = 'ADJUSTMENT'
                AND account_id IS NOT NULL
                AND direction IN ('CREDIT', 'DEBIT')
                AND amount_minor > 0
                AND currency ~ '^[A-Z]{3}$'
                AND transaction_id IS NULL)
            OR
            (type = 'REVERSAL'
                AND transaction_id IS NOT NULL
                AND account_id IS NULL
                AND direction IS NULL
                AND amount_minor IS NULL
                AND currency IS NULL)
        ),

    -- Decided exactly when no longer pending; posted exactly when approved.
    CONSTRAINT ck_operations_requests_decision
        CHECK ((status = 'PENDING') = (decided_by IS NULL AND decided_at IS NULL)),

    CONSTRAINT ck_operations_requests_result
        CHECK ((status = 'APPROVED') = (result_transaction_id IS NOT NULL)),

    -- Four eyes: the checker is never the maker.
    CONSTRAINT ck_operations_requests_four_eyes
        CHECK (decided_by IS NULL OR decided_by <> requested_by)
);

-- The queue: pending requests, oldest first.
CREATE INDEX idx_operations_requests_status
    ON operations_requests (status, requested_at);

-- At most one open reversal request per transaction.
CREATE UNIQUE INDEX uk_operations_requests_pending_reversal
    ON operations_requests (transaction_id)
    WHERE type = 'REVERSAL' AND status = 'PENDING';
