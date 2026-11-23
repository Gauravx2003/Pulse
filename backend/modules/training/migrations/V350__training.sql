-- V350 training and mentoring (Track 3, task 21.1; spec growth/training-mentoring).
-- Plans are versioned: an edit adds a version and existing assignments keep theirs; deleting a plan
-- hides it from the library and leaves its assignments running. The training rating is computed by
-- calc (TrainingRating through CalcService), never stored.
-- DML grants to pulse_app come from V100's default privileges.

-- ---------------------------------------------------------------- plan library
CREATE TABLE training_plans (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    current_version integer     NOT NULL DEFAULT 1,
    deleted_at      timestamptz,                                   -- hidden from the library; assignments keep running
    created_by      uuid        REFERENCES users (id),
    source          text        NOT NULL DEFAULT 'pulse',
    external_id     text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    version         integer     NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX training_plans_source_external_uq ON training_plans (source, external_id);

-- Every saved state of a plan. modules: [{id, title, type, week, hours, link}]
CREATE TABLE training_plan_versions (
    plan_id         uuid        NOT NULL REFERENCES training_plans (id) ON DELETE CASCADE,
    plan_version    integer     NOT NULL,
    name            text        NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
    audience        text        NOT NULL DEFAULT '' CHECK (length(audience) <= 120),
    weeks           integer     NOT NULL CHECK (weeks BETWEEN 1 AND 52),
    description     text        NOT NULL DEFAULT '' CHECK (length(description) <= 1000),
    values_built    jsonb       NOT NULL DEFAULT '[]'::jsonb,       -- culture values the plan builds
    checkpoints     jsonb       NOT NULL DEFAULT '[]'::jsonb,       -- checkpoint days: 14, 30, 60, 90
    cadence         text        NOT NULL DEFAULT 'Weekly' CHECK (cadence IN ('Daily', 'Weekly', 'Monthly')),
    assessment_type text        NOT NULL DEFAULT 'Practical task' CHECK (length(assessment_type) BETWEEN 1 AND 60),
    pass_mark       integer     NOT NULL DEFAULT 70 CHECK (pass_mark BETWEEN 0 AND 100),
    modules         jsonb       NOT NULL DEFAULT '[]'::jsonb,
    created_by      uuid        REFERENCES users (id),
    created_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (plan_id, plan_version)
);

-- ---------------------------------------------------------------- assignments
-- One person on one plan version with a mentor. The stage (Not started, In training, Assessment,
-- Final review, Trainee sign-off, Completed / Not completed) follows from the dates and the parts
-- recorded below; outcome holds the manager's decision once taken.
--   assessment:      {date, type, score, pass, result: Pass|Fail, notes, by, byRole, at}
--   final_reviews:   {mentor: {rating, good, improve, by, at}, manager: {rating, good, improve, decision, reason, by, at}}
--   sign_off:        {agree: Agreed|Disagreed, comment, planRating, mentorRating, improve, at}
--   acknowledgements:{"<feedback item key>": {st: Agreed|Disagreed, comment, at, reply?}}
--   module_progress: {"<module id>": {at, note}}
--   history:         previous attempts after Extend: [{attempt, assessment, final_reviews, end_on}]
CREATE TABLE training_assignments (
    id                     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id              uuid        NOT NULL REFERENCES people (id),
    plan_id                uuid        NOT NULL,
    plan_version           integer     NOT NULL,
    mentor_id              uuid        REFERENCES users (id),
    meeting_frequency      text        NOT NULL DEFAULT 'Weekly' CHECK (length(meeting_frequency) <= 40),
    cadence                text        NOT NULL DEFAULT 'Weekly' CHECK (cadence IN ('Daily', 'Weekly', 'Monthly')),
    start_on               date        NOT NULL,
    end_on                 date,                                    -- null: start + plan weeks
    assessment_window_days integer     NOT NULL DEFAULT 3 CHECK (assessment_window_days BETWEEN 1 AND 30),
    attempt                integer     NOT NULL DEFAULT 1 CHECK (attempt >= 1),
    outcome                text        CHECK (outcome IN ('completed', 'not_completed')),
    assessment             jsonb,
    final_reviews          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    sign_off               jsonb,
    acknowledgements       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    module_progress        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    history                jsonb       NOT NULL DEFAULT '[]'::jsonb,
    assigned_by            uuid        REFERENCES users (id),
    assigned_on            date        NOT NULL DEFAULT current_date,
    source                 text        NOT NULL DEFAULT 'pulse',
    external_id            text,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    version                integer     NOT NULL DEFAULT 1,
    FOREIGN KEY (plan_id, plan_version) REFERENCES training_plan_versions (plan_id, plan_version),
    CHECK (end_on IS NULL OR end_on >= start_on)
);
CREATE UNIQUE INDEX training_assignments_source_external_uq ON training_assignments (source, external_id);
CREATE INDEX training_assignments_person_idx ON training_assignments (person_id);
CREATE INDEX training_assignments_mentor_idx ON training_assignments (mentor_id);
CREATE INDEX training_assignments_plan_idx ON training_assignments (plan_id);

-- ---------------------------------------------------------------- check-ins
-- One per cadence period. States: Due and Missed are derived; Submitted, Verified, Needs work stored.
CREATE TABLE training_checkins (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id uuid         NOT NULL REFERENCES training_assignments (id) ON DELETE CASCADE,
    period_start  date         NOT NULL,
    done          text         NOT NULL CHECK (length(done) BETWEEN 1 AND 2000),
    hours         numeric(5,1) NOT NULL CHECK (hours >= 0),
    confidence    integer      CHECK (confidence BETWEEN 1 AND 5),
    blockers      text         NOT NULL DEFAULT '' CHECK (length(blockers) <= 1000),
    next_plan     text         NOT NULL DEFAULT '' CHECK (length(next_plan) <= 1000),
    submitted_on  date         NOT NULL,
    status        text         NOT NULL DEFAULT 'Submitted' CHECK (status IN ('Submitted', 'Verified', 'Needs work')),
    reviewer_id   uuid         REFERENCES users (id),
    reviewer_role text         CHECK (reviewer_role IN ('Mentor', 'Manager')),
    comment       text         NOT NULL DEFAULT '' CHECK (length(comment) <= 1000),
    reviewed_on   date,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    version       integer      NOT NULL DEFAULT 1,
    UNIQUE (assignment_id, period_start),
    CHECK (status <> 'Needs work' OR length(comment) > 0)
);

-- ---------------------------------------------------------------- mentoring
CREATE TABLE mentoring_sessions (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id uuid        NOT NULL REFERENCES training_assignments (id) ON DELETE CASCADE,
    held_on       date        NOT NULL,
    duration_min  integer     NOT NULL CHECK (duration_min BETWEEN 5 AND 600),
    mode          text        NOT NULL CHECK (length(mode) BETWEEN 1 AND 40),
    topics        text        NOT NULL CHECK (length(topics) BETWEEN 1 AND 1000),
    next_steps    text        NOT NULL DEFAULT '' CHECK (length(next_steps) <= 1000),
    blocker       boolean     NOT NULL DEFAULT false,
    blocker_note  text        NOT NULL DEFAULT '' CHECK (length(blocker_note) <= 1000),
    logged_by     uuid        REFERENCES users (id),
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX mentoring_sessions_assignment_idx ON mentoring_sessions (assignment_id, held_on DESC);

-- "Ask for a session" requests from the trainee.
CREATE TABLE mentoring_requests (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id uuid        NOT NULL REFERENCES training_assignments (id) ON DELETE CASCADE,
    note          text        NOT NULL CHECK (length(note) BETWEEN 1 AND 500),
    requested_on  date        NOT NULL DEFAULT current_date,
    handled_on    date,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX mentoring_requests_assignment_idx ON mentoring_requests (assignment_id) WHERE handled_on IS NULL;
