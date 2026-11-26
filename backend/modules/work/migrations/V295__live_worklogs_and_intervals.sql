-- V295 live Jira worklogs and work intervals (Track 2, task 1.1, change jira-derived-hours, design.md
-- decisions 1, 2 and 7). Additive only: one nullable column, one table and two read views; no Phase 1
-- column, constraint or row changes. Seed rows keep their stored person_id; the seed has no intervals.

-- Written by the live connector through IngestService: the raw tool account of the worklog author (Jira
-- Server username). The person is matched at read time through external_accounts (V190), as for
-- issues.assignee_account_id (V290), so linking an account later counts that person's earlier hours from
-- the next read, with no re-sync.
ALTER TABLE worklogs ADD COLUMN account_id text;

-- One row per issue and kind, written by the live connector from the configured Start/End datetime fields
-- of an issue without Jira time tracking (external_id '<key>|dev' or '<key>|qa'). The upsert replaces the
-- row on every sync, so a moved value never leaves stale hours behind. A cleared start, or an issue that
-- gained time tracking, is written with starts_at null and counts nothing: the "delete" without a delete.
-- The calc/work loaders split the rows into hours per person and day (working-day window, dayHours,
-- holidays, leave and visit days). An end before the start is stored as written and counts nothing.
--   end_source  field       ends_at is the End field
--               resolution  no End field; ends_at is the issue's resolution time
--               open        no End field and not resolved; ends_at is null and the loader counts up to now
--   account_id  the Jira account credited with the interval (assignee at the start), resolved to a person
--               at read time like every live account
CREATE TABLE issue_work_intervals (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    issue_id     uuid        NOT NULL REFERENCES issues (id) ON DELETE CASCADE,
    kind         text        NOT NULL CHECK (kind IN ('dev', 'qa')),
    starts_at    timestamptz,
    ends_at      timestamptz,
    end_source   text        CHECK (end_source IN ('field', 'resolution', 'open')),
    account_id   text,
    source       text        NOT NULL DEFAULT 'seed',
    external_id  text,
    synced_at    timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    version      integer     NOT NULL DEFAULT 1
);
CREATE UNIQUE INDEX issue_work_intervals_source_external_uq ON issue_work_intervals (source, external_id);
CREATE INDEX issue_work_intervals_issue_idx ON issue_work_intervals (issue_id);
-- Period reads: intervals that start before the period ends (rows with no start are never read).
CREATE INDEX issue_work_intervals_starts_idx ON issue_work_intervals (starts_at) WHERE starts_at IS NOT NULL;

-- Worklogs with their person resolved. A row without an account (every seed row) keeps its stored
-- person_id. A live row takes the person linked to its account in external_accounts, where the tool is the
-- row's source; an unmatched or bot account gives null, so the hours count for nobody until the Admin links
-- the account, and then from the next read with no re-sync.
-- The calc/work worklog loaders (utilization, daily load, Effort) read this view instead of the table.
CREATE VIEW worklogs_with_person AS
SELECT w.id,
       CASE WHEN w.account_id IS NULL THEN w.person_id
            WHEN a.is_bot THEN NULL
            ELSE a.person_id END AS person_id,
       w.issue_id, w.work_date, w.hours, w.logged_at,
       w.source, w.external_id, w.synced_at, w.created_at, w.updated_at, w.version,
       w.account_id
FROM worklogs w
LEFT JOIN external_accounts a ON a.tool = w.source AND a.tool_account_id = w.account_id;

-- Work intervals with their person resolved the same way. Every interval is live, so a row without an
-- account, or with an unmatched or bot account, counts for nobody.
CREATE VIEW issue_work_intervals_with_person AS
SELECT iv.id, iv.issue_id, iv.kind, iv.starts_at, iv.ends_at, iv.end_source,
       CASE WHEN a.is_bot THEN NULL
            ELSE a.person_id END AS person_id,
       iv.source, iv.external_id, iv.synced_at, iv.created_at, iv.updated_at, iv.version,
       iv.account_id
FROM issue_work_intervals iv
LEFT JOIN external_accounts a ON a.tool = iv.source AND a.tool_account_id = iv.account_id;
-- The app role reads both views and writes the table through V100's default privileges, as with
-- issues_with_assignee (V290).
