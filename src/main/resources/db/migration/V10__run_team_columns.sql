-- V10__run_team_columns.sql
-- Add team ownership and submitter columns to the run table.
-- Both are nullable for now; a later migration will backfill and
-- then make them NOT NULL once the application populates them.

ALTER TABLE run
    ADD COLUMN team_id UUID REFERENCES teams(id),
    ADD COLUMN uploaded_by UUID REFERENCES app_users(id);

-- Indexes for the RLS policies that will be introduced in Slice 6.
-- Researchers will filter by team_id; supervisors will join on it.
CREATE INDEX idx_run_team_id ON run(team_id);

-- Index for "who submitted this run" lookups, which the supervisor
-- portal will need.
CREATE INDEX idx_run_uploaded_by ON run(uploaded_by);