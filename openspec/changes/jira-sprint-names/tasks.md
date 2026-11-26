# Tasks: Jira sprint names

Coding rules are those of `pulse-v2-live-connectors` design decision 0: `JsonObject` only, SQL in repositories, nothing blocking the event loop, Allman braces.

**Order:**
- Track 2 lands group 1 first. Group 1 alone changes no label: no sprint has a name yet.
- Track 3 then lands group 3, never before V296 (an unknown column fails the whole sync).
- Group 2 (Track 2) can land with group 1 or after it.
- Group 4 runs after all of them.
- Track 3 reviews the label rule (1.2); Track 2 reviews the mapping (3.1).

## 1. Storage and the label rule (Track 2)

- [x] 1.1 Add migration `V296__sprint_names.sql` (additive): `sprints.name text`, nullable. Add `name` to the sprint query `CalcService` loads into the calendar.

  Verify: `MigrationRunnerTest` and `check-migration-versions.sh` pass.
- [x] 1.2 In `PeriodCalendar.sprint()`, build `short` from the name, else `"Sprint " + number`. When two sprints of the calendar share a `short`, append `" (" + key + ")"` to both. The label stays `"<short> · <range>"` plus " (active)". Keys do not change.

  Verify:
  - `PeriodCalendarTest` covers a named sprint, an unnamed one ("Sprint 43" unchanged), and two sprints with the same name
  - `FullOracleTest` and the period e2e tests (`periods.spec.js`) pass unchanged
- [x] 1.3 Check that closed periods show the calendar's current label, not the one stored in a snapshot's `inputs.period`. If a screen reads the stored label, switch it to the calendar's.

  Verify: a test renames a sprint after its period is frozen, and the period list shows the new name.

## 2. Every other place a sprint is named (Track 2)

- [x] 2.1 Backend: take the sprint label from the calendar instead of formatting it locally:
  - the `EffortFormulas` mid-sprint item
  - the `WorkloadActionProvider` action text (which today gives "Sprint s:J142" for a live key)
  - `sprintName` beside `sprintKey` on Work items rows (`WorkloadFormulas`)
  - `{key, name}` for a release's sprints (`WorkViewFormulas`, `TeamFormulas`)
  - the Action Center forecast's sprint name

  Verify: the existing Effort, Work items, release and Action Center tests pass unchanged on seed data, and a new test with one named live sprint shows its name in each payload.
- [x] 2.2 Frontend: show those names:
  - Work items' sprint column
  - the release cards' sprint lists (Overview, Work items, Team utilization)
  - the Action Center forecast title

  Each falls back to the key only when the name is missing. Give `PlPeriodControl` a `max-width` with an ellipsis and the selected label as its `title`.

  Verify:
  - the e2e suites for these screens pass unchanged on the seed
  - a stubbed live sprint with a long name shows its name, and the period control stays within a 390 px wide page
  - `npm run lint` is clean

## 3. Jira connector (Track 3)

- [x] 3.1 `JiraMapper.sprintRecord` writes `name`: Jira's name trimmed, null when empty. Add a renamed sprint and an unnamed one to `live-fixtures/jira/` in the recorded shape, string and object forms.

  Verify:
  - `JiraConnectorTest` covers: name stored in both forms, a rename updating in place with no second row, an empty name stored as null
  - `JiraShapeTest` passes on the migrated schema
  - `JiraFixturesTest` passes, with no real name or email in the fixtures
- [x] 3.2 Extend `live-fixtures/jira-e2e/` and `frontend/e2e/live-flows/jira.spec.js`: after Sync now, the period picker and Work items show the fixture sprint's name instead of "Sprint 147".

  Verify: `npm run test:live-flows` passes on a fresh database.

## 4. Docs and the live database (Track 1)

- [ ] 4.1 Update the sprints row of "Jira Server → Pulse" in `docs/live-connectors.md` (`name` = the sprint's Jira name; the screens show it, else "Sprint <number>"). Add to `docs/demo.md` ("The real team on live Jira") that sprint names arrive on the next full read.

  Verify: the rows match 1.2 and 3.1.
- [ ] 4.2 On `pulse_live`, after deploying groups 1–3:
  - clear the Jira cursor once (design decision 5) and run Sync now
  - check that every stored Jira sprint has a name
  - check that the period picker lists sprints by their Jira names, and that the overlapping sprints of different boards are told apart

  Verify: the counts (sprints, sprints with a name, duplicate names) are recorded in the PR description, with no real names.
