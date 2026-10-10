-- Device binding and the transaction PIN: what a customer must have, and
-- know, before money leaves their account.
--
-- customer_devices   the browsers and phones a customer signs in from. The
--                    client's device id is never stored, only its SHA-256.
--                    PENDING until confirmed with an emailed code (the first
--                    device a customer ever uses is trusted at once).
-- login_sessions     now name the device they were opened on.
-- one_time_codes     6-digit codes sent by email: confirming a device,
--                    resetting a PIN. Hashed, short-lived, few attempts.
-- transaction_pins   one BCrypt-hashed 4-digit PIN per customer, locked
--                    after too many wrong guesses.

CREATE TABLE customer_devices (
    id                UUID PRIMARY KEY,
    customer_id       UUID         NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    device_hash       VARCHAR(64)  NOT NULL,
    label             VARCHAR(200),
    status            VARCHAR(20)  NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL,
    last_seen_at      TIMESTAMPTZ  NOT NULL,
    trusted_at        TIMESTAMPTZ,
    cooling_off_until TIMESTAMPTZ,
    revoked_at        TIMESTAMPTZ,
    CONSTRAINT uk_customer_devices_hash UNIQUE (customer_id, device_hash),
    CONSTRAINT ck_customer_devices_status CHECK (status IN ('PENDING', 'TRUSTED', 'REVOKED')),
    CONSTRAINT ck_customer_devices_trusted CHECK (status <> 'TRUSTED' OR trusted_at IS NOT NULL)
);

CREATE INDEX idx_customer_devices_cooling_off
    ON customer_devices (customer_id, cooling_off_until)
    WHERE cooling_off_until IS NOT NULL;

ALTER TABLE login_sessions
    ADD COLUMN device_id UUID REFERENCES customer_devices (id) ON DELETE SET NULL;

CREATE INDEX idx_login_sessions_device ON login_sessions (device_id);

CREATE TABLE one_time_codes (
    id          UUID PRIMARY KEY,
    customer_id UUID        NOT NULL REFERENCES customers (id) ON DELETE CASCADE,
    purpose     VARCHAR(30) NOT NULL,
    subject_id  UUID,
    code_hash   VARCHAR(64) NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    CONSTRAINT ck_one_time_codes_purpose CHECK (purpose IN ('DEVICE_BINDING', 'PIN_RESET')),
    CONSTRAINT ck_one_time_codes_attempts CHECK (attempts >= 0)
);

CREATE INDEX idx_one_time_codes_customer ON one_time_codes (customer_id, purpose, created_at);

CREATE TABLE transaction_pins (
    customer_id     UUID PRIMARY KEY REFERENCES customers (id) ON DELETE CASCADE,
    pin_hash        VARCHAR(100) NOT NULL,
    failed_attempts INT          NOT NULL DEFAULT 0,
    locked_at       TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_transaction_pins_attempts CHECK (failed_attempts >= 0)
);
