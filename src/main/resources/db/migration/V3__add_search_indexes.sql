-- Adds GIN indexes to improve the performance of full-text and fuzzy search queries on the JSONB payload column

-- Full-text search index covering all string values inside the JSON document
CREATE INDEX idx_run_payload_fts
    ON run USING GIN (jsonb_to_tsvector('english', payload, '"all"'));

-- Trigram index for fuzzy similarity searches across the entire JSON payload
-- (casts the whole JSONB to text; refine to specific fields if performance demands it)
CREATE INDEX idx_run_payload_trgm
    ON run USING GIN ((payload::text) gin_trgm_ops);