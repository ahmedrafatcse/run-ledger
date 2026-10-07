-- Demo tamper. Runs as the OWNER (a role an attacker would need to have
-- already compromised). Changes payload only, leaves payload_hash alone.
-- The mismatch is what the integrity badge detects on reload.
--
-- Targets alice-1.json specifically so the demo is deterministic and the
-- README can say "tamper alice-1.json" without ambiguity.
--
-- After running this, reload http://localhost:8081/runs/<id> as Alice and
-- the badge renders TAMPERED. To restore, run demo/reset.ps1.

UPDATE run
SET payload = jsonb_build_object('tampered', true, 'note', 'demo')
WHERE source_file = 'alice-1.json'
  AND team_id = '11111111-1111-1111-1111-111111111111';