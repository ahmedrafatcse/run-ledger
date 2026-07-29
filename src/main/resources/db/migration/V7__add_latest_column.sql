-- V7: Add latest column to filter search results to the newest version only
ALTER TABLE run ADD COLUMN latest boolean NOT NULL DEFAULT true;

-- Backfill: mark all rows except the latest per identity as false
WITH latest_versions AS (
    SELECT DISTINCT ON (batch, source_file, source_index)
    id
FROM run
ORDER BY batch, source_file, source_index, version DESC
    )
UPDATE run
SET latest = false
WHERE id NOT IN (SELECT id FROM latest_versions);

-- Partial index for performance on common filter
CREATE INDEX idx_run_latest ON run (latest) WHERE latest = true;