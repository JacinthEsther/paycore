-- Resolving a payment in NEEDS_REVIEW, under maker-checker.
--
-- Automation hands a payment to operations when it cannot settle it: the
-- provider never answered a status check, or it took the customer's money
-- but PayCore could not credit it. Staff then decide, with evidence from
-- outside PayCore (the provider's support desk, a bank statement), but
-- never alone: one officer requests a resolution, a different one approves
-- it, exactly like adjustments and reversals (V24).
--
--   transfer out   COMPLETE  the beneficiary was paid; the debit stands
--                  REVERSE   they were not; the debit is reversed
--   card top-up    CREDIT    the card was charged; credit the customer
--                  REFUND    the card was charged; refund it instead
--                  CLOSE     the card was never charged; nothing to do
--
-- At approval PayCore asks the provider once more and refuses a decision
-- the provider's answer contradicts.

ALTER TABLE operations_requests
    ADD COLUMN payment_id UUID,
    ADD COLUMN resolution VARCHAR(20);

ALTER TABLE operations_requests
    ADD CONSTRAINT fk_operations_requests_payment
        FOREIGN KEY (payment_id) REFERENCES payments (id);

ALTER TABLE operations_requests DROP CONSTRAINT ck_operations_requests_type;

ALTER TABLE operations_requests
    ADD CONSTRAINT ck_operations_requests_type
        CHECK (type IN ('ADJUSTMENT', 'REVERSAL', 'PAYMENT_RESOLUTION'));

ALTER TABLE operations_requests DROP CONSTRAINT ck_operations_requests_shape;

ALTER TABLE operations_requests
    ADD CONSTRAINT ck_operations_requests_shape
        CHECK (
            (type = 'ADJUSTMENT'
                AND account_id IS NOT NULL
                AND direction IN ('CREDIT', 'DEBIT')
                AND amount_minor > 0
                AND currency ~ '^[A-Z]{3}$'
                AND transaction_id IS NULL
                AND payment_id IS NULL
                AND resolution IS NULL)
            OR
            (type = 'REVERSAL'
                AND transaction_id IS NOT NULL
                AND account_id IS NULL
                AND direction IS NULL
                AND amount_minor IS NULL
                AND currency IS NULL
                AND payment_id IS NULL
                AND resolution IS NULL)
            OR
            (type = 'PAYMENT_RESOLUTION'
                AND payment_id IS NOT NULL
                AND resolution IN ('COMPLETE', 'REVERSE', 'CREDIT', 'REFUND', 'CLOSE')
                AND account_id IS NULL
                AND direction IS NULL
                AND amount_minor IS NULL
                AND currency IS NULL
                AND transaction_id IS NULL)
        );

-- Adjustments and reversals always post a ledger transaction when
-- approved. A resolution posts one only when money moves (CREDIT,
-- REVERSE); COMPLETE, REFUND and CLOSE change only the payment.
ALTER TABLE operations_requests DROP CONSTRAINT ck_operations_requests_result;

ALTER TABLE operations_requests
    ADD CONSTRAINT ck_operations_requests_result
        CHECK (
            (status = 'APPROVED' OR result_transaction_id IS NULL)
            AND (type = 'PAYMENT_RESOLUTION' OR (status = 'APPROVED') = (result_transaction_id IS NOT NULL))
        );

-- At most one open resolution per payment.
CREATE UNIQUE INDEX uk_operations_requests_pending_resolution
    ON operations_requests (payment_id)
    WHERE type = 'PAYMENT_RESOLUTION' AND status = 'PENDING';

-- A refunded top-up records the provider's refund reference.
ALTER TABLE payments
    ADD COLUMN refund_reference VARCHAR(100);

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_refund
        CHECK (refund_reference IS NULL OR (type = 'CARD_FUNDING' AND status = 'FAILED'));

ALTER TABLE payments
    ADD CONSTRAINT uk_payments_refund_reference UNIQUE (refund_reference);
