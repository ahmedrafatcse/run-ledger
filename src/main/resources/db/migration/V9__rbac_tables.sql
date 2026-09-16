-- V9__rbac_tables.sql
-- RBAC schema: teams, users, supervisor assignments.
-- No application code touches these tables yet.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE teams (
                       id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       name        TEXT NOT NULL UNIQUE,
                       created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_users (
                           id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           email         TEXT NOT NULL UNIQUE,
                           display_name  TEXT NOT NULL,
                           app_role      TEXT NOT NULL CHECK (app_role IN ('researcher', 'supervisor', 'admin')),
                           team_id       UUID REFERENCES teams(id),
                           created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
                           CONSTRAINT app_users_role_team_consistency CHECK (
                               (app_role = 'researcher' AND team_id IS NOT NULL) OR
                               (app_role IN ('supervisor', 'admin') AND team_id IS NULL)
                               )
);

CREATE TABLE supervisor_team_assignments (
                                             supervisor_id  UUID NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
                                             team_id        UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
                                             created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                                             PRIMARY KEY (supervisor_id, team_id)
);

CREATE INDEX idx_supervisor_team_assignments_team
    ON supervisor_team_assignments(team_id);