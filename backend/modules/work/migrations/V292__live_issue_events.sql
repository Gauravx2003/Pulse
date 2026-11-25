-- V292 live Jira activity (Track 2, task 25.3, change pulse-v2-live-connectors, design.md decision 5).
-- Additive only: one nullable column and one read view; no Phase 1 column, constraint or row changes.

-- Written by the live connector through IngestService: the raw tool account of the changelog, comment or
-- worklog author (Jira Server username). The person is matched at read time through external_accounts
-- (V190), as for issues.assignee_account_id (V290), so linking an account later counts that person's
-- earlier activity from the next read, with no re-sync.
ALTER TABLE issue_events ADD COLUMN actor_account_id text;

-- Activity with its actor resolved. An event without an actor account (every seed row) keeps its stored
-- actor_id and is_bot. A live event takes the person linked to its account, where the tool is the
-- event's source; an unmatched account gives no person, and an account the Admin marked as a bot makes
-- the event a bot event, so it never counts as activity.
-- The calc/work activity loaders and the issue timeline read this view instead of the table.
CREATE VIEW issue_events_with_actor AS
SELECT e.id, e.issue_id, e.occurred_at, e.kind, e.field, e.from_value, e.to_value,
       CASE WHEN e.actor_account_id IS NULL THEN e.actor_id
            WHEN a.is_bot THEN NULL
            ELSE a.person_id END AS actor_id,
       e.actor_name,
       e.is_bot OR coalesce(a.is_bot, false) AS is_bot,
       e.summary, e.source, e.external_id, e.synced_at, e.created_at, e.actor_account_id
FROM issue_events e
LEFT JOIN external_accounts a ON a.tool = e.source AND a.tool_account_id = e.actor_account_id;
-- The app role reads the view through V100's default privileges, as with issues_with_assignee (V290).
