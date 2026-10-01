-- Staff notes are internal; descriptions are what customers see.
--
-- Until now a staff deposit, withdrawal or reversal stored the staff
-- member's reason ("Fraudulent transfer", "Payout bounced at bank") as the
-- transaction description, which customers read on their transactions and
-- statements. The reason moves to staff_note, visible only to staff, and
-- the description becomes a neutral narration.

ALTER TABLE ledger_transactions
    ADD COLUMN staff_note VARCHAR(500);

UPDATE ledger_transactions
SET staff_note = COALESCE(description, 'Recorded before staff notes were separated')
WHERE type IN ('DEPOSIT', 'WITHDRAWAL', 'REVERSAL');

UPDATE ledger_transactions
SET description = CASE type WHEN 'DEPOSIT' THEN 'Deposit' ELSE 'Withdrawal' END
WHERE type IN ('DEPOSIT', 'WITHDRAWAL');

UPDATE ledger_transactions reversal
SET description = 'Reversal of ' || original.reference
FROM ledger_transactions original
WHERE reversal.type = 'REVERSAL'
  AND original.id = reversal.reverses_transaction_id;

-- Every staff-posted transaction records why. Customer transfers have no
-- staff involvement, so no note.
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_staff_note
        CHECK (type = 'TRANSFER' OR staff_note IS NOT NULL);
