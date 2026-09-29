CREATE TABLE identities (
                            id UUID PRIMARY KEY,
                            customer_id UUID NOT NULL,
                            provider VARCHAR(30) NOT NULL,
                            provider_subject VARCHAR(255) NOT NULL,
                            password_hash VARCHAR(255),
                            enabled BOOLEAN NOT NULL,
                            created_at TIMESTAMP WITH TIME ZONE NOT NULL,
                            updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

                            CONSTRAINT fk_identity_customer
                                FOREIGN KEY (customer_id)
                                    REFERENCES customers(id),

                            CONSTRAINT uk_identity_provider_subject
                                UNIQUE (provider, provider_subject)
);