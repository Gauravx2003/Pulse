-- V370 culture (Track 3, task 19.1; spec culture/culture-values "Discipline override").
-- A manager may override a person's Discipline score with a written reason; the latest override that is
-- not cleared wins (people.disciplineOverride, read by core.discipline). Nobody overrides their own.
-- DML grants to pulse_app come from V100's default privileges.

CREATE TABLE discipline_overrides (
    id         uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    person_id  uuid         NOT NULL REFERENCES people (id),
    score      numeric(3,2) NOT NULL CHECK (score >= 1 AND score <= 5),
    reason     text         NOT NULL CHECK (length(reason) BETWEEN 10 AND 1000),
    set_by     uuid         NOT NULL REFERENCES users (id),
    set_at     timestamptz  NOT NULL DEFAULT now(),
    cleared_at timestamptz,
    cleared_by uuid         REFERENCES users (id),
    version    integer      NOT NULL DEFAULT 1
);
CREATE INDEX discipline_overrides_person_idx ON discipline_overrides (person_id, set_at DESC) WHERE cleared_at IS NULL;
