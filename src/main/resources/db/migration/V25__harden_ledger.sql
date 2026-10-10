-- Ledger hardening: the database refuses what the application must never
-- do, so a bug or a stray SQL statement cannot break the books.
--
--   1. Every ledger transaction balances: its debits equal its credits.
--   2. A POSTED transaction has entries.
--   3. Entries are append-only: never updated, never deleted.
--   4. A transaction's facts never change; only its lifecycle moves
--      forward (INITIATED -> POSTED -> REVERSED).
--
-- The application already enforces all four. These triggers are the last
-- line of defence, the same role the unique constraints play for
-- idempotency. They do not replace database privileges: in production
-- the application's role should also lack DELETE and TRUNCATE on these
-- tables, which no trigger can enforce against a table owner.
--
-- Maintenance escape hatch: a data repair (or a test fixture) may set
--   SET LOCAL paycore.ledger_maintenance = 'on'
-- inside its own transaction. It ends with that transaction and is never
-- set by the application.

-- ============================================================
-- 1 + 2. BALANCED TRANSACTIONS
-- ============================================================

-- Deferred to commit: LedgerService inserts the transaction, then its
-- debit, then its credit, and only the whole set has to balance.
CREATE FUNCTION paycore_ledger_require_balanced(p_transaction_id UUID)
    RETURNS void
    LANGUAGE plpgsql
AS $$
DECLARE
    debits  BIGINT;
    credits BIGINT;
BEGIN
    SELECT COALESCE(SUM(amount_minor) FILTER (WHERE entry_type = 'DEBIT'), 0),
           COALESCE(SUM(amount_minor) FILTER (WHERE entry_type = 'CREDIT'), 0)
    INTO debits, credits
    FROM ledger_entries
    WHERE transaction_id = p_transaction_id;

    IF debits = 0 OR debits <> credits THEN
        RAISE EXCEPTION
            'Ledger transaction % does not balance: debits %, credits %',
            p_transaction_id, debits, credits
            USING ERRCODE = 'check_violation',
                  CONSTRAINT = 'ck_ledger_transaction_balanced';
    END IF;
END;
$$;

CREATE FUNCTION paycore_ledger_entries_balanced()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM paycore_ledger_require_balanced(NEW.transaction_id);
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
    AFTER INSERT ON ledger_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION paycore_ledger_entries_balanced();

-- A transaction stored as POSTED must arrive with its entries in the same
-- database transaction.
CREATE FUNCTION paycore_ledger_posted_has_entries()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status <> 'INITIATED' THEN
        PERFORM paycore_ledger_require_balanced(NEW.id);
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_ledger_transactions_posted_has_entries
    AFTER INSERT ON ledger_transactions
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION paycore_ledger_posted_has_entries();

-- ============================================================
-- 3. APPEND-ONLY ENTRIES
-- ============================================================

CREATE FUNCTION paycore_ledger_maintenance()
    RETURNS boolean
    LANGUAGE sql
    STABLE
AS $$
    SELECT COALESCE(current_setting('paycore.ledger_maintenance', true), '') = 'on'
$$;

CREATE FUNCTION paycore_ledger_entries_append_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF paycore_ledger_maintenance() THEN
        RETURN COALESCE(NEW, OLD);
    END IF;

    RAISE EXCEPTION
        'Ledger entries are append-only: % of entry % refused; post a compensating transaction instead',
        TG_OP, OLD.id
        USING ERRCODE = 'integrity_constraint_violation',
              CONSTRAINT = 'ck_ledger_entries_append_only';
END;
$$;

CREATE TRIGGER trg_ledger_entries_append_only
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW
    EXECUTE FUNCTION paycore_ledger_entries_append_only();

-- ============================================================
-- 4. TRANSACTIONS ONLY MOVE FORWARD
-- ============================================================

CREATE FUNCTION paycore_ledger_transactions_forward_only()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF paycore_ledger_maintenance() THEN
        RETURN COALESCE(NEW, OLD);
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Ledger transaction % cannot be deleted; reverse it instead', OLD.id
            USING ERRCODE = 'integrity_constraint_violation',
                  CONSTRAINT = 'ck_ledger_transactions_forward_only';
    END IF;

    -- The facts of the transaction: what, how much, who, why, through whom.
    IF (NEW.id, NEW.reference, NEW.type, NEW.currency, NEW.initiated_by,
        NEW.idempotency_key, NEW.description, NEW.staff_note, NEW.approved_by,
        NEW.provider, NEW.provider_reference, NEW.counterparty_name,
        NEW.counterparty_bank, NEW.counterparty_account_number,
        NEW.created_at, NEW.reverses_transaction_id)
       IS DISTINCT FROM
       (OLD.id, OLD.reference, OLD.type, OLD.currency, OLD.initiated_by,
        OLD.idempotency_key, OLD.description, OLD.staff_note, OLD.approved_by,
        OLD.provider, OLD.provider_reference, OLD.counterparty_name,
        OLD.counterparty_bank, OLD.counterparty_account_number,
        OLD.created_at, OLD.reverses_transaction_id)
    THEN
        RAISE EXCEPTION 'Ledger transaction % is immutable apart from its status', OLD.id
            USING ERRCODE = 'integrity_constraint_violation',
                  CONSTRAINT = 'ck_ledger_transactions_forward_only';
    END IF;

    -- Once set, the moment it was posted or reversed stays.
    IF (OLD.posted_at IS NOT NULL AND NEW.posted_at IS DISTINCT FROM OLD.posted_at)
       OR (OLD.reversed_at IS NOT NULL AND NEW.reversed_at IS DISTINCT FROM OLD.reversed_at)
    THEN
        RAISE EXCEPTION 'Ledger transaction % cannot be re-dated', OLD.id
            USING ERRCODE = 'integrity_constraint_violation',
                  CONSTRAINT = 'ck_ledger_transactions_forward_only';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status
       AND NOT ((OLD.status = 'INITIATED' AND NEW.status = 'POSTED')
             OR (OLD.status = 'POSTED' AND NEW.status = 'REVERSED'))
    THEN
        RAISE EXCEPTION 'Ledger transaction % cannot move from % to %', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation',
                  CONSTRAINT = 'ck_ledger_transactions_forward_only';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ledger_transactions_forward_only
    BEFORE UPDATE OR DELETE ON ledger_transactions
    FOR EACH ROW
    EXECUTE FUNCTION paycore_ledger_transactions_forward_only();
