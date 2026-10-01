-- Customers fund their own accounts through a payment provider.
--
-- A provider deposit is the same posting as a staff deposit:
--
--   deposit     DEBIT settlement   CREDIT customer
--
-- but nobody on staff decided it: the provider confirmed that the money
-- reached PayCore's settlement bank. Instead of a staff note it records
-- which provider confirmed it and the provider's own payment reference,
-- the key for reconciling against the provider's settlement report.
ALTER TABLE ledger_transactions
    ADD COLUMN funding_provider VARCHAR(30),
    ADD COLUMN provider_reference VARCHAR(100);

-- Both or neither.
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_provider_pair
        CHECK ((funding_provider IS NULL) = (provider_reference IS NULL));

-- Only deposits come from a provider, and no staff member wrote a note
-- for one.
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_provider_deposit
        CHECK (funding_provider IS NULL OR (type = 'DEPOSIT' AND staff_note IS NULL));

-- One provider payment is credited at most once, however often the
-- provider (or a retrying client) reports it.
ALTER TABLE ledger_transactions
    ADD CONSTRAINT uk_ledger_transactions_provider_reference
        UNIQUE (funding_provider, provider_reference);

-- Every staff-posted transaction still records why; provider deposits
-- record the provider's confirmation instead.
ALTER TABLE ledger_transactions
    DROP CONSTRAINT ck_ledger_transactions_staff_note;

ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_staff_note
        CHECK (type = 'TRANSFER' OR staff_note IS NOT NULL OR funding_provider IS NOT NULL);
