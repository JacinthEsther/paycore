-- The most recent compliance decision on a KYC profile. review_reason is
-- shown to the customer (why it was rejected or what else is needed);
-- reviewed_by records which reviewer made the decision.
ALTER TABLE kyc_profiles
    ADD COLUMN review_reason VARCHAR(1000),
    ADD COLUMN reviewed_by UUID,
    ADD COLUMN reviewed_at TIMESTAMP WITH TIME ZONE;

ALTER TABLE kyc_profiles
    ADD CONSTRAINT fk_kyc_profile_reviewed_by
        FOREIGN KEY (reviewed_by)
        REFERENCES customers(id);
