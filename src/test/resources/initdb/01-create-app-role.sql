-- 01-create-app-role.sql
-- Runs once, on first Postgres container startup, before Flyway.
-- Creates the non-owner application role used at runtime.
-- The password is a development-only credential.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'runledger_app') THEN
CREATE ROLE runledger_app
    LOGIN
            NOSUPERUSER
            NOCREATEDB
            NOCREATEROLE
            PASSWORD 'runledger';
END IF;
END
$$;