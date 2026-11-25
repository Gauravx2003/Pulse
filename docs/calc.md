# Calculation library: adding a formula

Every metric, score and rating comes from one service, `CalcService` (spec
`platform/calculation-engine`, design.md decision 5). Endpoints read numbers from it through
`ctx.calc()`. They never compute a formula, a threshold or a band themselves. This page is for
Tracks 2 and 3, who add formulas in `backend/calc/work/` and `backend/calc/people/`.

## 1. Where formulas live

| Folder | Package | Owner | Descriptor |
|---|---|---|---|
| `backend/calc/core/` | `com.pulse.calc.core` | Track 1 | `CoreFormulas` |
| `backend/calc/work/` | `com.pulse.calc.work` | Track 2 | `WorkFormulas` |
| `backend/calc/people/` | `com.pulse.calc.people` | Track 3 | `PeopleFormulas` |

A descriptor is a public class with a no-argument constructor. Its simple name ends in `Formulas`
and it implements `FormulaModule`. `FormulaRegistry` finds every such class by classpath scan, the
same way feature modules are found. A folder may hold more than one descriptor (for example
`VisitFormulas` next to `PeopleFormulas`). Each descriptor's `id()` must be its folder's name. You
never edit `calc/core` to add a formula.

Put the maths in a pure class next to the descriptor: plain data in, plain data out, no database.
Verify that class against the prototype with the oracle ([oracle.md](oracle.md)). The descriptor
only loads the inputs and calls it.

## 2. A formula

```java
public final class WorkFormulas implements FormulaModule
{
    @Override
    public String id()
    {
        return "work";
    }

    @Override
    public List<Formula> formulas()
    {
        return List.of(
                Formula.of("work.utilization", Set.of("person", "period"), Set.of(ISSUES), WorkFormulas::utilization),
                new Formula("work.sprintMetrics", Set.of("period"), Set.of(ISSUES),
                        Set.of(PeriodCalendar.SPRINT), WorkFormulas::sprintMetrics));
    }

    private static Future<Object> utilization(CalcContext ctx, JsonObject params)
    {
        Future<Object> period = ctx.get("core.period", params);           // other formulas, by name
        Future<JsonArray> rows = ctx.sql().queryList("SELECT ...", Tuple.of(...));

        return Future.all(period, rows).map(all -> Utilization.of(rows.result(), (JsonObject) period.result(),
                ctx.rules()));                                              // the pure function
    }
}
```

- **Name:** starts with the module id, such as `work.utilization`. It must be unique.
- **Params:** the parameter names the formula reads. Only these go into the cache key. `period` is a
  period key (`s:S43`, `m:2026-09`, `q:Q3`, `all`). `person` is a `people.id`.
- **Topics:** the data the formula reads. Use a constant of your own, such as `work.issues`, or a
  `CalcService` constant (`PEOPLE`, `SPRINTS`, `PERFORMANCE`). You don't need to list the topics of
  formulas you call through `ctx.get`; their topics count automatically. Every formula also depends
  on the rules.
- **Period kinds (optional):** `Set.of(PeriodCalendar.SPRINT)` or `QUARTER` makes the formula
  periodic. When that period closes, its value is frozen in the period's snapshot. After that, reads
  of the closed period come from the snapshot, and a later rule edit or data change does not move
  them. A periodic formula takes `period`, and `person` when it is per person.
- **Result:** a number, string, boolean, `JsonObject`, `JsonArray`, or `null` for "no value". Don't
  return NaN or infinities: the property test in `CalcPropertyTest` checks for them.
- **Context (`CalcContext`):** `ctx.sql()` (read only), `ctx.today()` (pinned by `PULSE_TODAY`
  outside production), `ctx.rules()` (the rule set in force) and `ctx.calendar()`. Use `ctx.get(name,
  params)` for another formula. Use `ctx.optional(name, params)` for a formula that may not be
  registered yet; it resolves to null in that case.

## 3. Reading numbers in an endpoint

```java
ctx.calc().get("work.utilization", new JsonObject().put("person", id).put("period", key))
        .onSuccess(rc::json)
        .onFailure(rc::fail);
```

`get` returns the result guarded by `ResultGuard`. A null, NaN or infinity becomes
`{"notApplicable": true}`, and −0 becomes 0, so clients never see null. An unknown formula or period
key fails with `IllegalArgumentException`. Answer it as 400.

## 4. Telling CalcService that data changed

Results are cached. After an import commit, a confirmation, an override or a rule change, publish
the topics that changed:

```java
CalcService.publish(ctx.vertx(), "people.attendance");      // after the attendance upload commits
CalcService.publish(ctx.vertx(), CalcService.RULES);        // after a Rules & thresholds save (drops everything)
```

