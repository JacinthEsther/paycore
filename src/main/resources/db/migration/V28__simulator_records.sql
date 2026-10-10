-- The simulated providers' own records.
--
-- A real bank rail and card processor keep their own database of every
-- session and charge; that is what a status query reads and what their
-- settlement reports are built from. The simulators used to keep theirs
-- in memory, so a restart forgot them and an interrupted demo payment
-- could settle differently afterwards. They now keep them here.
--
-- They live in their own schema because they are not PayCore's data:
-- they stand in for systems PayCore does not own. Nothing in PayCore
-- reads them except the simulators (and, later, reconciliation, as the
-- provider's side of the comparison). No foreign keys to PayCore's tables,
-- on purpose: a provider knows nothing about PayCore's ids.

CREATE SCHEMA IF NOT EXISTS simulator;

-- Every payment the simulated rail carried, either way.
CREATE TABLE simulator.rail_sessions (
    session_id VARCHAR(40) PRIMARY KEY,

    -- OUTBOUND: PayCore paid another bank. INBOUND: another bank (Test
    -- Bank) paid a PayCore account.
    direction VARCHAR(10) NOT NULL,

    bank_code VARCHAR(10) NOT NULL,
    account_number VARCHAR(20) NOT NULL,
    account_name VARCHAR(200),

    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    narration VARCHAR(500),

    -- SUCCESSFUL: paid. FAILED: rejected, nobody paid. RETURNED: inbound
    -- money PayCore refused, sent back to the sender.
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(500),

    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_rail_sessions_direction CHECK (direction IN ('OUTBOUND', 'INBOUND')),
    CONSTRAINT ck_rail_sessions_status CHECK (status IN ('SUCCESSFUL', 'FAILED', 'RETURNED')),
    CONSTRAINT ck_rail_sessions_amount CHECK (amount_minor > 0)
);

CREATE INDEX idx_rail_sessions_created ON simulator.rail_sessions (created_at);

-- Every charge the simulated card processor took or declined.
CREATE TABLE simulator.card_payments (
    -- PayCore's reference for the charge; the processor deduplicates by it.
    merchant_reference VARCHAR(100) PRIMARY KEY,

    provider_reference VARCHAR(100) NOT NULL,

    amount_minor BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,

    -- CONFIRMED: charged. DECLINED: refused, nothing taken. REFUNDED:
    -- charged, then given back to the card.
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(500),

    refund_reference VARCHAR(100),

    created_at TIMESTAMPTZ NOT NULL,
    refunded_at TIMESTAMPTZ,

    CONSTRAINT uk_card_payments_provider_reference UNIQUE (provider_reference),
    CONSTRAINT uk_card_payments_refund_reference UNIQUE (refund_reference),
    CONSTRAINT ck_card_payments_status CHECK (status IN ('CONFIRMED', 'DECLINED', 'REFUNDED')),
    CONSTRAINT ck_card_payments_amount CHECK (amount_minor > 0),
    CONSTRAINT ck_card_payments_refund
        CHECK ((status = 'REFUNDED') = (refund_reference IS NOT NULL AND refunded_at IS NOT NULL))
);

CREATE INDEX idx_card_payments_created ON simulator.card_payments (created_at);
