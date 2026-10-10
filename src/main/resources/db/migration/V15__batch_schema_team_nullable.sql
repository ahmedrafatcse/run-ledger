-- V15__batch_schema_team_nullable.sql
-- Phase 1 of making batch_schema team-scoped.
--
-- The mapping is derived from run rows, which RLS filters per team. Without
-- a team dimension, two teams with a batch named the same share one mapping
-- row, and whichever team ingests last overwrites the other's view.
--
-- This migration adds team_id as NULLABLE. The composite key, RLS policies,
-- and NOT NULL constraint land in a later migration (V18) once the service
-- code sets team_id on every write. Until then:
--   - the column is unused
--   - existing code paths continue to work (writes leave it NULL)
--   - no behavior changes
--
-- Splitting the schema change from the constraint means the app stays
-- runnable through the refactor rather than requiring a big-bang landing.

ALTER TABLE batch_schema
    ADD COLUMN team_id UUID REFERENCES teams(id);

-- Index for the eventual (team_id, batch) lookups. Postgres won't use it
-- for reads yet since nothing queries by team_id, but it makes the later
-- composite-key migration fast on non-trivial tables.
CREATE INDEX idx_batch_schema_team_batch ON batch_schema (team_id, batch);