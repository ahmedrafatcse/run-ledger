-- Stores the key → path mapping for a batch.
-- key_mapping: JSONB map where each key is a shorthand key name (e.g. "accuracy")
-- and the value is the shallowest dot‑separated path (e.g. "metrics.accuracy").
CREATE TABLE batch_schema (
                              batch       VARCHAR(255) PRIMARY KEY,
                              key_mapping JSONB        NOT NULL,
                              created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);