Every cached result that read one of those topics, directly or through other formulas, is dropped.
The next read recomputes it.

CSV imports need no extra call. `ImportService` publishes `pulse.imports.committed` and
`pulse.imports.undone` with the import `type`, and CalcService drops the topic
`CalcService.importTopic(type)`, for example `imports.holidays`. A formula that reads a table filled
by an import lists that topic, as `work.workingDays` does.

## 5. Input ports the core formulas read

The core formulas (index, culture values, Discipline, training, appraisal) need data that Tracks 2
and 3 own. They read it through these formula names. Until a port is registered it reads as null:
the part is left out, or the default is used, as the spec says (for example, Discipline is 3.0 with
no data).

| Port | Params | Returns | Used by |
|---|---|---|---|
| `work.okrPillar` | person, period (quarter) | number 1–5, or null with no OKRs | `core.pillars` (OKR pillar) |
| `work.cultureInputs` | person, period (quarter) | `{"<param key>": {raw, of?, high?}}` for the 18 work-measured culture parameters (sprints, bugs and support, PRs, live sprint, extra-mile items, coverage areas, weekly pulse) — registered in `calc/work/WorkCultureFormulas`, oracle-checked by `CoreSeedOracleTest` | `core.cultureData` |
| `people.cultureInputs` | person, period (quarter) | the same, for the people-measured parameters: `office_presence`, `customer_visit_rating`, `training_rating`, `kudos_received`, `incidents_handled` (done incident extra-mile items + reported Escalation visits) — registered by Track 3, oracle-checked | `core.cultureData` |
| `work.extraMileItems` | person, period (quarter) | `[{state, points, values}]` | `core.culture` (bonus) |
| `work.disciplineInputs` | person | `{days, stale, replan, status, block, logSame, bulk, dod, review, standup}`, as `Discipline.metrics` | `core.discipline` |
| `people.attendanceDiscipline` | person | `{score, absent}` from `Attendance.stats` for the quarter | `core.discipline` (rule h) |
| `people.visitDays` | person | `[{from, to, hours}]` — the person's customer visits; `hours` a day = reported on-site hours over the visit's days (half hours), 8 before the report — registered by visits | capacity (`WorkingDays` person `visits`) |
| `people.visitPointsByRef` | person | `{"VIS-101": 2.5}` — each visit's points total by reference (seed external id, else visit id) — registered by visits | extra-mile visit items (`calc/work` effort) |
| `people.menteeCount` | person | integer — different people the person has mentored on a started training plan (running or finished); null without the training tables — registered by training | `work.promotionReadiness` ("mentored 2 engineers") |
| `people.onboarding` | person | `{assignment, plan, progress, mentor}` while a joiner (< 3 months) is on a started, unfinished onboarding plan, else null — registered by training (task 21.2) | `work.onboarding` (no utilization alert, left out of rebalance) |
| `people.leaveDays` | person | full leave and absent days (`L`, `S`, `A`) as dates — registered by attendance (task 17.1) | capacity: pass as `WorkingDays` person `leave` |
| `people.lastOneOnOne` | person | date string of the person's last held 1:1, or null — registered by Track 3 (`PeopleSignalsFormulas`; topic `people.oneOnOnes`) | `work.signals` ("1:1 overdue" after 10 working days) |
| `people.kudosReceived` | person, period (quarter) | integer — kudos the person received in the quarter; null without the kudos table — registered by Track 3 | `work.signals` ("No recognition recently") |
| `people.trainingBehind` | person | `[{plan, overdue}]` — each unfinished training plan with its overdue modules (`trOverdue`) — registered by Track 3 | `work.signals` ("Training behind" at 2 or more) |
| `people.trainingLoad` | person | `{plans: [{hoursPerWeek, end}], mentees}` — active plans (module hours over plan weeks, last day) and active trainees mentored — registered by Track 3 | `work.capacity` (training and mentoring hours) |
| `people.wins` | person | `[{kind, what, text, at, value}]` of the last 90 days, `at` an ISO date: kudos received, visits rated 4.5 or more, completed training — registered by Track 3 | `work.wins` (Team wins, digest) |
| `people.progress` | person | `{achievements: [{title, detail, value}], nextSteps: [{text, target: {module, tab?}, selfOnly}], training: [{plan, stage, rating}]}` — Delighted customer, Training completed, Mentor; reviews to give (`selfOnly`), feedback to acknowledge (checkpoint reviews left out), sign-off, check-ins due, visit reports due — registered by Track 3 | `work.achievements`, `work.nextSteps`, `work.progressTrends` |
| `people.disciplineOverride` | person | `{score, reason}`, or null — the latest uncleared row of `discipline_overrides` (V370), registered by Track 3 | `core.discipline` |
| `people.cultureDetail` | person, period (quarter), value | `CultureScore.detail` of one value — `{value, score, parts, raterGroups, parameters, bonusItems}`, the parts reproducing `core.culture`; null without a Combined 360° rating (Track 3, task 19.1) | culture endpoints |
| `people.trainingPlans` | person | `[{weeks, rating}]` with `rating` from `TrainingRating.rating` — registered by training; null without the training tables | `core.training` → Growth pillar |
| `people.reviewCycles` | person | `[{name, responses: [{group, status, scores}]}]`, oldest first by due date (360° and training checkpoint cycles) — registered by reviews; null without the reviews tables | `core.appraisal` (360° and manager parts) |

