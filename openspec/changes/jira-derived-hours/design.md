# Design

## Context

See proposal.md (Why). What shapes the approach:

- **Where hours logged come from.** Hours logged, days worked, closed-period utilization and effort item hours read the `worklogs` table (`person_id`, `issue_id`, `work_date`, `hours`):
  - `calc/work/PeriodWork` inputs
  - `WorkloadFormulas.WORKLOGS`
  - `WorkViewFormulas`
  - `EffortFormulas` item hours

  `issues.spent_hours` feeds only the estimate-accuracy part of sprint metrics.
- **What the Jira connector writes today.** Sprints, issues and issue events. No worklogs, and `fields=` does not ask for `worklog`.
- **How live people are resolved.** Live rows keep the raw Jira account. The person is resolved at read time through views (`issues_with_assignee`, `issue_events_with_actor`), so linking applies without a re-sync. `worklogs` has no account column yet.
- **What a connector cannot do.** It cannot read SQL, rules or holidays (pulse-v2 design decision 1), and `IngestService` never deletes rows.
- **Measured on `pulse_live` (26 Nov 2026):**
  - Dev: 4,765 issues had a Start Time (Dev); 43 have no end, 14 of them on issues not done.
  - QA: 517 had a Start Time (QA); 4 have no end, none on unfinished issues.
  - In the search answer the fields are datetimes with an offset, e.g. `2026-11-25T22:47:00.000+0530`.

## Goals / Non-Goals

**Goals:**
- Hours logged for this team come from the Start/End fields, with the same numbers on every screen that reads hours.
- A team that logs work in Jira gets its worklogs, and those take precedence.
- Moving or clearing a Start/End value in Jira never leaves stale hours behind.

**Non-Goals:**
- Story points, estimates, remaining hours and live-sprint utilization (see proposal).
- The "Design" Start/End fields; they can be added later with the same mechanism.
- Rebuilding earlier rounds of an interval from the changelog. Only the current field values count.
- A data-quality screen for suspicious intervals.

## Decisions

### 1. The connector stores raw intervals; the calc loaders split them into hours

- **Connector (Track 3).** It writes one `issue_work_intervals` row per issue and kind (`dev`, `qa`):
  - `external_id` is `<key>|dev` or `<key>|qa`
  - `starts_at`, `ends_at` (nullable), `account_id`, `end_source` (`field`, `resolution`, `open`)
- **Calc loaders (Track 2).** They expand intervals into hours per person per day, with the working-day window, `dayHours`, holidays, leave and visit days. They already have the period calendar and `ctx.rules()`.
- **Why this way.**
  - The upsert replaces the single row per issue and kind, so a moved or cleared value never leaves stale days.
  - Holidays and rules are read where they live.
- **Alternative rejected: the connector writes per-day `worklogs` rows.** Ingest never deletes, so moving End Time from Friday to Wednesday would keep Thursday's and Friday's hours. The connector also cannot see holidays or rules.
- **A cleared field.** When a field is cleared in Jira, the connector writes the row with `starts_at` null, and the loader ignores it. That is the "delete" without a delete.

### 2. Precedence per issue

- **Jira time tracking present** (any embedded worklog, or `timespent` > 0):
  - worklogs are written to `worklogs` (`source = 'jira'`, `external_id` = worklog id, `work_date` = `started` date in Asia/Kolkata, `hours` = `timeSpentSeconds / 3600`, `account_id` = author `name`)
  - the issue's interval rows are written with `starts_at` null, so a sync after someone starts logging work retires the old intervals
- **Otherwise:** intervals as in decision 1.
- The rule is decided per issue, not per person or per day. It is simple, explainable, and avoids double counting.
- **Embedded worklogs.** The search answer embeds up to 20 worklogs per issue (`worklog.total` tells the real count). When the total is higher, the issue still takes the time-tracking branch with the embedded ones only, and the sync result counts the truncated issues. Fetching `/issue/{key}/worklog` is a later option. The measured data has 17 worklog edits in total, so this does not bite today.

### 3. Who an interval is credited to

The account is the issue's assignee at the interval's start, from the assignee changelog. If nobody was assigned then, it is the author of the changelog entry that last set the start field. Failing both, it is the current assignee.

- **Why.** The team reassigns Dev → QA. The current assignee would credit a developer's hours to QA, and "who set the field" is often a lead or a bot.
- **Alternatives rejected:**
  - the current assignee only, which is wrong after a hand-over
  - the start field's editor only, which credits leads who fill fields for others
- **Bots.** A bot account counts for no person, as for issues.

### 4. Ends

