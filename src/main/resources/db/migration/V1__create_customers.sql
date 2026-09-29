CREATE TABLE customers (
                           id UUID PRIMARY KEY,

                           first_name VARCHAR(100) NOT NULL,
                           last_name VARCHAR(100) NOT NULL,

                           email VARCHAR(255) NOT NULL,
                           phone_number VARCHAR(20) NOT NULL,

                           password_hash VARCHAR(255) NOT NULL,

                           status VARCHAR(30) NOT NULL,

                           email_verified BOOLEAN NOT NULL DEFAULT FALSE,
                           phone_verified BOOLEAN NOT NULL DEFAULT FALSE,

                           last_login_at TIMESTAMPTZ,

                           created_at TIMESTAMPTZ NOT NULL,
                           updated_at TIMESTAMPTZ NOT NULL,

                           version BIGINT NOT NULL DEFAULT 0,

                           CONSTRAINT uk_customers_email
                               UNIQUE (email),

                           CONSTRAINT uk_customers_phone
                               UNIQUE (phone_number)
);