The parameter keys of the culture inputs are those of `CultureParameters`. The 12 `signal`
parameters come from `culture_signals` and are not inputs.

## 6. Core formulas you can call

`core.people`, `core.period {period}`, `core.cultureData {person, period}`, `core.culture {person,
period}`, `core.discipline {person}`, `core.training {person}`, `core.pillars {person, period}`,
`core.index {person, period}`, `core.indexHistory {person}`, `core.appraisalPerformance {person}`,
`core.appraisal {person}`, `core.proposed {person, period}` (Exceeds, Meets or Developing for the
index) and `core.thresholds {}` (§8). The examples `work.workingDays {period}` and `people.serviceMonths
{person}` show the pattern in each track's folder.

## 7. Snapshots and closed periods

Each hour, and once at server start, `CalcService.closeEndedPeriods()` freezes every sprint and
quarter that has ended. It stores one `period` snapshot per period: every periodic formula's value
per person, with the rule version in force. The closed quarters 2025-Q4 to 2026-Q2 are seeded
(`S130__demo_index_history`) with each person's index from the prototype's `trend`. Appraisal
proposals and published 360° cycles freeze their own results through `SnapshotStore.freeze`. An
Admin recalculation of a closed period goes through `SnapshotStore.replace` with a reason. See
[backend-guide.md](backend-guide.md) §6.

## 8. Thresholds and tones

No threshold, cut-off or score band lives in a module or a screen (design.md decision 5; task 23.6).
Admin-editable values are rules (§2, `ctx.rules()`). Fixed ones are constants in `calc/` that modules
read through CalcService:

| Formula | Class | Returns |
|---|---|---|
| `core.thresholds {}` | `calc/core/CoreThresholds` | `{newJoinerMonths, index: {exceeds, meets}, attendance: {shortNoticeDays, officePct, lateExcess, uploadDueDays}, tones: {score}}` |
| `work.thresholds {}` | `calc/work/WorkThresholds` | `{staleItems, oneOnOneOverdueDays, pulse: {lowMood, highWorkload}, tones: {mood, workload, teamMood, teamWorkload, forecastFit, coverage, prCompliance}}`; `prCompliance` starts at rule `prTarget` |
| `people.thresholds {}` | `calc/people/PeopleThresholds` | `{trainingWeeklyHours}` |

A **tone band list** is `[{min, tone}]`, highest `min` first. A value takes the tone of the first band
whose `min` it reaches. The last band has no `min` and catches the rest: a null would come back from
`calc.get` as `{"notApplicable": true}`. In Java use `CoreThresholds.tone(value, bands)`, in the UI
`toneFor(value, bands)` from `@/kit/tones.js`.

Prefer sending the tone with the value from the formula, as `ActionCenter.forecast` (`tone`),
`ActionCenter.coverage` (`heat`), `ActionCenter.pulseTrend` (`moodTone`, `workloadTone`) and
`work.progressTrends` (culture `tone`, PR month `tone`) do. Send the band list only when the values do
not come from a formula: the weekly pulse answers carry `moodTone` and `workloadTone` from
`work.thresholds`, and the culture payloads carry `tones.score` from `core.thresholds`.

Targets shown next to a value come from the same formula as the value. `core.discipline` metrics carry
`targets` (`compliance` and `complianceTop` are rules `d4` and `d5`), and `work.liveSprint` carries
`staleAfter` (rule `staleDays`). Team maths go through formulas too: `work.effortTotals {people,
period}` gives the Effort team totals and unplanned share, and `Appraisal.newCtc`, `exception` and
`budget` give the appraisal table's money.

`RuleEditMovesScreensTest` checks that an Admin edit of `staleDays`, `d4`/`d5` and `prTarget` moves
Overview, the Discipline panel and Progress.
