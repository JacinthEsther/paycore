-- Least privilege for the application's database user.
--
-- V25 and V26 make PostgreSQL refuse to rewrite ledger history and
-- payments, but a trigger cannot stop the tables' OWNER: an owner can
-- TRUNCATE, disable triggers or drop the tables. So in production two
-- database users are used:
--
--   owner (e.g. paycore_owner)  runs Flyway, owns every table
--                               spring.flyway.user / PAYCORE_DB_MIGRATION_USERNAME
--   paycore_app                 what the running API connects as
--                               spring.datasource.username / PAYCORE_DB_USERNAME
--
-- paycore_app gets exactly what the application does, nothing more:
--
--   SELECT, INSERT, UPDATE  on PayCore's tables
--   no UPDATE               on ledger_entries and payment_events (append-only)
--   DELETE                  only on customer_roles (revoking a role)
--   no TRUNCATE, no DDL, no ownership, read-only Flyway history
--
-- The role itself is created by a DBA (scripts/db/create-app-role.sql),
-- because creating roles needs CREATEROLE, which a migration user on a
-- managed database often lacks. This function grants to it; the Flyway
-- callback afterMigrate.sql calls it after every migration when the role
-- exists, so tables added by later migrations are covered automatically.
-- Where no paycore_app role exists (local development, tests) nothing
-- happens and everything keeps working as before.

CREATE FUNCTION paycore_apply_app_grants(app_role TEXT)
    RETURNS void
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
        RAISE EXCEPTION 'Role % does not exist', app_role;
    END IF;

    -- Start from nothing, so a privilege removed here is removed in the
    -- database too.
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA public FROM %I', app_role);
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA simulator FROM %I', app_role);
    EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM %I', app_role);

    EXECUTE format('GRANT USAGE ON SCHEMA public TO %I', app_role);
    EXECUTE format('GRANT USAGE ON SCHEMA simulator TO %I', app_role);

    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO %I', app_role);
    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', app_role);

    -- The simulated providers' records (V28): written as a provider would.
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA simulator TO %I', app_role);

    -- Append-only history: inserted, never changed.
    EXECUTE format('REVOKE UPDATE ON ledger_entries, payment_events FROM %I', app_role);

    -- The only rows the application ever deletes.
    EXECUTE format('GRANT DELETE ON customer_roles TO %I', app_role);

    -- Migrations are the owner's business; the API may only read history.
    EXECUTE format('REVOKE INSERT, UPDATE ON flyway_schema_history FROM %I', app_role);

    -- No creating objects in PayCore's schemas.
    EXECUTE format('REVOKE CREATE ON SCHEMA public FROM %I', app_role);
    EXECUTE format('REVOKE CREATE ON SCHEMA simulator FROM %I', app_role);
END;
$$;
