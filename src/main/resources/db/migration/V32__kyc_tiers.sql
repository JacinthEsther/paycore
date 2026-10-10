-- KYC tiers, modelled on the CBN's three-tier KYC: the more PayCore knows
-- about a customer, the more they can send in a day and hold.
--
--   0  not verified: the account cannot be active
--   1  identity verified with BVN or NIN and an ID document
--   2  both BVN and NIN verified
--   3  tier 2 plus a proof of address that compliance approved
--
-- The limits for each tier are configuration (paycore.limits.*), not data.

ALTER TABLE kyc_profiles
    ADD COLUMN tier SMALLINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_kyc_profiles_tier CHECK (tier BETWEEN 0 AND 3),
    ADD CONSTRAINT ck_kyc_profiles_verified_tier CHECK (status = 'VERIFIED' OR tier = 0);

-- Customers verified before tiers existed: 2 with both numbers, else 1.
UPDATE kyc_profiles p
SET tier = CASE
               WHEN EXISTS (SELECT 1 FROM kyc_verifications v
                            WHERE v.kyc_profile_id = p.id AND v.verification_type = 'BVN' AND v.result = 'PASSED')
                AND EXISTS (SELECT 1 FROM kyc_verifications v
                            WHERE v.kyc_profile_id = p.id AND v.verification_type = 'NIN' AND v.result = 'PASSED')
               THEN 2
               ELSE 1
           END
WHERE p.status = 'VERIFIED';

-- A verified customer asking for a higher tier with new evidence (a proof
-- of address for tier 3). Compliance decides; at most one open request.
CREATE TABLE kyc_tier_upgrades (
    id             UUID PRIMARY KEY,
    kyc_profile_id UUID          NOT NULL REFERENCES kyc_profiles (id) ON DELETE CASCADE,
    requested_tier SMALLINT      NOT NULL,
    document_id    UUID          NOT NULL REFERENCES kyc_documents (id) ON DELETE CASCADE,
    status         VARCHAR(20)   NOT NULL,
    reason         VARCHAR(1000),
    requested_at   TIMESTAMPTZ   NOT NULL,
    decided_by     UUID REFERENCES customers (id),
    decided_at     TIMESTAMPTZ,
    CONSTRAINT ck_kyc_tier_upgrades_tier CHECK (requested_tier BETWEEN 2 AND 3),
    CONSTRAINT ck_kyc_tier_upgrades_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_kyc_tier_upgrades_decision CHECK ((status = 'PENDING') = (decided_at IS NULL))
);

CREATE UNIQUE INDEX uk_kyc_tier_upgrades_one_open
    ON kyc_tier_upgrades (kyc_profile_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_kyc_tier_upgrades_status ON kyc_tier_upgrades (status, requested_at);
