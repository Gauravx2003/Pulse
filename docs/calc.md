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
| `work.cultureInputs` | person, period (quarter) | `{"<param key>": {raw, of?, high?}}` for the work-measured culture parameters | `core.cultureData` |
| `people.cultureInputs` | person, period (quarter) | the same, for the people-measured parameters (registered; `office_presence` so far) | `core.cultureData` |
| `work.extraMileItems` | person, period (quarter) | `[{state, points, values}]` | `core.culture` (bonus) |
| `work.disciplineInputs` | person | `{days, stale, replan, status, block, logSame, bulk, dod, review, standup}`, as `Discipline.metrics` | `core.discipline` |
| `people.attendanceDiscipline` | person | `{score, absent}` from `Attendance.stats` for the quarter | `core.discipline` (rule h) |
| `people.leaveDays` | person | full leave and absent days (`L`, `S`, `A`) as dates — registered by attendance (task 17.1) | capacity: pass as `WorkingDays` person `leave` |
| `people.disciplineOverride` | person | `{score, reason}`, or null | `core.discipline` |
| `people.trainingPlans` | person | `[{weeks, rating}]` with `rating` from `TrainingRating.rating` | `core.training` → Growth pillar |
| `people.reviewCycles` | person | `[{name, responses: [{group, status, scores}]}]`, oldest first | `core.appraisal` (360° and manager parts) |

The parameter keys of the culture inputs are those of `CultureParameters`. The 12 `signal`
parameters come from `culture_signals` and are not inputs.

## 6. Core formulas you can call

`core.people`, `core.period {period}`, `core.cultureData {person, period}`, `core.culture {person,
period}`, `core.discipline {person}`, `core.training {person}`, `core.pillars {person, period}`,
`core.index {person, period}`, `core.indexHistory {person}`, `core.appraisalPerformance {person}`
and `core.appraisal {person}`. The examples `work.workingDays {period}` and `people.serviceMonths
{person}` show the pattern in each track's folder.

## 7. Snapshots and closed periods

Each hour, and once at server start, `CalcService.closeEndedPeriods()` freezes every sprint and
quarter that has ended. It stores one `period` snapshot per period: every periodic formula's value
per person, with the rule version in force. The closed quarters 2025-Q4 to 2026-Q2 are seeded
(`S130__demo_index_history`) with each person's index from the prototype's `trend`. Appraisal
proposals and published 360° cycles freeze their own results through `SnapshotStore.freeze`. An
Admin recalculation of a closed period goes through `SnapshotStore.replace` with a reason. See
[backend-guide.md](backend-guide.md) §6.
