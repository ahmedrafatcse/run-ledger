-- V17__batch_schema_surrogate_and_constraints.sql
-- Final shape of batch_schema:
--   - surrogate id (BIGSERIAL) as primary key
--   - team_id NOT NULL
--   - composite uniqueness on (team_id, batch), enforced by a unique constraint
--
-- The V15 migration added team_id as nullable; the service refactor (Slice 4.5)
-- made every write set it. Now the constraint can be tightened.
--
-- Existing rows are truncated first: they have no team_id, and there is no
-- meaningful value to assign retroactively -- a mapping row's team is
-- whichever team ingested the batch, which was never recorded. Cleared
-- rows are rebuilt on the next ingest through mergeIntoMapping.

TRUNCATE TABLE batch_schema;

-- Drop the old primary key on batch alone
ALTER TABLE batch_schema DROP CONSTRAINT batch_schema_pkey;

-- Add the surrogate primary key
ALTER TABLE batch_schema ADD COLUMN id BIGSERIAL PRIMARY KEY;

-- Enforce NOT NULL now that every write path sets the column
ALTER TABLE batch_schema ALTER COLUMN team_id SET NOT NULL;

-- Composite uniqueness -- the invariant the service depends on
ALTER TABLE batch_schema
    ADD CONSTRAINT batch_schema_team_batch_uq UNIQUE (team_id, batch);

-- The V15 index is superseded by the unique constraint's index
DROP INDEX IF EXISTS idx_batch_schema_team_batch;