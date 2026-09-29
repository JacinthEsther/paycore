-- Client network of each verification attempt, for per-IP rate limiting
-- of BVN checks and for audit. Stored normalized: IPv4 as-is, IPv6
-- truncated to its /64 prefix (e.g. "2001:db8:1:2::/64"). Rows written
-- before this migration have no value.
ALTER TABLE kyc_verifications
    ADD COLUMN ip_address VARCHAR(50);

CREATE INDEX idx_kyc_verifications_ip_created
    ON kyc_verifications(ip_address, created_at)
    WHERE verification_type = 'BVN';
