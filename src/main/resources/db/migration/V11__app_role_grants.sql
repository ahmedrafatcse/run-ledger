-- V11__app_role_grants.sql
-- Grant the non-owner application role only the privileges it needs.
-- The role itself is created by init scripts (dev Docker and Testcontainers)
-- so that its password never lives in a migration.

GRANT USAGE ON SCHEMA public TO runledger_app;

-- run: content is write-once; only the "latest" pointer can move.
-- This is what makes the demo claim true: the app cannot modify a submitted JSON.
GRANT SELECT, INSERT ON run TO runledger_app;
GRANT UPDATE (latest) ON run TO runledger_app;

-- saved_search: full CRUD for users managing their own searches
GRANT SELECT, INSERT, UPDATE, DELETE ON saved_search TO runledger_app;

-- batch_schema: full CRUD for schema discovery
GRANT SELECT, INSERT, UPDATE, DELETE ON batch_schema TO runledger_app;

-- RBAC tables: read-only from the app's perspective for now
GRANT SELECT ON teams TO runledger_app;
GRANT SELECT ON app_users TO runledger_app;
GRANT SELECT ON supervisor_team_assignments TO runledger_app;

-- Sequences for auto-generated IDs
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO runledger_app;