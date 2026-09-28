-- V13__rls_policies.sql
-- Enable row-level security on the run table and define team-scoped policies.
--
-- The application (runledger_app) never queries run directly. Every request
-- goes through SecuredTransactionTemplate, which issues SET LOCAL ROLE into
-- one of the sub-roles (researcher, supervisor, admin) before the query runs.
-- RLS policies therefore target those sub-roles, not runledger_app itself.
--
-- FORCE ROW LEVEL SECURITY is required. Without it, the table owner
-- (runledger, and whatever Flyway connects as) bypasses every policy
-- silently, so tests and migrations running as the owner would appear to
-- work while the app would be filtered. FORCE makes the policies apply to
-- the owner too.
--
-- Decisions documented here (not implemented here):
--
--   runledger_admin: no policy. Admin sees zero rows under RLS. This is
--   deliberate for Slice 6 - admin access is not part of the demo. If
--   admin-over-everything becomes needed later, it gets its own migration
--   and its own reasoning, not a permissive policy added quietly.
--
--   batch_schema: no RLS. The getOrCreateMapping method deletes-then-
--   recreates on every call, which requires write access that supervisors
--   do not have. The access model for this table is being reconsidered in
--   Slice 6.6. Until that lands, batch_schema has no policy - tracked as
--   a known gap, not an oversight.

-- ---------------------------------------------------------------------------
-- Enable RLS
-- ---------------------------------------------------------------------------

ALTER TABLE run ENABLE ROW LEVEL SECURITY;
ALTER TABLE run FORCE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- Researcher policies
--
-- A researcher belongs to exactly one team (app_users.team_id).
-- current_setting('app.current_user_id', true) is set by
-- SecuredTransactionTemplate for the duration of the transaction.
--
-- NULLIF(..., '') is required: Postgres initializes custom placeholder GUCs
-- as empty string once they have been set at least once in the session.
-- ''::uuid throws; NULL::uuid is NULL, and NULL IN (...) is false, which is
-- the fail-closed behaviour we want when identity is somehow unset.
-- ---------------------------------------------------------------------------

CREATE POLICY researcher_select_own_team ON run
    FOR SELECT
                   TO runledger_researcher
                   USING (
                   team_id IN (
                   SELECT team_id FROM app_users
                   WHERE id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
                   )
                   );

CREATE POLICY researcher_insert_own_team ON run
    FOR INSERT
    TO runledger_researcher
    WITH CHECK (
        team_id IN (
            SELECT team_id FROM app_users
            WHERE id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
        )
        AND uploaded_by = NULLIF(current_setting('app.current_user_id', true), '')::uuid
    );

-- Required for the version-identity flip in RunIngestionService.ingest:
-- "UPDATE run SET latest = false WHERE id = ?" on the previous version.
-- Without this policy, RLS denies the UPDATE by default and every second
-- submission of the same file fails with a permission error.
--
-- WITH CHECK is not specified; Postgres uses USING for both the old and new
-- row when WITH CHECK is omitted. The new row's team_id cannot change because
-- the column-level grant limits UPDATE to the latest column only.
CREATE POLICY researcher_update_own_team ON run
    FOR UPDATE
                          TO runledger_researcher
                          USING (
                          team_id IN (
                          SELECT team_id FROM app_users
                          WHERE id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
                          )
                          );

-- ---------------------------------------------------------------------------
-- Supervisor policy
--
-- A supervisor is not assigned to a single team. Which teams they oversee
-- is resolved through supervisor_team_assignments. The policy looks up the
-- current user's assignments and permits rows whose team_id is among them.
--
-- Read-only: supervisors observe, they do not submit. No INSERT/UPDATE
-- policy for this role, so those operations are denied by default.
-- ---------------------------------------------------------------------------

CREATE POLICY supervisor_select_assigned_teams ON run
    FOR SELECT
                   TO runledger_supervisor
                   USING (
                   team_id IN (
                   SELECT team_id FROM supervisor_team_assignments
                   WHERE supervisor_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
                   )
                   );