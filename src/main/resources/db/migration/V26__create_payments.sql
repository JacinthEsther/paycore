-- Payments: the business process around money that crosses PayCore's
-- boundary, as opposed to the ledger, which records the accounting.
--
--   payment PAY-20261005-...   card top-up of N5,000, status PROCESSING
--   ledger  TXN-20261005-...   DEBIT settlement N5,000, CREDIT customer N5,000
--
-- A payment exists for every call to an external provider: a card top-up
-- (the funding provider) and a transfer to another bank (the bank rail).
-- Transfers between two PayCore accounts never leave PayCore and stay
-- ledger-only.
--
-- The state machine (enforced below, and in Payment.java):
--
--   CREATED ------> PROCESSING ----> SUCCEEDED
--      |               |   |
--      |               |   +-------> FAILED
--      |               v
--      |            UNKNOWN -------> SUCCEEDED / FAILED
--      |               |
--      |               v
--      +--> FAILED  NEEDS_REVIEW --> SUCCEEDED / FAILED
--
-- CREATED     recorded; for a bank transfer the customer is already
--             debited, but nothing has been sent to the rail yet
-- PROCESSING  handed to the provider, answer not yet recorded
-- UNKNOWN     the provider did not answer (timeout, outage) or PayCore
--             stopped before recording it: never assumed failed, because
--             the provider may have acted. A status check settles it.
-- NEEDS_REVIEW automatic status checks gave up, or the provider took the
--             customer's money but PayCore could not credit it. Operations
--             must look.
-- SUCCEEDED / FAILED  final; a failed bank transfer has been reversed.

CREATE TABLE payments (
    id UUID PRIMARY KEY,

    -- PAY-20261005-8F3A2C91D0B47E15, for customers and support.
    reference VARCHAR(40) NOT NULL,

    type VARCHAR(30) NOT NULL,

    status VARCHAR(20) NOT NULL,

    customer_id UUID NOT NULL,

    -- The customer account money leaves (transfer) or arrives in (top-up).
    account_id UUID NOT NULL,

    amount_minor BIGINT NOT NULL,

    currency VARCHAR(3) NOT NULL,

    -- The customer's key. One key, one payment, whatever the retries.
    idempotency_key VARCHAR(100) NOT NULL,

    provider VARCHAR(30) NOT NULL,

    -- PayCore's own name for the payment at the provider, and the key for
    -- asking it what happened: the NIP session id of a transfer, the
    -- merchant reference of a top-up.
    external_reference VARCHAR(100) NOT NULL,

    -- The provider's own id for it, once the provider has told us.
    provider_reference VARCHAR(100),

    narration VARCHAR(500),

    -- The other bank's account holder (transfers only).
    counterparty_name VARCHAR(200),
    counterparty_bank_code VARCHAR(10),
    counterparty_bank VARCHAR(100),
    counterparty_account_number VARCHAR(20),

    -- The ledger posting this payment made: the debit of a transfer (made
    -- when the payment is created), the credit of a top-up (made once the
    -- provider confirmed it).
    ledger_transaction_id UUID,

    -- The reversal that returned a failed transfer's money.
    reversal_transaction_id UUID,

    -- Customer-safe wording; technical detail goes to the logs.
    failure_reason VARCHAR(500),

    -- Status checks made with the provider, and when the next one is due.
    status_checks INT NOT NULL DEFAULT 0,
    next_check_at TIMESTAMPTZ,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,

    version BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT fk_payments_customer
        FOREIGN KEY (customer_id) REFERENCES customers (id),

    -- The payment's currency is its account's.
    CONSTRAINT fk_payments_account
        FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency),

    CONSTRAINT fk_payments_ledger_transaction
        FOREIGN KEY (ledger_transaction_id) REFERENCES ledger_transactions (id),

    CONSTRAINT fk_payments_reversal_transaction
        FOREIGN KEY (reversal_transaction_id) REFERENCES ledger_transactions (id),

    CONSTRAINT uk_payments_reference UNIQUE (reference),

    -- The race-proof form of "have I seen this request before?".
    CONSTRAINT uk_payments_customer_idempotency_key UNIQUE (customer_id, idempotency_key),

    -- One provider payment is one PayCore payment.
    CONSTRAINT uk_payments_provider_external_reference UNIQUE (provider, external_reference),

    CONSTRAINT uk_payments_ledger_transaction UNIQUE (ledger_transaction_id),

    CONSTRAINT uk_payments_reversal_transaction UNIQUE (reversal_transaction_id),

    CONSTRAINT ck_payments_type
        CHECK (type IN ('OUTBOUND_TRANSFER', 'CARD_FUNDING')),

    CONSTRAINT ck_payments_status
        CHECK (status IN ('CREATED', 'PROCESSING', 'UNKNOWN', 'NEEDS_REVIEW', 'SUCCEEDED', 'FAILED')),

    CONSTRAINT ck_payments_amount_positive
        CHECK (amount_minor > 0),

    CONSTRAINT ck_payments_currency
        CHECK (currency ~ '^[A-Z]{3}$'),

    CONSTRAINT ck_payments_status_checks
        CHECK (status_checks >= 0),

    -- Completed exactly when final.
    CONSTRAINT ck_payments_completed_at
        CHECK ((status IN ('SUCCEEDED', 'FAILED')) = (completed_at IS NOT NULL)),

    -- An unknown outcome always has its next status check scheduled.
    CONSTRAINT ck_payments_unknown_scheduled
        CHECK (status <> 'UNKNOWN' OR next_check_at IS NOT NULL),

    -- Success always has its ledger posting.
    CONSTRAINT ck_payments_succeeded_posted
        CHECK (status <> 'SUCCEEDED' OR ledger_transaction_id IS NOT NULL),

    -- A failure always says why.
    CONSTRAINT ck_payments_failed_reason
        CHECK (status <> 'FAILED' OR failure_reason IS NOT NULL),

    -- A transfer always names the beneficiary and is debited from the start;
    -- if it failed, the money went back.
    CONSTRAINT ck_payments_outbound_shape
        CHECK (type <> 'OUTBOUND_TRANSFER' OR (
            counterparty_name IS NOT NULL
            AND counterparty_bank IS NOT NULL
            AND counterparty_account_number IS NOT NULL
            AND ledger_transaction_id IS NOT NULL
            AND (status <> 'FAILED' OR reversal_transaction_id IS NOT NULL)
        )),

    -- A top-up has no beneficiary and nothing to reverse: money that never
    -- arrived was never credited.
    CONSTRAINT ck_payments_funding_shape
        CHECK (type <> 'CARD_FUNDING' OR (
            counterparty_name IS NULL
            AND counterparty_account_number IS NULL
            AND reversal_transaction_id IS NULL
            AND (status <> 'SUCCEEDED' OR provider_reference IS NOT NULL)
        ))
);

