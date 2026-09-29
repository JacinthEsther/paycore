CREATE TABLE kyc_profiles (
    id UUID PRIMARY KEY,

    customer_id UUID NOT NULL,

    status VARCHAR(40) NOT NULL,

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_kyc_profile_customer
        FOREIGN KEY (customer_id)
        REFERENCES customers(id),

    CONSTRAINT uk_kyc_profile_customer
        UNIQUE (customer_id)
);

CREATE INDEX idx_kyc_profiles_status
    ON kyc_profiles(status);


CREATE TABLE kyc_documents (
    id UUID PRIMARY KEY,

    kyc_profile_id UUID NOT NULL,

    document_type VARCHAR(40) NOT NULL,

    storage_key VARCHAR(500) NOT NULL,

    document_number VARCHAR(255),

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_kyc_document_profile
        FOREIGN KEY (kyc_profile_id)
        REFERENCES kyc_profiles(id)
);

CREATE INDEX idx_kyc_documents_profile
    ON kyc_documents(kyc_profile_id);


CREATE TABLE kyc_verifications (
    id UUID PRIMARY KEY,

    kyc_profile_id UUID NOT NULL,

    verification_type VARCHAR(40) NOT NULL,

    provider VARCHAR(100) NOT NULL,

    provider_reference VARCHAR(255),

    result VARCHAR(30) NOT NULL,

    reason VARCHAR(1000),

    created_at TIMESTAMP WITH TIME ZONE NOT NULL,

    CONSTRAINT fk_kyc_verification_profile
        FOREIGN KEY (kyc_profile_id)
        REFERENCES kyc_profiles(id)
);

CREATE INDEX idx_kyc_verifications_profile
    ON kyc_verifications(kyc_profile_id);

CREATE INDEX idx_kyc_verifications_provider_reference
    ON kyc_verifications(provider_reference);


-- Compliance review: approve / reject / request more information.
INSERT INTO permissions (id, name)
VALUES (gen_random_uuid(), 'KYC_REVIEW');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.name = 'KYC_REVIEW'
WHERE r.name = 'ADMIN';
