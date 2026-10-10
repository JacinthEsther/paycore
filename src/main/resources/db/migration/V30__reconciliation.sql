-- Reconciliation: comparing PayCore's books with each provider's own.
--
-- Every provider (the bank rail, the card processor) reports what it did on
-- a day: its settlement report. A reconciliation run takes one provider
-- and one business day (Lagos), and matches every line of the report with
-- what PayCore recorded, and every PayCore payment with the report:
--
--   MATCHED           both agree on the reference, the amount and the outcome
--   MISSING_LOCAL     the provider moved money PayCore has no record of
--                     (e.g. an inbound transfer whose notification was lost)
--   MISSING_PROVIDER  PayCore recorded money the provider never moved
--   AMOUNT_MISMATCH   both have it, for different amounts
--   STATUS_MISMATCH   both have it, with different outcomes (paid vs failed,
--                     or PayCore still waiting for an answer)
--
-- Anything but MATCHED is a break: it stays OPEN until it is fixed (money
-- through the usual controls: re-delivery by the rail, maker-checker
-- reversal or adjustment) and a supervisor resolves it with a note. A run
-- never changes once made; running the same day again makes a new run,
-- which carries over breaks already resolved.

CREATE TABLE reconciliation_runs (
    id UUID PRIMARY KEY,

    -- BANK_RAIL or CARD, and which provider of that kind.
    provider_kind VARCHAR(20) NOT NULL,
    provider VARCHAR(30) NOT NULL,

    -- The Lagos calendar day reconciled, and the instants it covers.
    business_date DATE NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,

    -- The officer who ran it; NULL for the scheduled daily run.
    started_by UUID,

    provider_lines INT NOT NULL,
    paycore_records INT NOT NULL,
    matched INT NOT NULL,
    breaks INT NOT NULL,

    -- Money that moved by each side's account, in minor units (NGN).
    provider_total_minor BIGINT NOT NULL,
    paycore_total_minor BIGINT NOT NULL,

    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_reconciliation_runs_started_by
        FOREIGN KEY (started_by) REFERENCES customers (id),

    CONSTRAINT ck_reconciliation_runs_kind
        CHECK (provider_kind IN ('BANK_RAIL', 'CARD')),

    CONSTRAINT ck_reconciliation_runs_window
        CHECK (window_end > window_start),

    CONSTRAINT ck_reconciliation_runs_counts
        CHECK (provider_lines >= 0 AND paycore_records >= 0 AND matched >= 0 AND breaks >= 0)
);

CREATE INDEX idx_reconciliation_runs_created ON reconciliation_runs (created_at DESC);

CREATE TABLE reconciliation_items (
    id UUID PRIMARY KEY,

    run_id UUID NOT NULL,

    -- OUTBOUND / INBOUND (bank rail) or CARD.
    kind VARCHAR(20) NOT NULL,

    -- The key both sides share: NIP session id or merchant reference.
    external_reference VARCHAR(100) NOT NULL,

    result VARCHAR(20) NOT NULL,

    currency VARCHAR(3) NOT NULL,

    provider_amount_minor BIGINT,
    provider_status VARCHAR(20),

    paycore_amount_minor BIGINT,
    paycore_status VARCHAR(20),

    -- What PayCore has for it, if anything.
    payment_id UUID,
    ledger_transaction_id UUID,
    account_id UUID,

    -- What is wrong, in words, for the officer working the break.
    detail VARCHAR(500),

    -- NOT_REQUIRED for a match; OPEN -> RESOLVED for a break.
    resolution_status VARCHAR(20) NOT NULL,
    resolved_by UUID,
    resolved_at TIMESTAMPTZ,
    resolution_note VARCHAR(500),

    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_reconciliation_items_run
        FOREIGN KEY (run_id) REFERENCES reconciliation_runs (id),

    CONSTRAINT fk_reconciliation_items_payment
        FOREIGN KEY (payment_id) REFERENCES payments (id),

    CONSTRAINT fk_reconciliation_items_ledger_transaction
        FOREIGN KEY (ledger_transaction_id) REFERENCES ledger_transactions (id),

    CONSTRAINT fk_reconciliation_items_account
        FOREIGN KEY (account_id) REFERENCES accounts (id),

    CONSTRAINT fk_reconciliation_items_resolved_by
        FOREIGN KEY (resolved_by) REFERENCES customers (id),

    CONSTRAINT uk_reconciliation_items_run_reference
        UNIQUE (run_id, kind, external_reference),

    CONSTRAINT ck_reconciliation_items_kind
        CHECK (kind IN ('OUTBOUND', 'INBOUND', 'CARD')),

    CONSTRAINT ck_reconciliation_items_result
        CHECK (result IN ('MATCHED', 'MISSING_LOCAL', 'MISSING_PROVIDER', 'AMOUNT_MISMATCH', 'STATUS_MISMATCH')),

    -- Each side is either present (amount and status) or missing.
    CONSTRAINT ck_reconciliation_items_provider_side
        CHECK ((provider_amount_minor IS NULL) = (provider_status IS NULL)),

    CONSTRAINT ck_reconciliation_items_paycore_side
        CHECK ((paycore_amount_minor IS NULL) = (paycore_status IS NULL)),

    CONSTRAINT ck_reconciliation_items_missing
        CHECK ((result <> 'MISSING_LOCAL' OR paycore_status IS NULL)
           AND (result <> 'MISSING_PROVIDER' OR provider_status IS NULL)),

    -- Matches need no work; breaks are open until resolved, by someone, why.
    CONSTRAINT ck_reconciliation_items_resolution
        CHECK (
            (result = 'MATCHED' AND resolution_status = 'NOT_REQUIRED' AND resolved_by IS NULL)
            OR (result <> 'MATCHED' AND resolution_status = 'OPEN'
                AND resolved_at IS NULL AND resolution_note IS NULL)
            OR (result <> 'MATCHED' AND resolution_status = 'RESOLVED'
                AND resolved_at IS NOT NULL AND resolution_note IS NOT NULL)
        )
);

