-- V296 sprint names (Track 2 for task 1.1, change jira-sprint-names, design.md decision 1).
-- Additive only: one nullable column. No Phase 1 column, constraint or row changes; seed sprints have no
-- name and stay null, so their labels stay "Sprint <number>".

-- Written by the live Jira connector through IngestService: the sprint's Jira name, trimmed, null when
-- Jira has none. The upsert by (source, external_id) updates it when the sprint is renamed in Jira.
-- PeriodCalendar labels a sprint by this name, else "Sprint " + number.
ALTER TABLE sprints ADD COLUMN name text;
