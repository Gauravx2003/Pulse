# Proposal

## Why

On the internal Jira Server, story points, estimates and logged work are empty on all 10,656 synced issues. The last worklog edit was in January 2024. As a result, "Hours logged", days worked, closed-period utilization and the effort figures read 0 for the whole team, even though the sync works.

The team records time in other fields: the datetime custom fields **Start Time (Dev) / End Time (Dev)** and **Start Time (QA) / End Time (QA)**. Each pair has been edited thousands of times. Pulse should turn those intervals into working hours when Jira's own time tracking is empty.

The connector also writes no Jira worklogs today. So even a team that does log work would see 0. This change closes that gap too, because it is the source that takes precedence.

## What Changes

**Jira connector (Track 3)**
- Writes Jira's own worklogs to `worklogs` with `source = 'jira'`. Each worklog's author is kept as a Jira account and resolved to a person at read time, like issues.
- When an issue has no Jira time tracking (no worklog and no time spent), it records each Dev and QA interval from the configured Start/End fields as an **issue work interval**.
  - An interval has a start, an end (empty while the work is open) and the Jira account it is credited to.
  - It is replaced in place on every sync.
- Jira configuration gains four optional field ids: Dev start, Dev end, QA start, QA end.
  - Test connection proposes them by field name and type.
  - An empty field id turns derived hours off for that pair.

**Work data and calc loaders (Track 2)**
- Hours logged per person and day come from worklogs first. Where an issue has no worklog, they come from its work intervals, split into working hours:
  - only weekdays that are not holidays, leave or customer-visit days
  - each day clipped to the working-day window
  - each day capped at `dayHours`
- No formula changes. The loaders that feed hours logged, days worked, closed-period utilization and effort read through one view or loader that merges both sources.
- New rule: a **working-day window** (default 10:00–18:00, Asia/Kolkata), in the "staleness and capacity" group.

**Not changed**
- Story points, estimates and remaining hours. Start/End fields describe work done, not work planned.
- Live-sprint utilization reads remaining hours, so it stays 0 for this team until estimates are used. This is a known limit, not part of this change.

## Capabilities

### New Capabilities
- None.

### Modified Capabilities
- `admin/integrations-connectors`: adds a requirement for Jira worklogs and work intervals derived from configured Start/End datetime fields, with Jira time tracking taking precedence. Field discovery proposes the four ids.
- `work/effort-extra-mile`: hours logged come from Jira worklogs, or, for issues without any, from their work intervals split into working hours.
- `platform/rules-thresholds`: a working-day window rule, validated as a time range, used to split work intervals into hours.

## Impact

- **Backend, Track 3:** `backend/modules/connectors/jira/` (mapper, connector config, field discovery) and new fixtures under `live-fixtures/jira/`.
- **Backend, Track 2:**
  - an additive migration in the V29x range: `worklogs.account_id`, an `issue_work_intervals` table, and a read view that resolves the person of live worklogs and intervals
  - the `calc/work` hour loaders (`PeriodWork` inputs, Effort item hours)
  - the rule default and its validation
- **Frontend:** none required. Integrations renders the new config fields from the schema, and the Rules screen shows the new rule in its existing group. The Rules screen's card editor may need one input type; checked during implementation.
- **Data:** the first sync after release fills worklogs and intervals for every issue. Nothing is deleted. Seed data and seed figures are unchanged.
- **Depends on:** `pulse-v2-live-connectors` (Jira connector, V290–V294 views, account resolution).