CREATE INDEX idx_reconciliation_items_run ON reconciliation_items (run_id, result);

-- Finding earlier resolutions of the same break, for carry-over.
CREATE INDEX idx_reconciliation_items_reference
    ON reconciliation_items (external_reference, kind, result)
    WHERE resolution_status = 'RESOLVED';

-- A run is evidence: once made, only a break's resolution may change.
CREATE FUNCTION paycore_reconciliation_items_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Reconciliation item % cannot be deleted', OLD.id
            USING ERRCODE = 'integrity_constraint_violation',
                  CONSTRAINT = 'ck_reconciliation_items_immutable';
    END IF;

    IF (NEW.run_id, NEW.kind, NEW.external_reference, NEW.result, NEW.currency,
        NEW.provider_amount_minor, NEW.provider_status, NEW.paycore_amount_minor,
        NEW.paycore_status, NEW.payment_id, NEW.ledger_transaction_id, NEW.account_id,
        NEW.detail, NEW.created_at)
       IS DISTINCT FROM
       (OLD.run_id, OLD.kind, OLD.external_reference, OLD.result, OLD.currency,
        OLD.provider_amount_minor, OLD.provider_status, OLD.paycore_amount_minor,
        OLD.paycore_status, OLD.payment_id, OLD.ledger_transaction_id, OLD.account_id,
        OLD.detail, OLD.created_at)
       OR (OLD.resolution_status <> 'OPEN' AND NEW IS DISTINCT FROM OLD)
    THEN
        RAISE EXCEPTION 'Reconciliation item % is evidence; only an open break can be resolved', OLD.id
            USING ERRCODE = 'integrity_constraint_violation',
                  CONSTRAINT = 'ck_reconciliation_items_immutable';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_reconciliation_items_guard
    BEFORE UPDATE OR DELETE ON reconciliation_items
    FOR EACH ROW
    EXECUTE FUNCTION paycore_reconciliation_items_guard();

-- ============================================================
-- THE SIMULATED PROVIDERS' SETTLEMENT REPORTS
-- ============================================================

-- The sender of an inbound session, so the rail can deliver its
-- notification again when PayCore missed it.
ALTER TABLE simulator.rail_sessions
    ADD COLUMN sender_name VARCHAR(200),
    ADD COLUMN sender_bank VARCHAR(100),
    ADD COLUMN sender_account_number VARCHAR(20),
    -- Whether PayCore was notified of an inbound session (false: lost).
    ADD COLUMN notified BOOLEAN NOT NULL DEFAULT true;

-- Whether a charge is in the processor's settlement report (false: the
-- processor has not settled it to PayCore's bank).
ALTER TABLE simulator.card_payments
    ADD COLUMN settled BOOLEAN NOT NULL DEFAULT true;
