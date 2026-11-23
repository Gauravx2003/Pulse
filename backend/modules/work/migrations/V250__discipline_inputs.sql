-- V250 Discipline inputs (Track 2, task 10.2): what the work.disciplineInputs port gives core.discipline
-- (spec culture/culture-values "Discipline rules") besides the live data it computes itself (stale
-- tickets, PR standard). In v1 both tables are seeded from the prototype (discMetrics' DISC and
-- discDays); a live Jira connector would derive them from the changelog and worklogs.
-- Grants: V100 set default privileges, so pulse_app gets DML on these tables automatically.

-- Rules c-g per person: re-planning, status accuracy, time logging, early blockers, process adherence.
CREATE TABLE discipline_inputs (
    person_id        uuid        PRIMARY KEY REFERENCES people (id) ON DELETE CASCADE,
    replan_proactive integer     NOT NULL DEFAULT 0 CHECK (replan_proactive >= 0),  -- due-date moves before the date
    replan_late      integer     NOT NULL DEFAULT 0 CHECK (replan_late >= 0),       -- moves after it had passed
    status_wrong     integer     NOT NULL DEFAULT 0 CHECK (status_wrong >= 0),
    status_checked   integer     NOT NULL DEFAULT 0 CHECK (status_checked >= status_wrong),
    log_same_pct     integer     NOT NULL CHECK (log_same_pct BETWEEN 0 AND 100),   -- worklogs entered the same day
    bulk_entries     integer     NOT NULL DEFAULT 0 CHECK (bulk_entries >= 0),
    block_early      integer     NOT NULL DEFAULT 0 CHECK (block_early >= 0),       -- blockers flagged within a day
    block_raised     integer     NOT NULL DEFAULT 0 CHECK (block_raised >= block_early),
    dod_pct          integer     NOT NULL CHECK (dod_pct BETWEEN 0 AND 100),
    review_pct       integer     NOT NULL CHECK (review_pct BETWEEN 0 AND 100),     -- used when there are no PRs
    standup_pct      integer     NOT NULL CHECK (standup_pct BETWEEN 0 AND 100),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now()
);

-- Rule a: whether the person made a meaningful update on their active tickets each working day.
CREATE TABLE daily_updates (
    person_id uuid    NOT NULL REFERENCES people (id) ON DELETE CASCADE,
    day       date    NOT NULL,
    updated   boolean NOT NULL,
    PRIMARY KEY (person_id, day)
);
