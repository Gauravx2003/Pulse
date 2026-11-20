-- V220 Road map (Track 2, task 15.1): milestones, promotion targets and the skills matrix.
--
-- Pulse-owned data, so no ingest columns. Visible only to the person, their manager and all.view
-- (spec growth/roadmap); every table is scoped by person_id. Promotion readiness criteria that come
-- from numbers (performance index, culture values, mentoring) are computed through CalcService and
-- the owning services in task 15.2, never stored here.
-- Grants: V100 default privileges give pulse_app DML on these tables.

-- Timeline agreed in 1:1s: the employee proposes, the manager confirms.
CREATE TABLE milestones (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id      uuid        NOT NULL REFERENCES people (id) ON DELETE CASCADE,
    sort_order     integer     NOT NULL,                     -- position on the timeline
    title          text        NOT NULL CHECK (btrim(title) <> ''),
    when_label     text        NOT NULL,                     -- "When": "Q1 2027", "Apr 2027", "Q3–Q4 2026"
    stage          text        NOT NULL DEFAULT 'next' CHECK (stage IN ('done', 'now', 'next')),
    progress_pct   smallint    CHECK (progress_pct BETWEEN 0 AND 100),
    note           text,                                     -- badge text: "On track", "Planned", "Target"
    culture_value  text,                                     -- value the milestone grows
    linked_issues  text[]      NOT NULL DEFAULT '{}',        -- e.g. {PLAT-2231, PLAT-2201}
    status         text        NOT NULL DEFAULT 'proposed' CHECK (status IN ('proposed', 'confirmed')),
    proposed_by    uuid        REFERENCES users (id),
    confirmed_by   uuid        REFERENCES users (id),
    confirmed_at   timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        integer     NOT NULL DEFAULT 1,
    CHECK (status = 'confirmed' OR (confirmed_by IS NULL AND confirmed_at IS NULL))
);
CREATE INDEX milestones_person_idx ON milestones (person_id, sort_order);
CREATE INDEX milestones_proposed_idx ON milestones (person_id) WHERE status = 'proposed';

-- The level a person is working towards. Met / Open criteria are evaluated in task 15.2;
-- cross_team_epic_key is the manager's record of the "led a cross-team epic" criterion.
CREATE TABLE promotion_targets (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id          uuid        NOT NULL UNIQUE REFERENCES people (id) ON DELETE CASCADE,
    target_level       text        NOT NULL CHECK (target_level IN ('L1', 'L2', 'L3', 'L4', 'L5')),
    target_title       text        NOT NULL,                 -- "Senior Engineer"
    target_on          date        NOT NULL,                 -- target month, shown as "Apr 2027"
    cross_team_epic_key text,                                -- null: criterion Open
    set_by             uuid        REFERENCES users (id),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            integer     NOT NULL DEFAULT 1
);

-- Skills rated by the manager against the level guide; gap = needed_level - current_level.
CREATE TABLE skills_matrix (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id     uuid        NOT NULL REFERENCES people (id) ON DELETE CASCADE,
    sort_order    integer     NOT NULL,
    skill         text        NOT NULL CHECK (btrim(skill) <> ''),
    current_level smallint    NOT NULL CHECK (current_level BETWEEN 0 AND 5),
    needed_level  smallint    NOT NULL CHECK (needed_level BETWEEN 1 AND 5),
    rated_by      uuid        REFERENCES users (id),
    rated_at      timestamptz NOT NULL DEFAULT now(),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    version       integer     NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX skills_matrix_person_skill_uq ON skills_matrix (person_id, lower(skill));
CREATE INDEX skills_matrix_skill_idx ON skills_matrix (lower(skill));   -- skills coverage and rebalance (9.1, 13.2)
