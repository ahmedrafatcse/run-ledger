-- V18__saved_search_owner_constraint.sql
-- Tighten saved_search:
--   - owner_id becomes NOT NULL
--   - unique invariant moves from (name, batch) to (owner_id, name, batch)
--
-- The V16 migration added owner_id as nullable; the service refactor
-- (Slice 4.7) made every write set it. Now the constraint can land.
--
-- Existing rows are truncated: any pre-4.7 row has owner_id NULL and
-- there's no meaningful value to assign retroactively -- the historical
-- owner is not recorded anywhere. Saved searches are user configuration,
-- cheap to re-create, and no test or fixture depends on their persistence.

TRUNCATE TABLE saved_search;

-- Drop the old unowned unique index (from V8) and the interim non-unique
-- index (from V16). The new unique index supersedes both.
DROP INDEX IF EXISTS saved_search_name_batch_uq;
DROP INDEX IF EXISTS idx_saved_search_owner_name_batch;

-- Every write path now sets owner_id; enforce it.
ALTER TABLE saved_search ALTER COLUMN owner_id SET NOT NULL;

-- Composite uniqueness per owner. Uses an index rather than a table
-- constraint because COALESCE is an expression, which only a unique
-- index can enforce.
CREATE UNIQUE INDEX saved_search_owner_name_batch_uq
    ON saved_search (owner_id, name, COALESCE(batch, ''));