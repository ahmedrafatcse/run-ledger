-- V8: Saved searches – reusable search configurations
CREATE TABLE saved_search (
                              id          bigserial PRIMARY KEY,
                              name        text NOT NULL,
                              batch       text,
                              params_json jsonb NOT NULL,
                              created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX saved_search_name_batch_uq ON saved_search (name, COALESCE(batch, ''));