CREATE TABLE login_sessions (
    id UUID PRIMARY KEY,

    customer_id UUID NOT NULL,

    session_token_hash VARCHAR(255) NOT NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,

    last_used_at TIMESTAMP WITH TIME ZONE,

    revoked_at TIMESTAMP WITH TIME ZONE,

    ip_address VARCHAR(45),

    user_agent VARCHAR(1000),

    CONSTRAINT fk_login_session_customer
        FOREIGN KEY (customer_id)
        REFERENCES customers(id),

    CONSTRAINT uk_login_session_token_hash
        UNIQUE (session_token_hash)
);

CREATE INDEX idx_login_session_customer
    ON login_sessions (customer_id);

CREATE INDEX idx_login_session_expires_at
    ON login_sessions (expires_at);
