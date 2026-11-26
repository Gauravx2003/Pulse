# Proposal

## Why

Live Jira sprints show as "Sprint 142", "Sprint 144" everywhere in Pulse, while the team knows them by their Jira names, such as "Atlas 2.4.1".

- The number is Jira's internal sprint id, not a sprint sequence, so it means nothing to anyone.
- The Jira instance has several boards whose sprints overlap (two sprints cover September 2026). With ids only, nobody can tell which sprint is which in the period picker.

The connector already parses the name from Jira's sprint field and then drops it, because `sprints` has no name column and every label is built as `"Sprint " + number`.

## What Changes

- **Store the name.** An additive migration adds `sprints.name` (nullable). Seed sprints keep it empty.
- **Write it from Jira.** The Jira connector writes the sprint's Jira name on each sprint record.
- **Show it wherever a sprint is labelled**, with "Sprint N" when there is no name, so seed sprints, demo screens and oracle figures are unchanged:
  - the period picker and every period title and short name (`PeriodCalendar`)
  - Work items' sprint column, and the release cards' sprint lists (Overview, Work items, Team utilization)
  - the Effort "mid-sprint" item, the workload action text and the Action Center forecast title
- **Backfill on `pulse_live`.** Sprints are written from issues, so existing sprints get their names on the next full read.
- **Docs.** The sprints row of the "Jira Server → Pulse" mapping in `docs/live-connectors.md`.

No API removal; period keys (`s:J142`, `s:S43`) do not change, so saved view state and snapshots stay valid.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `platform/period-calendar`: a sprint is labelled by its name when it has one, else "Sprint N"; two sprints with the same name stay distinguishable.
- `admin/integrations-connectors`: the Jira connector stores each sprint's name.

## Impact

- **Track 2:**
  - migration `V296__sprint_names.sql` in the work migrations
  - `calc/core/PeriodCalendar` and the sprint query in `CalcService`
  - `calc/work/EffortFormulas`, `WorkloadFormulas` (sprint name beside `sprintKey`), `WorkViewFormulas` (release sprints)
  - `modules/work/WorkloadActionProvider`
  - the work, utilization and action-center screens
- **Track 3:** `JiraMapper.sprintRecord`, the Jira fixtures and connector tests.
- **Track 1:** `docs/live-connectors.md`, and running the full read on `pulse_live`.
- **Order matters.** Ingest rejects a record field that is not a column of its table, failing the whole batch. The connector change must not merge before V296.
- **Unchanged:** seed data, oracle tests, period keys and snapshots.
