-- V360 appraisal (Track 3, task 22.1; spec appraisal/appraisal, reference spec section 12).
-- One cycle per appraisal year with its effective date (default 1 October). One proposal per person per
-- cycle: Draft -> Submitted -> Approved -> Released, or Submitted -> Sent back -> Draft.
-- Scores are not stored here: they come from core.appraisal (task 4.8) while Draft, and from the
-- calc_snapshots row frozen at submission afterwards (snapshot_id). CTC stays on people.ctc.
-- DML grants to pulse_app come from V100's default privileges.

CREATE TABLE appraisal_cycles (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    year         integer     NOT NULL UNIQUE CHECK (year BETWEEN 2000 AND 2100),
    effective_on date        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      integer     NOT NULL DEFAULT 1
);

CREATE TABLE appraisal_proposals (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    cycle_id         uuid         NOT NULL REFERENCES appraisal_cycles (id),
    person_id        uuid         NOT NULL REFERENCES people (id),
    pct              numeric(4,1) NOT NULL CHECK (pct >= 0 AND pct <= 50),   -- the rule cap is checked by the service
    reason           text         NOT NULL DEFAULT '' CHECK (length(reason) <= 1000),
    promotion        boolean      NOT NULL DEFAULT false,
    status           text         NOT NULL DEFAULT 'Draft'
                                  CHECK (status IN ('Draft', 'Submitted', 'Sent back', 'Approved', 'Released')),
    send_back_reason text         CHECK (length(send_back_reason) <= 1000),
    proposed_by      uuid         REFERENCES users (id),
    submitted_by     uuid         REFERENCES users (id),
    submitted_at     timestamptz,
    decided_by       uuid         REFERENCES users (id),
    decided_at       timestamptz,
    released_at      timestamptz,
    snapshot_id      uuid         REFERENCES calc_snapshots (id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    version          integer      NOT NULL DEFAULT 1,
    CONSTRAINT appraisal_proposals_person_uq UNIQUE (cycle_id, person_id)
);
CREATE INDEX appraisal_proposals_status_idx ON appraisal_proposals (cycle_id, status);
