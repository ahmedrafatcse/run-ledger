-- V14__add_canon_version.sql
-- Records which version of the canonicalization algorithm produced each
-- stored payload_hash. Without this, changing the algorithm again would
-- require wiping the run table - which immutability forbids.
--
-- Every existing row is stamped 'v1' by the DEFAULT. New rows set it
-- explicitly from CanonicalJsonService.CANON_VERSION. When the algorithm
-- changes to v2, the IntegrityStatus check can report rows as
-- "hash computed under v1, current algorithm is v2" instead of silently
-- claiming a mismatch.

ALTER TABLE run
    ADD COLUMN canon_version TEXT NOT NULL DEFAULT 'v1';