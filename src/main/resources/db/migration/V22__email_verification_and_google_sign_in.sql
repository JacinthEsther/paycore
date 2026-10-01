-- Email verification, and customers who sign in with Google.

-- Google does not share a phone number, so a customer created through
-- Google sign-in has none until they add it. The unique constraint still
-- applies to every number that is set (NULLs never collide). KYC cannot be
-- submitted without one.
ALTER TABLE customers
    ALTER COLUMN phone_number DROP NOT NULL;


-- One-time links that prove a customer owns their email address. Only a
-- SHA-256 hash of the token is stored, like session and refresh tokens, so
-- a database leak does not hand out working links.
CREATE TABLE email_verification_tokens (
    id UUID PRIMARY KEY,

    -- Tokens are worthless without their customer, so they go with it.
    customer_id UUID NOT NULL,

    -- The address the link was sent to. A link only verifies that address,
    -- so one sent before an email change cannot verify the new one.
    email VARCHAR(255) NOT NULL,

    token_hash VARCHAR(64) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL,

    expires_at TIMESTAMPTZ NOT NULL,

    -- Set when the link is used, or when a newer link replaces it.
    used_at TIMESTAMPTZ,

    CONSTRAINT fk_email_verification_tokens_customer
        FOREIGN KEY (customer_id)
        REFERENCES customers (id)
        ON DELETE CASCADE,

    CONSTRAINT uk_email_verification_tokens_hash
        UNIQUE (token_hash),

    CONSTRAINT ck_email_verification_tokens_expiry
        CHECK (expires_at > created_at)
);

-- Resend limits count a customer's recent links.
CREATE INDEX idx_email_verification_tokens_customer
    ON email_verification_tokens (customer_id, created_at DESC);
