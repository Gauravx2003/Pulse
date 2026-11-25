-- V290 live Jira issues (Track 2, task 25.1, change pulse-v2-live-connectors, design.md decisions 5 and 6).
-- Additive only: two nullable columns, one index and one read view; no Phase 1 column, constraint or
-- row changes. Seed rows keep their stored assignee_id; nothing here rewrites them.

-- Written by the live connector through IngestService (column names frozen on Day 1 for Track 3):
--   html_url             the issue in the tool, e.g. <baseUrl>/browse/PLAT-2231
--   assignee_account_id  the raw tool account of the assignee (Jira Server username), matched to a
--                        person at read time through external_accounts (V190)
ALTER TABLE issues ADD COLUMN html_url text;
ALTER TABLE issues ADD COLUMN assignee_account_id text;

-- Incremental reads and "last synced" per source.
CREATE INDEX issues_source_synced_idx ON issues (source, synced_at);

-- Issues with their person resolved. A row without an assignee account (every seed row) keeps its
-- stored assignee_id. A live row takes the person linked to its account in external_accounts, where
-- the tool is the row's source (the connector writes its tile id, 'jira', as source); an unmatched or
-- bot account gives null, so the issue is left out of per-person figures and counts for the person
-- from the next read once the Admin links the account, with no re-sync.
-- The calc/work issue loaders and the Work items repository read this view instead of the table.
CREATE VIEW issues_with_assignee AS
SELECT i.id, i.key, i.summary, i.issue_type, i.status,
       CASE WHEN i.assignee_account_id IS NULL THEN i.assignee_id
            WHEN a.is_bot THEN NULL
            ELSE a.person_id END AS assignee_id,
       i.sprint_id, i.release_id, i.story_points, i.estimate_hours, i.spent_hours, i.remaining_hours,
       i.due_on, i.started_on, i.resolved_on, i.carried_over, i.reopened, i.was_stale,
       i.source, i.external_id, i.synced_at, i.created_at, i.updated_at, i.version,
       i.html_url, i.assignee_account_id
FROM issues i
LEFT JOIN external_accounts a ON a.tool = i.source AND a.tool_account_id = i.assignee_account_id;
-- The app role reads the view through V100's default privileges, as with bugs_current (V240).
