-- V330 1:1s and kudos (Track 3, task 20.2; spec culture/one-on-ones-kudos).
-- Conventions (design.md decision 4): UUID keys, created_at/updated_at/version on editable rows.
-- Every table names the person it is about (person_id) so the policy scope can filter on it.
-- DML grants to pulse_app come from V100's default privileges.

-- ---------------------------------------------------------------- 1:1s
-- One meeting between a person and their manager. Notes are shared: both participants edit them,
-- and only the two of them and HR (all.view) may read them.
CREATE TABLE one_on_ones (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id  uuid        NOT NULL REFERENCES people (id),
    manager_id uuid        NOT NULL REFERENCES users (id),       -- the other participant
    held_on    date        NOT NULL,                              -- past 1:1s, or the next one
    agenda     jsonb       NOT NULL DEFAULT '[]'::jsonb,         -- ["Workload", ...]
    notes      text        NOT NULL DEFAULT '' CHECK (length(notes) <= 4000),
    created_by uuid        REFERENCES users (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version    integer     NOT NULL DEFAULT 1
);
CREATE INDEX one_on_ones_person_idx ON one_on_ones (person_id, held_on DESC);
CREATE INDEX one_on_ones_manager_idx ON one_on_ones (manager_id, held_on DESC);

-- Action items agreed in a 1:1: owner, due, done. person_id repeats the 1:1's person for the scope.
CREATE TABLE one_on_one_actions (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    one_on_one_id uuid        NOT NULL REFERENCES one_on_ones (id) ON DELETE CASCADE,
    person_id     uuid        NOT NULL REFERENCES people (id),
    text          text        NOT NULL CHECK (length(text) BETWEEN 1 AND 300),
    owner_id      uuid        REFERENCES users (id),              -- null: not assigned yet
    due_on        date,
    done          boolean     NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       integer     NOT NULL DEFAULT 1
);
CREATE INDEX one_on_one_actions_meeting_idx ON one_on_one_actions (one_on_one_id, created_at);
CREATE INDEX one_on_one_actions_person_idx ON one_on_one_actions (person_id);

-- ---------------------------------------------------------------- kudos
-- Recognition for one culture value: evidence, never score. Kudos given in Pulse have
-- source 'pulse'; Slack /kudos messages arrive later through the ingest seam with their own
-- source and external_id (design.md decision 6).
CREATE TABLE kudos (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id     uuid        NOT NULL REFERENCES people (id),    -- who receives it
    from_user_id  uuid        REFERENCES users (id),              -- null: sender not matched (Slack)
    culture_value text        NOT NULL CHECK (culture_value IN ('Outcome Ownership', 'Moral & Ethics',
                              'Decision Making', 'Problem Solving', 'Risk Appetite', 'Adaptability',
                              'Agility in Execution', 'Collaboration', 'Resilience', 'Resourcefulness',
                              'Discipline')),
    message       text        NOT NULL CHECK (length(message) BETWEEN 1 AND 500),
    shared_to     text        NOT NULL DEFAULT 'none' CHECK (shared_to IN ('none', 'slack', 'teams')),
    source        text        NOT NULL DEFAULT 'pulse',
    external_id   text,
    synced_at     timestamptz,
    given_at      timestamptz NOT NULL DEFAULT now(),
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX kudos_source_external_uq ON kudos (source, external_id);
CREATE INDEX kudos_person_idx ON kudos (person_id, given_at DESC);
CREATE INDEX kudos_given_idx ON kudos (given_at DESC);
