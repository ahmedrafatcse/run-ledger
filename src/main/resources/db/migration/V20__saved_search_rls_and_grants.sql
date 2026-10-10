-- V19__saved_search_rls_and_grants.sql
-- Enable row-level security on saved_search and scope every operation to
-- the current user's own rows.
--
-- The policy applies to researchers and supervisors alike: both have full
-- CRUD on their own saved searches, and RLS makes "their own" a database
-- invariant rather than a service-layer convention.
--
-- Admin gets no policy. Same reasoning as elsewhere in this milestone:
-- admin is out of scope for Minimum, and no policy means zero rows, which
-- is the correct fail-closed default.
--
-- NULLIF(..., '')::uuid is required for the same reason it is on run and
-- batch_schema: custom placeholder GUCs return empty string, not NULL,
-- after a local-scope reset.

ALTER TABLE saved_search ENABLE ROW LEVEL SECURITY;
ALTER TABLE saved_search FORCE ROW LEVEL SECURITY;

CREATE POLICY saved_search_own_rows ON saved_search
    FOR ALL TO runledger_researcher, runledger_supervisor
    USING (
        owner_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
    )
    WITH CHECK (
        owner_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid
    );