-- The status-check queue: what is due, oldest first.
CREATE INDEX idx_payments_status_check
    ON payments (status, next_check_at)
    WHERE status IN ('CREATED', 'PROCESSING', 'UNKNOWN', 'NEEDS_REVIEW');

CREATE INDEX idx_payments_customer_created
    ON payments (customer_id, created_at DESC);

-- ============================================================
-- BACKFILL: PAYMENTS MADE BEFORE THIS TABLE EXISTED
-- ============================================================
-- Every outbound transfer and provider top-up already in the ledger gets
-- its payment, so every provider payment has one from now on (the base
-- for reconciliation). They were all settled at the time.

INSERT INTO payments (
    id, reference, type, status, customer_id, account_id, amount_minor, currency,
    idempotency_key, provider, external_reference, provider_reference, narration,
    counterparty_name, counterparty_bank, counterparty_account_number,
    ledger_transaction_id, reversal_transaction_id, failure_reason,
    created_at, updated_at, completed_at
)
SELECT
    paycore_uuid_v7(),
    'PAY-' || to_char(t.created_at AT TIME ZONE 'UTC', 'YYYYMMDD') || '-' || upper(substr(md5(t.id::text), 1, 16)),
    'OUTBOUND_TRANSFER',
    CASE WHEN t.status = 'REVERSED' THEN 'FAILED' ELSE 'SUCCEEDED' END,
    t.initiated_by,
    debit.account_id,
    debit.amount_minor,
    t.currency,
    t.idempotency_key,
    t.provider,
    t.provider_reference,
    t.provider_reference,
    t.description,
    t.counterparty_name,
    t.counterparty_bank,
    t.counterparty_account_number,
    t.id,
    reversal.id,
    CASE WHEN t.status = 'REVERSED' THEN 'The transfer failed and the money was returned' END,
    t.created_at,
    COALESCE(t.reversed_at, t.posted_at, t.created_at),
    COALESCE(t.reversed_at, t.posted_at, t.created_at)
FROM ledger_transactions t
JOIN ledger_entries debit
    ON debit.transaction_id = t.id AND debit.entry_type = 'DEBIT'
LEFT JOIN ledger_transactions reversal
    ON reversal.reverses_transaction_id = t.id
WHERE t.type = 'OUTBOUND_TRANSFER';

-- Card top-ups. The merchant reference is FundingService.merchantReference:
-- 'PC-' and the first 16 bytes of SHA-256("<customer id>:<key>"), in hex.
INSERT INTO payments (
    id, reference, type, status, customer_id, account_id, amount_minor, currency,
    idempotency_key, provider, external_reference, provider_reference, narration,
    ledger_transaction_id, created_at, updated_at, completed_at
)
SELECT
    paycore_uuid_v7(),
    'PAY-' || to_char(t.created_at AT TIME ZONE 'UTC', 'YYYYMMDD') || '-' || upper(substr(md5(t.id::text), 1, 16)),
    'CARD_FUNDING',
    'SUCCEEDED',
    t.initiated_by,
    credit.account_id,
    credit.amount_minor,
    t.currency,
    t.idempotency_key,
    t.provider,
    'PC-' || upper(substr(encode(sha256(convert_to(t.initiated_by::text || ':' || t.idempotency_key, 'UTF8')), 'hex'), 1, 32)),
    t.provider_reference,
    t.description,
    t.id,
    t.created_at,
    COALESCE(t.posted_at, t.created_at),
    COALESCE(t.posted_at, t.created_at)
