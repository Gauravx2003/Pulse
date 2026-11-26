# Design

## Context

- **Storage.** `sprints` (V100) has `key`, `number`, the three dates and the ingest columns. There is no name.
- **What the Jira connector does.**
  - `JiraMapper.sprints()` reads `name` from both forms of the sprint field.
  - `JiraMapper.sprintRecord()` writes `key = J<id>`, `number = <id>` and the dates, and leaves the name out.
  - Sprints without both dates (future sprints) are not stored.
- **Where a sprint is labelled today:**
  - `PeriodCalendar.sprint()` builds `"Sprint " + number` as the period's `short`, and `"<short> · <range>"` as its label. The picker, page titles and snapshots' period labels use these.
  - `EffortFormulas` mid-sprint item: `"Sprint " + number`.
  - `WorkloadActionProvider`: `"Sprint " + key.replace("s:S", "")`. For a live key this gives "Sprint s:J142", which is already wrong.
  - Frontend:
    - Work items' sprint column shows the raw `sprintKey`.
    - The release cards (Overview, Work items, Team utilization) show the keys with `s:` stripped.
    - Action Center keeps the digits of the forecast's sprint key.
- **Ingest.** It writes only columns that exist in the table and fails the whole batch on an unknown record field.
- **Measured on the live database.** The connector stored about 780 dated sprints from several boards, and their dates overlap.

## Goals / Non-Goals

**Goals:**
- Every place that names a sprint shows the same label: the Jira name, else "Sprint N".
- Seed sprints, demo screens and oracle figures stay exactly as they are.
- Two sprints that share a name can still be told apart.

**Non-Goals:**
- Filtering or grouping sprints by Jira board. The board is not stored; names already tell the boards apart in practice.
- Storing future (undated) sprints.
- The Action Center "next sprint" forecast for live sprints. It still derives `S<n+1>` from the key; that is a separate issue.
- A general "read everything again" action in Integrations.

## Decisions

### 1. A nullable `sprints.name`, filled only by live connectors

- **Migration.** `V296__sprint_names.sql` (Track 2, additive): `ALTER TABLE sprints ADD COLUMN name text`.
- **Seed.** Seed sprints keep `name` null, so their labels stay "Sprint N" with no seed change.
- **Number unchanged.** `number` stays the Jira id. It is the fallback only, and nothing sorts or computes on it for live sprints.
- **Alternative rejected: putting the name into `number`'s label at read time from `issue_events`.** It would be fragile and slow, and the connector already has the name.

### 2. The connector writes the name on every sprint record

- `sprintRecord` adds `name`, the trimmed Jira name; an empty name is written as null.
- The upsert by (`source`, `external_id`) updates the name when the sprint is renamed, so there is no new row.
- When one page holds the same sprint twice, the existing "last one wins" merge of sprint records applies.

### 3. One label function, used everywhere

- `PeriodCalendar` computes each sprint's `short`:
  - its name if set, else `"Sprint " + number`
  - when two sprints in the calendar have the same `short`, both get `" (" + key + ")"` appended
  - the label stays `"<short> · <range>"` plus " (active)"
- **Everything else reads that `short` instead of formatting its own:**
  - Effort's mid-sprint item and the workload action text get it from the calendar the formula already has.
  - `WorkloadFormulas` adds `sprintName` beside `sprintKey` on Work items rows.
  - The release formulas send `{key, name}` pairs for the release's sprints.
  - The frontend shows `sprintName`, and the names in release cards and the Action Center forecast title, falling back to the key only when the name is missing.
- **Why one function.** The clash rule needs the whole sprint list, and a second formatter would drift from it.
- **Long names.** The period picker (`PlPeriodControl`) is a native `<select>`, so it grows with its longest option. It gets a `max-width` with an ellipsis, and the selected label as its `title`, so a long name cannot push the toolbar past phone width.

### 4. Keys do not change

- Period keys stay `s:J142` / `s:S43`.
- Saved period choices, snapshot subject keys, links and the e2e suites keep working.
- A rename in Jira changes only the label.

### 5. Backfill by one full read on the live database

- Sprints are written from issues, and the next incremental sync only touches sprints of issues updated since the cursor. Existing sprints therefore get their names on the next full read.
- The Jira config is unchanged, so the cursor reset of jira-derived-hours 6.2 does not trigger it.
- **On `pulse_live`** (the only live database), the developer clears the Jira cursor once after deploying, then runs Sync now. This is the same as the 6.1 procedure.
- **Alternative rejected: a migration that clears the cursor.** It would have to land with the connector change rather than with V296, or the full read would run before names are written.

## Risks / Trade-offs

- **[Connector merged before V296]** → every sync fails with `bad_payload` (unknown column). The tasks order Track 2's migration first, and the connector task's Verify runs `JiraShapeTest` against the migrated schema.
- **[Long or noisy Jira names]** → shown as Jira has them, truncated in narrow places. The name is the team's own vocabulary, which is the point.
- **[Same name on two boards]** → disambiguated by key (decision 3). Without a stored board, the key is the only stable distinguisher.
- **[Labels inside frozen snapshots]** → a snapshot's inputs may keep an old label text, but screens take labels from the calendar at read time, so closed periods show the new names without a re-freeze. Task 1.3 confirms this.
