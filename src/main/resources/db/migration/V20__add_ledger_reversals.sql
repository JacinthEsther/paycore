-- Reversals. A posted transaction is never edited or deleted; its effect
-- is undone by a new REVERSAL transaction with the same entries on the
-- opposite sides, and the original moves to REVERSED.
--
--   original  TRANSFER  DEBIT Esther  CREDIT John
--   reversal  REVERSAL  DEBIT John    CREDIT Esther

ALTER TABLE ledger_transactions
    ADD COLUMN reverses_transaction_id UUID;

ALTER TABLE ledger_transactions
    ADD CONSTRAINT fk_ledger_transactions_reverses
        FOREIGN KEY (reverses_transaction_id)
        REFERENCES ledger_transactions (id);

-- A transaction is reversed at most once: the final protection behind
-- the service's row lock on the original.
ALTER TABLE ledger_transactions
    ADD CONSTRAINT uk_ledger_transactions_reverses
        UNIQUE (reverses_transaction_id);

-- Exactly the REVERSAL transactions point at what they reverse...
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_reversal_link
        CHECK ((type = 'REVERSAL') = (reverses_transaction_id IS NOT NULL));

-- ...and never at themselves.
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_reversal_not_self
        CHECK (reverses_transaction_id <> id);
