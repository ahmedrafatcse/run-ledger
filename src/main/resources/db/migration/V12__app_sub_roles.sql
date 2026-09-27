-- V12__app_sub_roles.sql
-- Create the three application sub-roles that runledger_app switches into
-- via SET LOCAL ROLE per request. RLS policies in Slice 6 target these roles.
--
-- runledger_app remains the login role; it never queries secured tables as
-- itself. Every secured transaction starts with SET LOCAL ROLE <sub-role>,
-- which is scoped to that transaction only.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'runledger_researcher') THEN
CREATE ROLE runledger_researcher NOLOGIN;
END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'runledger_supervisor') THEN
CREATE ROLE runledger_supervisor NOLOGIN;
END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'runledger_admin') THEN
CREATE ROLE runledger_admin NOLOGIN;
END IF;
END
$$;

-- Allow runledger_app to SET LOCAL ROLE into each sub-role
GRANT runledger_researcher TO runledger_app;
GRANT runledger_supervisor TO runledger_app;
GRANT runledger_admin      TO runledger_app;

-- Researcher: full read/write on run content (with latest-only update),
-- plus normal access to the operational tables.
GRANT SELECT, INSERT ON run TO runledger_researcher;
GRANT UPDATE (latest) ON run TO runledger_researcher;
GRANT SELECT ON app_users, teams TO runledger_researcher;
GRANT SELECT, INSERT, UPDATE, DELETE ON batch_schema TO runledger_researcher;
GRANT SELECT, INSERT, UPDATE, DELETE ON saved_search TO runledger_researcher;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO runledger_researcher;

-- Supervisor: read-only on run and operational tables.
GRANT SELECT ON run TO runledger_supervisor;
GRANT SELECT ON app_users, teams, supervisor_team_assignments TO runledger_supervisor;
GRANT SELECT ON batch_schema TO runledger_supervisor;
GRANT SELECT ON saved_search TO runledger_supervisor;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO runledger_supervisor;

-- Admin: read/write on run, full access to operational tables.
GRANT SELECT, INSERT ON run TO runledger_admin;
GRANT UPDATE (latest) ON run TO runledger_admin;
GRANT SELECT ON app_users, teams, supervisor_team_assignments TO runledger_admin;
GRANT SELECT, INSERT, UPDATE, DELETE ON batch_schema TO runledger_admin;
GRANT SELECT, INSERT, UPDATE, DELETE ON saved_search TO runledger_admin;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO runledger_admin;