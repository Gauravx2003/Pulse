-- V340 360° feedback (Track 3, task 20.3; spec culture/review-360, reference spec 360° feedback).
-- Cycles, reviewer requests with the review form, and pseudonymous reviewer storage: a request names
-- its reviewer only through review_reviewer_keys.pseudonym. The mapping to the user is read by the
-- review service alone, never by reports, exports or logs (spec "Pseudonymous reviewer storage").
-- Results (group merge under the anonymity threshold, hidden strengths, blind spots, weighted score)
-- are computed in calc/people through CalcService, never stored here.
-- DML grants to pulse_app come from V100's default privileges.

CREATE TABLE review_cycles (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name                text        NOT NULL CHECK (length(name) BETWEEN 1 AND 120),
    quarter             text        NOT NULL CHECK (quarter ~ '^[0-9]{4}-Q[1-4]$'),
    due_on              date        NOT NULL,
    department          text,                                         -- participants; null: everyone
    internal_min        integer     NOT NULL DEFAULT 3,
    internal_max        integer     NOT NULL DEFAULT 5,
    cross_min           integer     NOT NULL DEFAULT 2,
    cross_max           integer     NOT NULL DEFAULT 4,
    anonymity_threshold integer     NOT NULL DEFAULT 3 CHECK (anonymity_threshold BETWEEN 2 AND 10),
    weights             jsonb       NOT NULL DEFAULT '{"manager": 40, "internal": 25, "cross": 15, "self": 20}'::jsonb,
    status              text        NOT NULL DEFAULT 'running' CHECK (status IN ('running', 'closed', 'published')),
    created_by          uuid        REFERENCES users (id),
    published_at        timestamptz,
    source              text        NOT NULL DEFAULT 'pulse',
    external_id         text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             integer     NOT NULL DEFAULT 1,
    CHECK (internal_min BETWEEN 0 AND internal_max),
    CHECK (cross_min BETWEEN 0 AND cross_max)
);
-- Only one cycle runs at a time (spec "Cycle").
CREATE UNIQUE INDEX review_cycles_one_running_uq ON review_cycles ((true)) WHERE status = 'running';
CREATE UNIQUE INDEX review_cycles_source_external_uq ON review_cycles (source, external_id);

-- One pseudonym per reviewer per cycle, so answers cannot be linked across cycles by the key.
CREATE TABLE review_reviewer_keys (
    pseudonym uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id  uuid NOT NULL REFERENCES review_cycles (id) ON DELETE CASCADE,
    user_id   uuid NOT NULL REFERENCES users (id),
    UNIQUE (cycle_id, user_id)
);

-- A request: one reviewer (by pseudonym) reviews one subject in one rater group, and their answers.
-- scores:   {"<value>": 1..5 or null for Not observed} over the 10 rated values (Discipline is not rated)
-- examples: {"<value>": "text"} (required for ratings 1, 2 and 5)
CREATE TABLE review_requests (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id     uuid        NOT NULL REFERENCES review_cycles (id) ON DELETE CASCADE,
    subject_id   uuid        NOT NULL REFERENCES people (id),
    reviewer_key uuid        NOT NULL REFERENCES review_reviewer_keys (pseudonym),
    rater_group  text        NOT NULL CHECK (rater_group IN ('self', 'manager', 'internal', 'cross')),
    status       text        NOT NULL DEFAULT 'not_started'
                             CHECK (status IN ('not_started', 'in_progress', 'submitted', 'declined')),
    scores       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    examples     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    keep_doing   text        NOT NULL DEFAULT '' CHECK (length(keep_doing) <= 2000),
    start_doing  text        NOT NULL DEFAULT '' CHECK (length(start_doing) <= 2000),
    assigned_by  uuid        REFERENCES users (id),
    submitted_at timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      integer     NOT NULL DEFAULT 1,
    UNIQUE (cycle_id, subject_id, reviewer_key),
    CHECK ((status = 'submitted') = (submitted_at IS NOT NULL))
);
CREATE INDEX review_requests_subject_idx ON review_requests (cycle_id, subject_id);
CREATE INDEX review_requests_reviewer_idx ON review_requests (reviewer_key);
