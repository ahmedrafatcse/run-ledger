-- Seed teams, users, and supervisor assignments. Idempotent.
--
-- Runs are NOT seeded here. They come from the API so payload_hash is
-- computed by the real ingestion path (CanonicalJsonService). Inserting
-- them directly in SQL would reintroduce the canonicalization divergence
-- that Pre.1 fixed.
--
-- Run as the owner role (runledger), not runledger_app. The app role
-- can't write to teams or app_users directly under RLS. These are
-- demo-setup credentials, not part of the app's security boundary.

INSERT INTO teams (id, name) VALUES
                                 ('11111111-1111-1111-1111-111111111111', 'Team A'),
                                 ('22222222-2222-2222-2222-222222222222', 'Team B')
    ON CONFLICT (id) DO NOTHING;

INSERT INTO app_users (id, email, display_name, app_role, team_id) VALUES
                                                                       ('aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa', 'alice@example.com', 'Alice', 'researcher', '11111111-1111-1111-1111-111111111111'),
                                                                       ('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb', 'bob@example.com',   'Bob',   'researcher', '22222222-2222-2222-2222-222222222222'),
                                                                       ('cccccccc-cccc-cccc-cccc-cccccccccccc', 'sup@example.com',   'Sup',   'supervisor', NULL)
    ON CONFLICT (id) DO NOTHING;

INSERT INTO supervisor_team_assignments (supervisor_id, team_id) VALUES
                                                                     ('cccccccc-cccc-cccc-cccc-cccccccccccc', '11111111-1111-1111-1111-111111111111'),
                                                                     ('cccccccc-cccc-cccc-cccc-cccccccccccc', '22222222-2222-2222-2222-222222222222')
    ON CONFLICT DO NOTHING;