-- V18__batch_schema_rls_and_grants.sql
-- Enable row-level security on batch_schema and tighten the grants.
--
-- Policies mirror run's: a researcher sees and writes their own team's
-- mappings, a supervisor reads their assigned teams' mappings (read-only
-- -- they never ingest), admin is out of scope as it is elsewhere.
--
-- Grants are narrowed from V12's table-level UPDATE/DELETE to
-- column-level UPDATE on key_mapping only. Nothing deletes mappings
-- anymore (Slice 4.5 replaced rebuild-on-call with merge-on-ingest), so
-- DELETE is revoked. Same reasoning as the Slice 3 column grants on run:
-- leave only the writes the service actually performs.
--
-- NULLIF(..., '')::uuid is required for the same reason it is on run:
-- custom placeholder GUCs return empty string, not NULL, after a
-- local-scope reset.

-- ---------------------------------------------------------------------------
-- Enable RLS
-- ---------------------------------------------------------------------------

ALTER TABLE batch_schema ENABLE ROW LEVEL SECURITY;
ALTER TABLE batch_schema FORCE ROW LEVEL SECURITY;

-- ---------------------------------------------------------------------------
-- Researcher policies
--
-- FOR ALL covers SELECT, INSERT, UPDATE. DELETE is covered by the policy
-- but blocked at the grant level, so the effective set is SELECT/INSERT/UPDATE.
-- ---------------------------------------------------------------------------

CREATE POLICY researcher_batch_own_team ON batch_schema
    FOR ALL TO runledger_researcher
    USING (
        team_id IN (
            SELECT team_id FROM app_users
            WHERE id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
        )
    )
    WITH CHECK (
        team_id IN (
            SELECT team_id FROM app_users
            WHERE id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
        )
    );

-- ---------------------------------------------------------------------------
-- Supervisor policy -- read-only, teams resolved via assignments
-- ---------------------------------------------------------------------------

CREATE POLICY supervisor_batch_assigned ON batch_schema
    FOR SELECT TO runledger_supervisor
                          USING (
                          team_id IN (
                          SELECT team_id FROM supervisor_team_assignments
                          WHERE supervisor_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
                          )
                          );

-- ---------------------------------------------------------------------------
-- Tighten grants
-- ---------------------------------------------------------------------------

REVOKE DELETE ON batch_schema FROM runledger_researcher;
REVOKE UPDATE ON batch_schema FROM runledger_researcher;
GRANT UPDATE (key_mapping) ON batch_schema TO runledger_researcher;

REVOKE DELETE ON batch_schema FROM runledger_admin;
REVOKE UPDATE ON batch_schema FROM runledger_admin;
GRANT UPDATE (key_mapping) ON batch_schema TO runledger_admin;

-- Sequence grant: the new id column needs nextval for the researcher and
-- admin roles, since both can INSERT.
GRANT USAGE, SELECT ON SEQUENCE batch_schema_id_seq TO runledger_researcher;
GRANT USAGE, SELECT ON SEQUENCE batch_schema_id_seq TO runledger_admin;