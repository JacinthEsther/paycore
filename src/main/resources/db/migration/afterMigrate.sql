-- Flyway callback: runs after every migrate.
--
-- Re-applies the application role's privileges (V29) so tables created by
-- any later migration get them too. Does nothing before V29 has run or
-- where no paycore_app role exists (local development, tests).
DO $$
BEGIN
    IF to_regprocedure('paycore_apply_app_grants(text)') IS NOT NULL
       AND EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'paycore_app')
    THEN
        PERFORM paycore_apply_app_grants('paycore_app');
    END IF;
END;
$$;
