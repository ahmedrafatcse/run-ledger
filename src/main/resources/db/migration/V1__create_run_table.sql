CREATE TABLE run (
    id          BIGSERIAL PRIMARY KEY,
    payload     JSONB       NOT NULL,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);