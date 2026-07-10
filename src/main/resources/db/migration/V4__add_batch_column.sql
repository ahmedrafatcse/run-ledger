ALTER TABLE run ADD COLUMN batch VARCHAR(255);
CREATE INDEX idx_run_batch ON run(batch);