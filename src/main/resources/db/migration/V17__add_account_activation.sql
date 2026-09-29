-- Accounts now open PENDING and staff activate them once the customer is
-- eligible (verified KYC). Activation is audited like every other status
-- change, so the history accepts an ACTIVATED event. ACCOUNT_MANAGE, seeded
-- in V16, now also covers activation.

ALTER TABLE account_status_events
    DROP CONSTRAINT ck_account_status_events_type;

ALTER TABLE account_status_events
    ADD CONSTRAINT ck_account_status_events_type
        CHECK (event_type IN ('OPENED', 'ACTIVATED', 'FROZEN', 'UNFROZEN', 'CLOSED'));
