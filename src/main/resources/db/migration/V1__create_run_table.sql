-- database setup, so we have a place to store the run info
-- V1 version 1, __ as a separator, name

CREATE TABLE run (
    id          BIGSERIAL PRIMARY KEY,
    payload     JSONB       NOT NULL, -- casually put, stores the JSON as a dict
    created_at  TIMESTAMP   NOT NULL DEFAULT NOW()
);