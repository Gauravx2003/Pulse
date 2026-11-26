-- V294 live assignee changes (Track 2, task 25.4 review, change pulse-v2-live-connectors, design.md decision 5).
-- Additive only: the V292 read view re-created with two columns appended last (CREATE OR REPLACE keeps
-- every existing column, name and position). No table, constraint or row changes.

-- An assignee change holds the old and the new assignee. A seed event stores their person ids, as text;
-- a live Jira event stores their Jira usernames, matched to people through external_accounts like every
-- live account. from_person and to_person give the person id (as text) for both, so the Effort
-- take-overs read one column: a username whose account is linked gives that person, an unmatched or bot
-- account gives null, and any other value (the seed's person ids) is kept as it is. Null for every other
-- kind of event.
CREATE OR REPLACE VIEW issue_events_with_actor AS
SELECT e.id, e.issue_id, e.occurred_at, e.kind, e.field, e.from_value, e.to_value,
       CASE WHEN e.actor_account_id IS NULL THEN e.actor_id
            WHEN a.is_bot THEN NULL
            ELSE a.person_id END AS actor_id,
       e.actor_name,
       e.is_bot OR coalesce(a.is_bot, false) AS is_bot,
       e.summary, e.source, e.external_id, e.synced_at, e.created_at, e.actor_account_id,
       CASE WHEN e.kind <> 'assignee_change' THEN NULL
            WHEN f.id IS NULL THEN e.from_value
            WHEN f.is_bot THEN NULL
            ELSE f.person_id::text END AS from_person,
       CASE WHEN e.kind <> 'assignee_change' THEN NULL
            WHEN t.id IS NULL THEN e.to_value
            WHEN t.is_bot THEN NULL
            ELSE t.person_id::text END AS to_person
FROM issue_events e
LEFT JOIN external_accounts a ON a.tool = e.source AND a.tool_account_id = e.actor_account_id
LEFT JOIN external_accounts f ON e.kind = 'assignee_change' AND f.tool = e.source AND f.tool_account_id = e.from_value
LEFT JOIN external_accounts t ON e.kind = 'assignee_change' AND t.tool = e.source AND t.tool_account_id = e.to_value;
-- The app role keeps reading the view: CREATE OR REPLACE keeps its grants.
