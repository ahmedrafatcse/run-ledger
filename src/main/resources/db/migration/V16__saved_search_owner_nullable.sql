-- V16__saved_search_owner_nullable.sql
-- Phase 1 of making saved_search owner-scoped.
--
-- Today the unique index is on (name, COALESCE(batch, '')) alone, with no
-- owner dimension. Alice cannot save a search named "baseline" if Bob
-- already has one, and the constraint itself leaks Bob's name's existence
-- -- the same class of existence leak that the 404-on-invisible-runs
-- policy avoids at the page layer.
--
-- This migration adds owner_id as NULLABLE and an index for the eventual
-- (owner_id, name, batch) lookups. The constraint change (drop the old
-- unique index, add a composite one keyed on owner) and RLS policies land
-- after the service code sets owner_id on every write. Until then:
--   - the column is unused
--   - the old unique index remains, so cross-user name collisions are still
--     prevented (the current behavior, unchanged)
--   - no behavior changes
--
-- This mirrors the phased approach used for batch_schema.team_id (V15).

ALTER TABLE saved_search
    ADD COLUMN owner_id UUID REFERENCES app_users(id);

-- Index for the owner-scoped lookups the service will do once owner_id is
-- populated. Non-unique for now; the unique constraint comes in a later
-- migration once existing rows have owners.
CREATE INDEX idx_saved_search_owner_name_batch
    ON saved_search (owner_id, name, COALESCE(batch, ''));