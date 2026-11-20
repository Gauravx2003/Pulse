-- V210 OKRs (Track 2, task 14.1): objectives, key results and check-ins.
--
-- OKRs are Pulse-owned (created in Pulse or imported by CSV), so they carry no ingest columns.
-- Quarters use the sortable key '2026-Q3', like culture_signals. Progress, status and the OKR
-- pillar are computed in calc/work through CalcService (spec growth/okrs), never stored.
-- Grants: V100 default privileges give pulse_app DML on these tables.

-- An objective belongs to one person (Individual) or one team (Team).
CREATE TABLE objectives (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    quarter       text        NOT NULL CHECK (quarter ~ '^[0-9]{4}-Q[1-4]$'),
    level         text        NOT NULL CHECK (level IN ('Individual', 'Team')),
    owner_id      uuid        REFERENCES people (id),       -- Individual objectives
    team          text,                                     -- Team objectives, e.g. "Platform"
    title         text        NOT NULL CHECK (btrim(title) <> ''),
    epic_key      text,                                     -- optional linked Jira epic
    culture_value text,                                     -- value the objective grows
    created_by    uuid        REFERENCES users (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       integer     NOT NULL DEFAULT 1,
    CHECK ((level = 'Individual' AND owner_id IS NOT NULL AND team IS NULL)
        OR (level = 'Team' AND team IS NOT NULL AND owner_id IS NULL))
);
-- CSV import matches an objective by quarter, owner (or team) and text (spec growth/okrs).
CREATE UNIQUE INDEX objectives_match_uq ON objectives (quarter, coalesce(owner_id::text, 'team:' || team), lower(title));
CREATE INDEX objectives_owner_idx ON objectives (owner_id, quarter);
CREATE INDEX objectives_team_idx ON objectives (team, quarter) WHERE level = 'Team';
CREATE INDEX objectives_quarter_idx ON objectives (quarter);

-- 1 to 5 key results per objective (position 1-5); a target may be lower than its start.
CREATE TABLE key_results (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    objective_id  uuid        NOT NULL REFERENCES objectives (id) ON DELETE CASCADE,
    position      smallint    NOT NULL CHECK (position BETWEEN 1 AND 5),
    description   text        NOT NULL CHECK (btrim(description) <> ''),
    start_value   numeric     NOT NULL,
    target_value  numeric     NOT NULL,
    current_value numeric     NOT NULL,
    unit          text        NOT NULL DEFAULT '',
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       integer     NOT NULL DEFAULT 1,
    CHECK (target_value <> start_value),
    UNIQUE (objective_id, position)
);

-- Every new current value: a manual check-in or a matching row of a CSV import (bulk check-in).
CREATE TABLE key_result_checkins (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    key_result_id  uuid        NOT NULL REFERENCES key_results (id) ON DELETE CASCADE,
    previous_value numeric     NOT NULL,
    new_value      numeric     NOT NULL,
    note           text        NOT NULL DEFAULT '' CHECK (char_length(note) <= 500),
    via            text        NOT NULL DEFAULT 'manual' CHECK (via IN ('manual', 'import')),
    checked_in_by  uuid        REFERENCES users (id),
    checked_in_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX key_result_checkins_kr_idx ON key_result_checkins (key_result_id, checked_in_at DESC);
