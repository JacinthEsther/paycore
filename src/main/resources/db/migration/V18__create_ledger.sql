-- Double-entry ledger: the source of truth for money.
--
-- A ledger transaction is one financial event (a transfer); its entries
-- are the accounting sides of that event (a debit and a credit). An
-- account's balance is derived from its entries, CREDITS - DEBITS, so
-- accounts still have no balance column. Entries are never updated or
-- deleted: a mistake is corrected by a new, compensating transaction.
--
-- Amounts are whole minor units (kobo for NGN) in BIGINT, never floating
-- point, and always positive: the entry type carries the direction.

CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY,

    -- Human-facing identifier for support, statements and reconciliation,
    -- e.g. TXN-20260930-8F3A2C91D0B4. The UUID stays the database identity.
    reference VARCHAR(100) NOT NULL,

    type VARCHAR(30) NOT NULL,

    status VARCHAR(30) NOT NULL,

    -- One transaction has exactly one currency; every entry uses it.
    currency VARCHAR(3) NOT NULL,

    -- The customer who asked for the transaction. Idempotency keys are
    -- scoped to them, so one customer's key can never collide with, or
    -- replay, another customer's transaction.
    initiated_by UUID NOT NULL,

    idempotency_key VARCHAR(100) NOT NULL,

    description VARCHAR(500),

    created_at TIMESTAMPTZ NOT NULL,

    posted_at TIMESTAMPTZ,

    reversed_at TIMESTAMPTZ,

    CONSTRAINT fk_ledger_transactions_initiated_by
        FOREIGN KEY (initiated_by)
        REFERENCES customers(id),

    CONSTRAINT uk_ledger_transactions_reference
        UNIQUE (reference),

    -- The final protection against a retried request moving money twice;
    -- the service's lookup alone can race.
    CONSTRAINT uk_ledger_transactions_initiator_idempotency_key
        UNIQUE (initiated_by, idempotency_key),

    -- Target of the entries' composite foreign key below.
    CONSTRAINT uk_ledger_transactions_id_currency
        UNIQUE (id, currency),

    CONSTRAINT ck_ledger_transactions_type
        CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL')),

    CONSTRAINT ck_ledger_transactions_status
        CHECK (status IN ('INITIATED', 'POSTED', 'REVERSED')),

    CONSTRAINT ck_ledger_transactions_currency
        CHECK (currency ~ '^[A-Z]{3}$'),

    -- posted_at is set once the transaction has been posted, and stays set
    -- after a reversal; reversed_at exactly when it has been reversed.
    CONSTRAINT ck_ledger_transactions_posted_at
        CHECK ((status = 'INITIATED') = (posted_at IS NULL)),

    CONSTRAINT ck_ledger_transactions_reversed_at
        CHECK ((status = 'REVERSED') = (reversed_at IS NOT NULL))
);

CREATE INDEX idx_ledger_transactions_created
    ON ledger_transactions (created_at DESC);

CREATE INDEX idx_ledger_transactions_initiated_by
    ON ledger_transactions (initiated_by);


-- Target of the entries' composite foreign key below: lets the database
-- guarantee an entry is in its account's currency.
ALTER TABLE accounts
    ADD CONSTRAINT uk_accounts_id_currency
        UNIQUE (id, currency);


CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,

    transaction_id UUID NOT NULL,

    account_id UUID NOT NULL,

    entry_type VARCHAR(10) NOT NULL,

    amount_minor BIGINT NOT NULL,

    currency VARCHAR(3) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL,

    -- The entry's currency is its transaction's currency...
    CONSTRAINT fk_ledger_entries_transaction
        FOREIGN KEY (transaction_id, currency)
        REFERENCES ledger_transactions (id, currency),

    -- ...and its account's currency. No FX through the back door.
    CONSTRAINT fk_ledger_entries_account
        FOREIGN KEY (account_id, currency)
        REFERENCES accounts (id, currency),

    CONSTRAINT ck_ledger_entries_amount_positive
        CHECK (amount_minor > 0),

    CONSTRAINT ck_ledger_entries_type
        CHECK (entry_type IN ('DEBIT', 'CREDIT'))
);

CREATE INDEX idx_ledger_entries_transaction
    ON ledger_entries (transaction_id);

-- Account history (newest first) and the balance SUM. The INCLUDE columns
-- let the balance be computed from the index alone, without visiting the
-- table rows.
CREATE INDEX idx_ledger_entries_account_created
    ON ledger_entries (account_id, created_at DESC)
    INCLUDE (entry_type, amount_minor, currency);

-- No new permissions: CUSTOMER already has TRANSACTION_READ (V6) and
-- TRANSACTION_CREATE (V12).
