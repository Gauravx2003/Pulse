-- V150 scheduled job runs (Track 1, task 5.5, spec platform/jobs-scheduler "Run records and failure visibility").
-- One row per run of a registered job: scheduled tick or manual trigger by an Admin.

CREATE TABLE job_runs (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    job             text        NOT NULL,                         -- JobSpec name, e.g. platform.retention-purge
    trigger         text        NOT NULL CHECK (trigger IN ('schedule', 'manual')),
    triggered_by    uuid        REFERENCES users (id) ON DELETE SET NULL, -- null: the scheduler
    started_at      timestamptz NOT NULL DEFAULT now(),
    ended_at        timestamptz,
    outcome         text        NOT NULL CHECK (outcome IN ('running', 'succeeded', 'failed', 'skipped')),
    attempts        integer     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,                                  -- set while a failed attempt waits for its retry
    counts          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    error           text                                          -- never a secret; cut to 300 characters
);

-- "No overlapping runs": at most one running row per job; a second insert fails and the run is skipped.
CREATE UNIQUE INDEX job_runs_one_running ON job_runs (job) WHERE outcome = 'running';

CREATE INDEX job_runs_job_started_idx ON job_runs (job, started_at DESC);
