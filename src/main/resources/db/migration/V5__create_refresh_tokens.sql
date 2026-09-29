CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,

    customer_id UUID NOT NULL,

    session_id UUID NOT NULL,

    token_hash VARCHAR(64) NOT NULL,

    family_id UUID NOT NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,

    revoked_at TIMESTAMP WITH TIME ZONE,

    replaced_by UUID,

    CONSTRAINT fk_refresh_token_customer
        FOREIGN KEY (customer_id)
        REFERENCES customers(id),

    CONSTRAINT fk_refresh_token_session
        FOREIGN KEY (session_id)
        REFERENCES login_sessions(id),

    CONSTRAINT uk_refresh_token_hash
        UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_customer
    ON refresh_tokens(customer_id);

CREATE INDEX idx_refresh_tokens_family
    ON refresh_tokens(family_id);

CREATE INDEX idx_refresh_tokens_session
    ON refresh_tokens(session_id);