FROM ledger_transactions t
JOIN ledger_entries credit
    ON credit.transaction_id = t.id AND credit.entry_type = 'CREDIT'
WHERE t.type = 'DEPOSIT'
  AND t.provider IS NOT NULL;

-- ============================================================
-- THE STATE MACHINE, IN THE DATABASE
-- ============================================================

CREATE FUNCTION paycore_payments_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Payment % cannot be deleted', OLD.id
            USING ERRCODE = 'integrity_constraint_violation',
                  CONSTRAINT = 'ck_payments_state_machine';
    END IF;

    -- What was asked for never changes.
    IF (NEW.id, NEW.reference, NEW.type, NEW.customer_id, NEW.account_id,
        NEW.amount_minor, NEW.currency, NEW.idempotency_key, NEW.provider,
        NEW.external_reference, NEW.counterparty_name, NEW.counterparty_bank_code,
        NEW.counterparty_bank, NEW.counterparty_account_number, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.reference, OLD.type, OLD.customer_id, OLD.account_id,
        OLD.amount_minor, OLD.currency, OLD.idempotency_key, OLD.provider,
        OLD.external_reference, OLD.counterparty_name, OLD.counterparty_bank_code,
        OLD.counterparty_bank, OLD.counterparty_account_number, OLD.created_at)
    THEN
        RAISE EXCEPTION 'Payment % cannot change what was asked for', OLD.id
            USING ERRCODE = 'integrity_constraint_violation',
                  CONSTRAINT = 'ck_payments_state_machine';
    END IF;

    -- Final is final.
    IF OLD.status IN ('SUCCEEDED', 'FAILED') THEN
        RAISE EXCEPTION 'Payment % is % and cannot change', OLD.id, OLD.status
            USING ERRCODE = 'integrity_constraint_violation',
                  CONSTRAINT = 'ck_payments_state_machine';
    END IF;

    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'CREATED'      AND NEW.status IN ('PROCESSING', 'FAILED'))
        OR (OLD.status = 'PROCESSING'   AND NEW.status IN ('SUCCEEDED', 'FAILED', 'UNKNOWN', 'NEEDS_REVIEW'))
        OR (OLD.status = 'UNKNOWN'      AND NEW.status IN ('SUCCEEDED', 'FAILED', 'NEEDS_REVIEW'))
        OR (OLD.status = 'NEEDS_REVIEW' AND NEW.status IN ('SUCCEEDED', 'FAILED'))
    ) THEN
        RAISE EXCEPTION 'Payment % cannot move from % to %', OLD.id, OLD.status, NEW.status
            USING ERRCODE = 'integrity_constraint_violation',
                  CONSTRAINT = 'ck_payments_state_machine';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_payments_guard
    BEFORE UPDATE OR DELETE ON payments
    FOR EACH ROW
    EXECUTE FUNCTION paycore_payments_guard();

-- ============================================================
-- AUDIT TRAIL OF EVERY STATUS CHANGE
-- ============================================================
-- Written by the database, so no code path can move a payment without a
-- record of it.

CREATE TABLE payment_events (
    id UUID PRIMARY KEY,

    payment_id UUID NOT NULL,

    -- NULL for the payment's creation.
    from_status VARCHAR(20),

    to_status VARCHAR(20) NOT NULL,

    status_checks INT NOT NULL,

    failure_reason VARCHAR(500),

    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_payment_events_payment
        FOREIGN KEY (payment_id) REFERENCES payments (id)
);

CREATE INDEX idx_payment_events_payment
    ON payment_events (payment_id, created_at);

CREATE FUNCTION paycore_payments_audit()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.status IS DISTINCT FROM OLD.status THEN
        INSERT INTO payment_events (id, payment_id, from_status, to_status, status_checks, failure_reason, created_at)
        VALUES (
            paycore_uuid_v7(),
            NEW.id,
            CASE WHEN TG_OP = 'UPDATE' THEN OLD.status END,
            NEW.status,
            NEW.status_checks,
            NEW.failure_reason,
            clock_timestamp()
        );
    END IF;

    RETURN NULL;
END;
$$;

CREATE TRIGGER trg_payments_audit
    AFTER INSERT OR UPDATE ON payments
    FOR EACH ROW
    EXECUTE FUNCTION paycore_payments_audit();

-- No new permissions: customers read their own payments with
-- TRANSACTION_READ; staff list them with ACCOUNT_VIEW_ALL; operations
-- officers ask the provider again with LEDGER_REQUEST.
