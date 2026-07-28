-- V6: Add run identity and version columns
-- Closes the gate for idempotent re‑scan, watch mode, and tamper‑evidence.

-- 1. Add identity columns (nullable for now, backfilled below)
ALTER TABLE run
    ADD COLUMN source_file   text   NOT NULL DEFAULT '',
  ADD COLUMN source_index  integer NOT NULL DEFAULT 0,
  ADD COLUMN version       integer NOT NULL DEFAULT 1,
  ADD COLUMN payload_hash  text;

-- 2. Backfill source_file and source_index from the JSONB payload
--    This extracts the values that were injected by the CLI during ingestion.
UPDATE run
SET source_file  = payload ->> '_source.file',
    source_index = COALESCE((payload ->> '_source.index')::int, 0)
WHERE source_file = '';   -- only rows that still have the default

-- 3. Unique constraint to prevent duplicate versions for the same source identity
--    This also serves as a uniqueness guard against concurrent scan races.
CREATE UNIQUE INDEX run_identity_version_uq
    ON run (batch, source_file, source_index, version);

-- 4. Backfill payload_hash
--    PostgreSQL cannot reproduce Jackson's canonical JSON deterministically,
--    so the actual hash values must be computed by the application using the
--    same ObjectMapper configuration (ORDER_MAP_ENTRIES_BY_KEYS + SHA-256).
--    A separate startup component (PayloadHashBackfill) will run once and set
--    payload_hash for all rows where it is NULL. No further action is needed
--    from this migration.