# Tasks: hours from Jira worklogs and Start/End fields

Coding rules are those of `pulse-v2-live-connectors` design decision 0: `JsonObject` only, SQL in repositories, nothing blocking the event loop, Allman braces.

**Order:**
- Track 2 lands groups 1–3 first. Group 1 alone changes no figure.
- Track 3 then lands groups 4–5.
- Group 6 runs after both.
- Track 2 reviews the Track 3 mapping (4.4); Track 3 reviews the splitting (3.1).

## 1. Data layer (Track 2)

- [x] 1.1 Add migration `V295__live_worklogs_and_intervals.sql` (additive) in `backend/modules/work/migrations/`:
  - `worklogs.account_id text`
  - table `issue_work_intervals`: `issue_id` FK, `kind` `dev`/`qa`, `starts_at`, `ends_at`, `end_source`, `account_id`, `source`, `external_id`, `synced_at`, timestamps, `version`; unique (`source`, `external_id`)
  - views `worklogs_with_person` and `issue_work_intervals_with_person`: a seed row keeps `person_id`; a live row takes the linked, non-bot person, as in V290

  Verify: `MigrationRunnerTest` passes, and `check-migration-versions.sh` passes.
- [x] 1.2 Point every worklog reader at `worklogs_with_person`, with no formula change:
  - `WorkloadFormulas.WORKLOGS`
  - `WorkViewFormulas` day hours
  - `EffortFormulas` item hours

  Verify:
  - the Phase 1 Effort, Utilization and Work tests and `FullOracleTest` pass unchanged
  - a new test shows that a live worklog of an unmatched account counts for nobody, and for the person once linked, with no re-sync

## 2. Working-day window rule (Track 2)

- [x] 2.1 Add `workday.start` and `workday.end` (TIME) to `RuleCatalogue` in the staleness and capacity group, with defaults `10:00` and `18:00` in the `RuleCatalogue` defaults; a stored rule version that predates the window reads it from the defaults (the seeded S100 is forward-only and is not edited). Reject an end that is not after the start.

  Verify: `RulesRoutesTest` covers the default, a valid change creating a new audited version, and the 18:00–10:00 refusal with a message on the window.
- [x] 2.2 Check that the Rules screen shows and saves the two fields in the existing card (the TIME input already exists for `att.lateAfter`).

  Verify: the rules e2e test or a new assertion shows the window, and `npm run lint` is clean.

## 3. Splitting intervals into hours (Track 2)

- [x] 3.1 Add the pure function `calc/work/WorkIntervals` (design decision 5):
  - clip to [start, min(end, now)] in Asia/Kolkata
  - weekdays only, minus holidays, leave and visit days
  - overlap with the window
  - merge a person's overlaps per day
  - cap at `dayHours`
  - never after today

  Verify: `WorkIntervalsTest` covers every scenario of `specs/work/effort-extra-mile` (two days, weekend and holiday, open, resolved-open, Dev/QA overlap, end before start) plus a window change.
- [ ] 3.2 Feed interval hours into the same per-person, per-day hours as worklogs.
  - Feed them into `PeriodWork` inputs, the `WORKLOGS` loader and the work-view day hours.
  - For Effort items, use the issue's own clipped hours.
  - Each issue takes one source: worklogs when it has any, else its intervals.

  Verify:
  - a loader test with seed and live rows gives hours logged, days worked and closed-period utilization as the sum of both sources, with no 24-hour days
  - `FullOracleTest` is unchanged (the seed has no intervals)
- [ ] 3.3 Add the link-then-read test for intervals (extend `LinkThenReadTest` or a sibling): an interval of an unmatched account counts for nobody; once linked, the person's hours logged and Effort change on the next read.

  Verify: the test passes.

## 4. Jira connector (Track 3)

- [ ] 4.1 Config and discovery:
  - add `devStartField`, `devEndField`, `qaStartField` and `qaEndField` to `configSchema` (optional, `customfield_[0-9]+`)
  - have `proposals()` offer datetime custom fields named "Start Time (Dev)" and the rest, ignoring case
  - add the ids and `worklog` to `fields=`

  Verify: `JiraConnectorTest` covers the proposals from the recorded `field.json` (10200/10201, 11000/10701) and an empty id turning the pair off.
- [ ] 4.2 Worklogs:
  - for an issue with time tracking, map embedded worklogs to `worklogs` records (external id = worklog id, `work_date` = `started` date in Asia/Kolkata, hours = seconds/3600, `account_id` = author `name`)
  - resolve the authors through `accounts().resolve`
  - count issues whose `worklog.total` exceeds the embedded list in the sync result

  Verify:
  - a re-sync with changed hours updates in place
  - `JiraConnectorContractTest` still passes
- [ ] 4.3 Intervals:
  - for an issue without time tracking, map each configured pair to one `issue_work_intervals` record (`<key>|dev`, `<key>|qa`)
  - credit it to the assignee at the start from the assignee changelog, else the author who last set the start field, else the current assignee
  - ends come from the End field, else `resolutiondate` (`end_source = resolution`), else null (`open`)
  - a cleared start, or an issue that gained time tracking, writes the record with `starts_at` null

  Verify: `JiraConnectorTest` covers each scenario of `specs/admin/integrations-connectors`: recorded, hand-over, resolved-open, time tracking wins, interval moved, fields not configured.
- [ ] 4.4 Fixtures and mapping record:
  - add worklog and Start/End cases to `live-fixtures/jira/search-page1.json` / `search-page2.json` in the recorded shape: datetime with `+0530`, a hand-over, an open interval on a resolved issue, an issue with worklogs and Start/End
  - extend the "Jira Server → Pulse" section of `docs/live-connectors.md` with the worklog and interval rows and the precedence rule

  Verify:
  - `JiraFixturesTest` passes, with no real email
  - Track 2 approves the mapping in the PR

## 5. End-to-end (Track 3)

- [ ] 5.1 Extend `live-fixtures/jira-e2e/` and `frontend/e2e/live-flows/jira.spec.js`:
  - after Sync now, Priya's hours logged on Effort and Team utilization (closed period) equal the hours her fixture intervals give under the default window
  - an interval of `arjun.m` counts only after he is linked

  Verify: `npm run test:live-flows` passes on a fresh database.
- [ ] 5.2 Update `docs/demo.md` ("The real team on live Jira"):
  - confirm the four field ids at Test connection
  - what derived hours mean: time on the issue in office hours, capped per day
  - where to change the working-day window

  Verify: the steps match the screens of 5.1.

## 6. Acceptance on real data

- [ ] 6.1 On `pulse_live`:
  - confirm the four field ids, run Sync now and record the sync counts (intervals, worklogs, truncated)
  - for three people, compare a closed sprint's hours logged with a hand calculation from their Jira Start/End values
  - check that no day exceeds `dayHours`

  Verify: the results are recorded in this change's PR description, and every compared value matches.
