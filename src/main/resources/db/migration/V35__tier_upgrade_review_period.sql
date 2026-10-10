ALTER TABLE kyc_tier_upgrades
    ADD COLUMN review_due_at TIMESTAMPTZ,
    ADD COLUMN flags VARCHAR(1000),
    ADD COLUMN decided_automatically BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE kyc_tier_upgrades SET review_due_at = requested_at + INTERVAL '3 days';

ALTER TABLE kyc_tier_upgrades
    ALTER COLUMN review_due_at SET NOT NULL,
    ADD CONSTRAINT ck_kyc_tier_upgrades_automatic CHECK (
        NOT decided_automatically OR (status = 'APPROVED' AND decided_by IS NULL AND flags IS NULL)
    );

CREATE INDEX idx_kyc_tier_upgrades_due
    ON kyc_tier_upgrades (review_due_at)
    WHERE status = 'PENDING';
