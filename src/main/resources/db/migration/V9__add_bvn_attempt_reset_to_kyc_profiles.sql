-- An admin can reset a customer's BVN retry limit. Failed attempts are
-- kept as audit history; failures before bvn_attempts_reset_at simply
-- stop counting toward the limit. bvn_attempts_reset_by records which
-- admin performed the most recent reset.
ALTER TABLE kyc_profiles
    ADD COLUMN bvn_attempts_reset_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN bvn_attempts_reset_by UUID;

ALTER TABLE kyc_profiles
    ADD CONSTRAINT fk_kyc_profile_bvn_attempts_reset_by
        FOREIGN KEY (bvn_attempts_reset_by)
        REFERENCES customers(id);
