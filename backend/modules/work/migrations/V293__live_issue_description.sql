-- V293 live Jira issue description (Track 2 for task 26.2, change pulse-v2-live-connectors, design.md decision 6).
-- Additive only: one nullable column, and the V290 read view re-created with that column appended last
-- (CREATE OR REPLACE keeps every existing column, name and position). No Phase 1 column, constraint or row
-- changes; seed rows have no description and stay null.

-- Written by the live connector through IngestService: Jira Server REST v2 returns the description as a
-- plain text / wiki markup string, stored as is (no ADF parsing).
ALTER TABLE issues ADD COLUMN description text;

CREATE OR REPLACE VIEW issues_with_assignee AS
SELECT i.id, i.key, i.summary, i.issue_type, i.status,
       CASE WHEN i.assignee_account_id IS NULL THEN i.assignee_id
            WHEN a.is_bot THEN NULL
            ELSE a.person_id END AS assignee_id,
       i.sprint_id, i.release_id, i.story_points, i.estimate_hours, i.spent_hours, i.remaining_hours,
       i.due_on, i.started_on, i.resolved_on, i.carried_over, i.reopened, i.was_stale,
       i.source, i.external_id, i.synced_at, i.created_at, i.updated_at, i.version,
       i.html_url, i.assignee_account_id,
       i.description
FROM issues i
LEFT JOIN external_accounts a ON a.tool = i.source AND a.tool_account_id = i.assignee_account_id;
-- The app role keeps reading the view: CREATE OR REPLACE keeps its grants.
