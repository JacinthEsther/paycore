-- Bill payments: airtime, data, electricity and TV, paid through a bills
-- aggregator (VTpass, or the simulator).
--
-- They work like a transfer to another bank: the customer is debited
-- first (DEBIT customer, CREDIT settlement: PayCore pays the aggregator
-- from the money it holds), then the aggregator is asked to deliver. If it
-- refuses, the debit is reversed; if it does not answer, the payment is
-- UNKNOWN until a status query (requery) settles it.
--
-- The biller and the customer's number (phone, meter, smartcard) are the
-- counterparty: counterparty_name "Ikeja Electric (prepaid)",
-- counterparty_bank "Electricity", counterparty_account_number the meter.

-- ============================================================
-- LEDGER
-- ============================================================

ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_type;

ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_type
        CHECK (type IN (
            'DEPOSIT', 'WITHDRAWAL', 'TRANSFER', 'REVERSAL',
            'INBOUND_TRANSFER', 'OUTBOUND_TRANSFER', 'ADJUSTMENT', 'BILL_PAYMENT'
        ));

ALTER TABLE ledger_transactions DROP CONSTRAINT ck_ledger_transactions_provider_type;

ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_provider_type
        CHECK (provider IS NULL OR (
            type IN ('DEPOSIT', 'INBOUND_TRANSFER', 'OUTBOUND_TRANSFER', 'BILL_PAYMENT', 'REVERSAL')
            AND staff_note IS NULL
            AND approved_by IS NULL
        ));

-- A bill is always paid through an aggregator, to a named biller.
ALTER TABLE ledger_transactions
    ADD CONSTRAINT ck_ledger_transactions_bill_payment
        CHECK (type <> 'BILL_PAYMENT' OR (
            provider IS NOT NULL
            AND provider_reference IS NOT NULL
            AND counterparty_name IS NOT NULL
            AND counterparty_account_number IS NOT NULL
        ));

-- ============================================================
-- PAYMENTS
-- ============================================================

ALTER TABLE payments
    -- The aggregator's id for the biller (VTpass serviceID, e.g. "ikeja-electric").
    ADD COLUMN bill_biller_id VARCHAR(50),
    -- The product bought: a data bundle or TV bouquet (VTpass variation_code),
    -- or the meter type for electricity. Null for airtime.
    ADD COLUMN bill_product_code VARCHAR(100),
    ADD COLUMN bill_product_name VARCHAR(200),
    -- What the biller gave back, e.g. a prepaid meter's token. Arrives with
    -- the answer, so it may be set after the payment was created.
    ADD COLUMN bill_token VARCHAR(300);

ALTER TABLE payments DROP CONSTRAINT ck_payments_type;

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_type
        CHECK (type IN ('OUTBOUND_TRANSFER', 'CARD_FUNDING', 'BILL_PAYMENT'));

-- A bill names its biller and number and is debited from the start; if it
-- failed, the money went back.
ALTER TABLE payments
    ADD CONSTRAINT ck_payments_bill_shape
        CHECK (type <> 'BILL_PAYMENT' OR (
            bill_biller_id IS NOT NULL
            AND counterparty_name IS NOT NULL
            AND counterparty_account_number IS NOT NULL
            AND ledger_transaction_id IS NOT NULL
            AND (status <> 'FAILED' OR reversal_transaction_id IS NOT NULL)
        ));

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_bill_columns
        CHECK (type = 'BILL_PAYMENT' OR (
            bill_biller_id IS NULL AND bill_product_code IS NULL
            AND bill_product_name IS NULL AND bill_token IS NULL
        ));

-- What was asked for never changes, now including the bill; the token
-- (the biller's answer) may be filled in once.
CREATE OR REPLACE FUNCTION paycore_payments_guard()
    RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Payment % cannot be deleted', OLD.id
            USING ERRCODE = 'integrity_constraint_violation',
                  CONSTRAINT = 'ck_payments_state_machine';
    END IF;

    IF (NEW.id, NEW.reference, NEW.type, NEW.customer_id, NEW.account_id,
        NEW.amount_minor, NEW.currency, NEW.idempotency_key, NEW.provider,
        NEW.external_reference, NEW.counterparty_name, NEW.counterparty_bank_code,
        NEW.counterparty_bank, NEW.counterparty_account_number, NEW.created_at,
        NEW.bill_biller_id, NEW.bill_product_code, NEW.bill_product_name)
       IS DISTINCT FROM
       (OLD.id, OLD.reference, OLD.type, OLD.customer_id, OLD.account_id,
        OLD.amount_minor, OLD.currency, OLD.idempotency_key, OLD.provider,
        OLD.external_reference, OLD.counterparty_name, OLD.counterparty_bank_code,
        OLD.counterparty_bank, OLD.counterparty_account_number, OLD.created_at,
        OLD.bill_biller_id, OLD.bill_product_code, OLD.bill_product_name)
    THEN
        RAISE EXCEPTION 'Payment % cannot change what was asked for', OLD.id
            USING ERRCODE = 'integrity_constraint_violation',
                  CONSTRAINT = 'ck_payments_state_machine';
    END IF;

    IF OLD.bill_token IS NOT NULL AND NEW.bill_token IS DISTINCT FROM OLD.bill_token THEN
        RAISE EXCEPTION 'Payment % already has its token', OLD.id
            USING ERRCODE = 'integrity_constraint_violation',
                  CONSTRAINT = 'ck_payments_state_machine';
    END IF;

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

-- ============================================================
-- THE SIMULATED AGGREGATOR'S OWN RECORDS
-- ============================================================
-- As for the simulated rail (V28): what its requery reads, kept by the
-- "aggregator", not by PayCore.

CREATE TABLE simulator.bill_payments (
    request_id VARCHAR(60) PRIMARY KEY,
    biller_id VARCHAR(50) NOT NULL,
    product_code VARCHAR(100),
    customer_number VARCHAR(30) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    -- DELIVERED: the biller gave value. FAILED: refused, nothing given.
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(500),
    token VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_sim_bill_payments_status CHECK (status IN ('DELIVERED', 'FAILED')),
    CONSTRAINT ck_sim_bill_payments_amount CHECK (amount_minor > 0)
);

CREATE INDEX idx_sim_bill_payments_created ON simulator.bill_payments (created_at);