- The end is the End field when it is set.
- Else, if the issue is resolved, the end is `resolutiondate` (`end_source = 'resolution'`). This is a full datetime only the connector has; `issues.resolved_on` is a date.
- Else `ends_at` is null (`open`), and the loader counts up to now, never after today.
- An end before the start makes the row count nothing; it is still stored as written.

### 5. Splitting into hours (one pure function, Track 2)

`WorkIntervals.hours(intervals, calendar, window, dayHours, today, now)` → `{date → hours}` per person:

1. Clip each interval to `[start, min(end, now)]` in Asia/Kolkata.
2. For each weekday in that span that is not a holiday, leave or visit day for the person, take the overlap with the window on that date.
3. Merge a person's overlapping pieces on the same day, so a Dev and a QA interval or two issues are not counted twice.
4. Cap the day at `dayHours`.

The loaders add these hours to worklog hours by person and day, with each issue taking only one source (decision 2). Everything downstream (`PeriodWork`, Effort) is unchanged.

- **Why merge overlaps.** People often hold several issues "in progress" at once. Summing would give 24-hour days.
- **Effort per issue.** Effort item hours for an issue are that issue's own clipped hours before the cross-issue merge. The per-day total stays capped, so an issue's share can be larger than its share of the capped day. This is acceptable for effort lists, and noted for review.

### 6. The working-day window rule

- The new rule `workday: {"start": "10:00", "end": "18:00"}` sits in the staleness and capacity group.
- It is validated like `att.lateAfter` (HH:MM) plus end after start.
- Default: 8 hours, matching `dayHours`. An Admin who sets a longer window still gets at most `dayHours` per day.

### 7. Data model (Track 2 migration, next free number in V29x)

- `worklogs.account_id text`, plus the read view `worklogs_with_person`: a seed row keeps `person_id`; a live row takes the linked, non-bot person.
- `issue_work_intervals`:
  - `id`, `issue_id` (FK), `kind` (`dev` / `qa`), `starts_at`, `ends_at`, `end_source`, `account_id`
  - `source`, `external_id`, `synced_at`, `created_at`, `updated_at`, `version`
  - unique on (`source`, `external_id`)
- A read view resolves its person the same way.
- All of it is additive. Seed rows and seed figures are untouched; the seed has no intervals.

### 8. Config and discovery (Track 3)

- New optional `configSchema` fields `devStartField`, `devEndField`, `qaStartField`, `qaEndField`, each with pattern `customfield_[0-9]+`.
- `proposals()` adds them for custom fields of schema type `datetime` named exactly "Start Time (Dev)" and so on, ignoring case.
- A pair with either id empty is off.
- The ids are added to `fields=`, and so is `worklog`.

## Risks / Trade-offs

- **[A forgotten open interval keeps adding 8 h a day]** → it counts only while the issue is unresolved (14 Dev cases today). Track 2's review can add a cap later without a data change.
- **[Start/End time is elapsed time, not effort]** → clipping to the window and capping at `dayHours` bounds it, and overlaps merge. The numbers read as "time on the issue in office hours". The runbook says so, and the Work items tooltip or a docs note names the source.
- **[Credited to the wrong person after unusual hand-overs]** → decision 3's order covers the normal Dev → QA flow. The interval keeps `account_id`, so a later correction is a re-sync, not a migration.
- **[Per-issue precedence hides intervals once one worklog appears]** → this is intended. Time tracking is the stronger source, and the switch is visible in the sync counts.
- **[Changelog dates are display strings ("25/Nov/26 10:47 PM")]** → intervals are read from the field values in the search answer (ISO with offset), never from the changelog. The assignee history only needs the changelog's `created` times, which are ISO.
- **[More rows per sync]** → at most two interval rows per issue plus worklogs, the same order of size as `issue_events`.

## Migration Plan

1. Track 2 lands the migration and the views with loaders reading worklogs through `worklogs_with_person`. There are no intervals yet, so figures are unchanged, which proves the plumbing.
2. Track 3 lands the connector mapping and config fields.
3. On `pulse_live`: confirm the four field ids in Integrations (saving the changed config clears the sync cursor, task 6.2), run Sync now, which then reads everything, and compare a few people's hours with their Jira fields by hand.
4. **Rollback.** Clear the four field ids and sync: the rows get `starts_at` null and count nothing. Or redeploy the previous build: the new table and column are unused by old code.

## Open Questions

- The office's real working-day window, which may differ from 10:00–18:00. It is only a rule value and can be changed on the Rules screen.
- Whether a lunch break should come out of the window. If wanted, it is a later rule; it changes no structure here.
