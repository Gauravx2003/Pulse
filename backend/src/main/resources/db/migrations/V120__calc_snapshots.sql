-- Task 4.9: calculation snapshots (spec platform/calculation-engine "Snapshots and rule versioning").
--
-- A snapshot keeps the inputs, the guarded result and the rule version of a calculation that must
-- stay as it was: a closed period, a submitted appraisal proposal, a published 360° cycle. A rule
-- change never touches it. It is replaced only by a new row (an Admin recalculation of a named
-- closed period with a written reason, or an appraisal send-back), which marks the old row
-- superseded; rows are never updated in place or deleted by the application.

CREATE TABLE calc_snapshots (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    kind          text        NOT NULL CHECK (kind IN ('period', 'appraisal', 'review_360')),
    subject_key   text        NOT NULL,                  -- "s:S42", "q:2026-Q3"; proposal id; cycle id
    rule_version  integer     NOT NULL REFERENCES rule_versions (version),
    inputs        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    result        jsonb       NOT NULL,
    reason        text,                                  -- why this row replaced the previous one
    created_at    timestamptz NOT NULL DEFAULT now(),
    created_by    uuid,
    superseded_at timestamptz,
    superseded_by uuid REFERENCES calc_snapshots (id) DEFERRABLE INITIALLY DEFERRED
);

-- One live snapshot per subject.
CREATE UNIQUE INDEX calc_snapshots_live_uq ON calc_snapshots (kind, subject_key) WHERE superseded_at IS NULL;
CREATE INDEX calc_snapshots_subject_idx ON calc_snapshots (kind, subject_key, created_at);

-- Frozen rows: the application may only mark a snapshot superseded.
REVOKE UPDATE, DELETE, TRUNCATE ON calc_snapshots FROM pulse_app;
GRANT UPDATE (superseded_at, superseded_by) ON calc_snapshots TO pulse_app;

-- A rule version is history: new versions are inserted, old ones never change.
REVOKE UPDATE, DELETE, TRUNCATE ON rule_versions FROM pulse_app;
