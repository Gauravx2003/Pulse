package com.pulse.calc.work;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.PeriodCalendar;
import com.pulse.calc.core.WorkingDays;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Workload formulas of {@code calc/work} (task 8.1): the data loaders around {@link Workload},
 * {@link PeriodWork}, {@link SprintMetrics} and {@link BugStats}. They read the Track 2 tables (V200),
 * the holidays and the person's joining date, and two optional Track 3 ports for the working-day
 * calendar; until Track 3 registers them they read as "no leave" and "no visits":
 * <ul>
 *   <li>{@code people.leaveDays {person}}: leave and absence dates, {@code ["2026-09-30", ...]};</li>
 *   <li>{@code people.visitDays {person}}: customer visits, {@code [{from, to, hours?}]}, where
 *       {@code hours} is the on-site time per day when a visit report exists.</li>
 * </ul>
 *
 * <p>Last activity of an issue is the latest transition, worklog, comment, commit or PR event by its
 * assignee, never a bot or planning event (spec work/work-items). An issue nobody has worked on yet is
 * idle since it was created or assigned; without those events, since work started, else since the first
 * day of its sprint.
 */
public final class WorkloadFormulas implements FormulaModule
{

    /**
     * Issues or their activity changed (ingest of Jira and GitHub, task 5.4).
     */
    public static final String ISSUES = "work.issues";

    /**
     * Worklogs or commit days changed.
     */
    public static final String WORKLOGS = "work.worklogs";

    /**
     * Bugs or support tickets changed.
     */
    public static final String BUGS = "work.bugs";

    public static final String LIVE_SPRINT = "work.liveSprint";

    public static final String ISSUE = "work.issue";

    public static final String PERIOD_WORK = "work.periodWork";

    public static final String SPRINT_METRICS = "work.sprintMetrics";

    public static final String UTILIZATION = "work.utilization";

    public static final String BUG_STATS = "work.bugStats";

    public static final String HOURS_PER_POINT = "work.hoursPerPoint";

    /**
     * Closed sprints the hours-per-point calibration looks back over (reference section 12).
     */
    public static final int CALIBRATION_SPRINTS = 3;

    private static final Set<String> COUNTED_ACTIVITY = Set.of("transition", "worklog", "comment", "commit", "pull_request");

    private static final String ISSUE_ROW = """
            SELECT i.id, i.key, i.status, i.issue_type AS "issueType", i.assignee_id AS "assigneeId",
                   i.story_points AS "storyPoints", i.estimate_hours AS "estimateHours", i.spent_hours AS "spentHours",
                   i.remaining_hours AS "remainingHours", i.due_on AS "dueOn", i.started_on AS "startedOn",
                   i.resolved_on AS "resolvedOn", i.carried_over AS "carriedOver", i.was_stale AS "wasStale",
                   i.reopened, s.key AS "sprintKey", s.work_ends_on AS "sprintWorkEnd",
                   to_char(coalesce(
                       (SELECT max(e.occurred_at) FROM issue_events e
                        WHERE e.issue_id = i.id AND e.actor_id = i.assignee_id AND NOT e.is_bot
                          AND e.kind = ANY($1::text[])) AT TIME ZONE 'Asia/Kolkata',
                       (SELECT max(e.occurred_at) FROM issue_events e
                        WHERE e.issue_id = i.id AND e.kind IN ('created', 'assignee_change')) AT TIME ZONE 'Asia/Kolkata',
                       coalesce(i.started_on, s.starts_on, i.created_at::date)::timestamp),
                       'YYYY-MM-DD"T"HH24:MI') AS "lastActivity"
            FROM issues i
            LEFT JOIN sprints s ON s.id = i.sprint_id
            LEFT JOIN releases r ON r.id = i.release_id
            """;

    @Override
    public String id()
    {
        return "work";
    }

    @Override
    public List<Formula> formulas()
    {
        Set<String> calendar = Set.of(CalcService.PEOPLE, CalcService.SPRINTS, WorkFormulas.HOLIDAYS,
                CalcService.importTopic("holidays"), CalcService.importTopic("attendance"), CalcService.importTopic("visits"));

        Set<String> work = union(calendar, Set.of(ISSUES, WORKLOGS));

        return List.of(
                Formula.of(HOURS_PER_POINT, Set.of("person"), Set.of(ISSUES, CalcService.SPRINTS), safe(WorkloadFormulas::hoursPerPoint)),
                Formula.of(LIVE_SPRINT, Set.of("person"), work, safe(WorkloadFormulas::liveSprint)),
                Formula.of(ISSUE, Set.of("issue"), work, safe(WorkloadFormulas::issue)),
                new Formula(PERIOD_WORK, Set.of("person", "period"), work,
                        Set.of(PeriodCalendar.SPRINT), safe(WorkloadFormulas::periodWork)),
                new Formula(SPRINT_METRICS, Set.of("person", "period"), work,
                        Set.of(PeriodCalendar.SPRINT), safe(WorkloadFormulas::sprintMetrics)),
                new Formula(UTILIZATION, Set.of("person", "period"), work,
                        Set.of(PeriodCalendar.SPRINT), safe(WorkloadFormulas::utilization)),
                Formula.of(BUG_STATS, Set.of("person", "period"), union(calendar, Set.of(BUGS)), safe(WorkloadFormulas::bugStats)));
    }

    /**
     * Turns a bad parameter thrown while reading {@code params} into a failed future (answered as 400).
     */
    private static Formula.Compute safe(Formula.Compute compute)
    {
        return (ctx, params) ->
        {
            try
            {
                return compute.apply(ctx, params);
            }
            catch (IllegalArgumentException e)
            {
                return Future.failedFuture(e);
            }
        };
    }

    // ------------------------------------------------------------------ formulas

    /**
     * {@code work.hoursPerPoint {person}}: hours spent per story point over the person's done issues of
     * the last 3 closed sprints, or null without any (remaining hours then fall back to 0).
     */
    private static Future<Object> hoursPerPoint(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        return WorkSql.row(ctx, """
                SELECT sum(i.spent_hours) AS spent, sum(i.story_points) AS points
                FROM issues i JOIN sprints s ON s.id = i.sprint_id
                WHERE i.assignee_id = $1 AND i.status = 'Done' AND i.story_points > 0
                  AND s.id IN (SELECT id FROM sprints WHERE ends_on < $2 ORDER BY ends_on DESC LIMIT $3)""",
                        Tuple.of(person, ctx.today(), CALIBRATION_SPRINTS))
                .map(row ->
                {
                    Double points = row == null ? null : Workload.number(row, "points");

                    return points == null || points == 0 ? null : Workload.orZero(Workload.number(row, "spent")) / points;
                });
    }

    /**
     * {@code work.liveSprint {person}}: {@link Workload#live} for the active sprint, with the sprint key,
     * the % of the sprint elapsed and the hours per point used; null when no sprint is active.
     */
    private static Future<Object> liveSprint(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        return ctx.calendar().compose(calendar ->
        {
            JsonObject sprint = calendar.activeSprint();

            if (sprint == null)
            {
                return Future.succeededFuture(null);
            }

            String sprintKey = sprint.getString("key").substring(2);

            Future<Days> days = days(ctx, person);

            Future<JsonArray> issues = WorkSql.rows(ctx, ISSUE_ROW + " WHERE i.assignee_id = $2 AND s.key = $3 ORDER BY i.key",
                    Tuple.of(activityKinds(), person, sprintKey));

            Future<Object> perPoint = ctx.get(HOURS_PER_POINT, params);

            return Future.all(days, issues, perPoint).map(all ->
            {
                LocalDate start = LocalDate.parse(sprint.getString("from"));

                LocalDate workEnd = LocalDate.parse(sprint.getString("end"));

                int elapsed = Workload.sprintElapsedPct(start, workEnd, ctx.today(), days.result().company());

                double hoursPerPoint = perPoint.result() instanceof Number n ? n.doubleValue() : 0;

                return (Object) Workload.live(list(issues.result()), ctx.today(), workEnd, ctx.rules(), elapsed,
                                days.result().person(), hoursPerPoint)
                        .put("sprint", sprint.getString("key"))
                        .put("elapsedPct", elapsed)
                        .put("hoursPerPoint", hoursPerPoint);
            });
        });
    }

    /**
     * {@code work.issue {issue}}: one issue's signals for Work items and the issue drawer:
     * {@code {key, flags, noEstimate, checks, remainingHours, lastActivity}}; null when it does not exist.
     */
    private static Future<Object> issue(CalcContext ctx, JsonObject params)
    {
        UUID id = uuid(params, "issue");

        return WorkSql.row(ctx, ISSUE_ROW + " WHERE i.id = $2", Tuple.of(activityKinds(), id))
                .compose(row ->
                {
                    if (row == null)
                    {
                        return Future.succeededFuture(null);
                    }

                    UUID assignee = row.getString("assigneeId") == null ? null : UUID.fromString(row.getString("assigneeId"));

                    Future<Days> days = days(ctx, assignee);

                    Future<PeriodCalendar> calendar = ctx.calendar();

                    Future<Object> perPoint = assignee == null ? Future.succeededFuture(null)
                            : ctx.get(HOURS_PER_POINT, new JsonObject().put("person", assignee.toString()));

                    return Future.all(days, calendar, perPoint).map(all ->
                    {
                        int elapsed = elapsedOf(calendar.result(), row, ctx.today(), days.result().company());

                        double hoursPerPoint = perPoint.result() instanceof Number n ? n.doubleValue() : 0;

                        return (Object) new JsonObject()
                                .put("key", row.getString("key"))
                                .put("flags", Workload.flags(row, ctx.today(), ctx.rules(), elapsed, days.result().person()))
                                .put("noEstimate", Workload.noEstimate(row))
                                .put("checks", Workload.checks(row, ctx.today(), ctx.rules(), days.result().person()))
                                .put("remainingHours", Workload.remainingHours(row, hoursPerPoint))
                                .put("lastActivity", row.getString("lastActivity"));
                    });
                });
    }

    /**
     * {@code work.periodWork {person, period}}: {@link PeriodWork#stats} from the day facts of the period.
     */
    private static Future<Object> periodWork(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        return period(ctx, params).compose(period -> dayFacts(ctx, person, period)
                .map(facts -> (Object) PeriodWork.stats(facts, ctx.today(), ctx.rules())
                        .put("period", period.getString("key"))));
    }

    /**
     * {@code work.sprintMetrics {person, period}}: {@link SprintMetrics#of} over the person's issues of a
     * sprint, month or release, plus {@code reopenRate}.
     */
    private static Future<Object> sprintMetrics(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        return period(ctx, params).compose(period -> ctx.calendar().compose(calendar ->
        {
            JsonObject active = calendar.activeSprint();

            String activeKey = active == null ? null : active.getString("key").substring(2);

            Future<JsonArray> rows = issuesIn(ctx, person, period, active);

            Future<Object> work = ctx.get(PERIOD_WORK, params);

            Future<Days> days = days(ctx, person);

            return Future.all(rows, work, days).map(all ->
            {
                int elapsed = active == null ? 0 : Workload.sprintElapsedPct(LocalDate.parse(active.getString("from")),
                        LocalDate.parse(active.getString("end")), ctx.today(), days.result().company());

                List<JsonObject> issues = new ArrayList<>();

                for (JsonObject row : list(rows.result()))
                {
                    boolean live = activeKey != null && activeKey.equals(row.getString("sprintKey"));

                    boolean done = "Done".equals(row.getString("status"));

                    Integer cycle = done ? SprintMetrics.cycleDays(date(row, "startedOn"), date(row, "resolvedOn"),
                            days.result().company()) : null;

                    boolean stale = live
                            ? Workload.flags(row, ctx.today(), ctx.rules(), elapsed, days.result().person()).stream()
                                    .anyMatch(f -> "Stale".equals(((JsonArray) f).getString(1)))
                            : row.getBoolean("wasStale", false);

                    issues.add(row.copy().put("cycle", cycle).put("live", live).put("wasStale", stale));
                }

                return (Object) SprintMetrics.of(issues, (JsonObject) work.result())
                        .put("reopenRate", SprintMetrics.reopenRate(issues))
                        .put("period", period.getString("key"));
            });
        }));
    }

    /**
     * {@code work.utilization {person, period}}: the prototype's {@code utilOf}: the live sprint's
     * utilization for the active sprint, otherwise hours logged / (working days x day hours).
     */
    private static Future<Object> utilization(CalcContext ctx, JsonObject params)
    {
        return period(ctx, params).compose(period ->
        {
            if (PeriodCalendar.SPRINT.equals(period.getString("kind")) && period.getBoolean("live", false))
            {
                return ctx.get(LIVE_SPRINT, new JsonObject().put("person", params.getString("person")))
                        .map(live -> live instanceof JsonObject l ? l.getValue("util") : null);
            }

            return ctx.get(PERIOD_WORK, params).map(work -> ((JsonObject) work).getValue("cover"));
        });
    }

    /**
     * {@code work.bugStats {person, period}}: {@link BugStats#of} with this quarter's bugs and the
     * period's support tickets.
     */
    private static Future<Object> bugStats(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        return period(ctx, params).compose(period ->
        {
            Future<JsonArray> bugs = WorkSql.rows(ctx, """
                    SELECT key, origin_person_id AS "originPersonId", status, severity, environment,
                           reported_on AS "reportedOn", fixed_by_id AS "fixedById", proactive
                    FROM bugs WHERE origin_person_id = $1 OR fixed_by_id = $1""", Tuple.of(person));

            Future<JsonArray> tickets = WorkSql.rows(ctx, """
                    SELECT key, person_id AS "personId", opened_on AS "openedOn", hours, csat
                    FROM support_tickets WHERE person_id = $1""", Tuple.of(person));

            return Future.all(bugs, tickets).map(all ->
            {
                LocalDate today = ctx.today();

                LocalDate quarterStart = LocalDate.of(today.getYear(), (today.getMonthValue() - 1) / 3 * 3 + 1, 1);

                return (Object) BugStats.of(person.toString(), list(bugs.result()), list(tickets.result()), quarterStart,
                        LocalDate.parse(period.getString("from")), LocalDate.parse(period.getString("to")))
                        .put("period", period.getString("key"));
            });
        });
    }

    // ------------------------------------------------------------------ loaders

    /**
     * The person's and the company's working-day predicates.
     */
    private record Days(Predicate<LocalDate> person, Predicate<LocalDate> company, JsonObject profile, Set<LocalDate> holidays)
    {
    }

    private static Future<Days> days(CalcContext ctx, UUID person)
    {
        Future<JsonArray> holidays = WorkSql.rows(ctx, "SELECT holiday_on AS date, name, location FROM holidays");

        Future<JsonObject> row = person == null ? Future.succeededFuture(null)
                : WorkSql.row(ctx, "SELECT joined_on AS \"joinedOn\" FROM people WHERE id = $1", Tuple.of(person));

        JsonObject ref = new JsonObject().put("person", person == null ? null : person.toString());

        Future<Object> leave = person == null ? Future.succeededFuture(null) : ctx.optional("people.leaveDays", ref);

        Future<Object> visits = person == null ? Future.succeededFuture(null) : ctx.optional("people.visitDays", ref);

        return Future.all(holidays, row, leave, visits).map(all ->
        {
            Set<LocalDate> company = WorkingDays.holidays(holidays.result(), null);

            JsonObject profile = new JsonObject()
                    .put("joinedOn", row.result() == null ? null : row.result().getString("joinedOn"))
                    .put("leave", leave.result() instanceof JsonArray l ? l : new JsonArray())
                    .put("visits", visits.result() instanceof JsonArray v ? v : new JsonArray());

            Predicate<LocalDate> personal = day -> WorkingDays.isWorking(day, company, profile);

            Predicate<LocalDate> companyWide = day -> WorkingDays.isWorking(day, company, null);

            return new Days(person == null ? companyWide : personal, companyWide, profile, company);
        });
    }

    /**
     * One fact per day of the period, as {@link PeriodWork} reads them.
     */
    private static Future<JsonArray> dayFacts(CalcContext ctx, UUID person, JsonObject period)
    {
        LocalDate from = LocalDate.parse(period.getString("from"));

        LocalDate to = LocalDate.parse(period.getString("to"));

        Future<Days> days = days(ctx, person);

        Future<JsonArray> worklogs = WorkSql.rows(ctx, """
                SELECT work_date AS day, sum(hours) AS hours FROM worklogs
                WHERE person_id = $1 AND work_date BETWEEN $2 AND $3 GROUP BY work_date""", Tuple.of(person, from, to));

        Future<JsonArray> commits = WorkSql.rows(ctx, """
                SELECT commit_date AS day FROM commit_days
                WHERE person_id = $1 AND commit_date BETWEEN $2 AND $3 AND commit_count > 0""", Tuple.of(person, from, to));

        return Future.all(days, worklogs, commits).map(all ->
        {
            Map<String, Double> hours = new HashMap<>();

            for (JsonObject w : list(worklogs.result()))
            {
                hours.put(w.getString("day"), Workload.orZero(Workload.number(w, "hours")));
            }

            Set<String> committed = new HashSet<>();

            for (JsonObject c : list(commits.result()))
            {
                committed.add(c.getString("day"));
            }

            JsonObject profile = days.result().profile();

            LocalDate joined = profile.getString("joinedOn") == null ? null : LocalDate.parse(profile.getString("joinedOn"));

            JsonArray out = new JsonArray();

            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1))
            {
                String key = day.toString();

                JsonObject visit = visitOn(profile.getJsonArray("visits"), day);

                double logged = hours.getOrDefault(key, 0.0);

                if (visit != null && visit.getValue("hours") instanceof Number onSite)
                {
                    logged = onSite.doubleValue();
                }

                out.add(new JsonObject()
                        .put("date", key)
                        .put("weekday", day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY)
                        .put("holiday", days.result().holidays().contains(day))
                        .put("leave", profile.getJsonArray("leave").contains(key))
                        .put("visit", visit != null)
                        .put("beforeJoining", joined != null && day.isBefore(joined))
                        .put("hours", logged)
                        .put("commits", committed.contains(key)));
            }

            return out;
        });
    }

    /**
     * The person's issues in a sprint, a release or a month. A month holds the issues resolved in it,
     * those of closed sprints ending in it that were never resolved, and every issue of the active
     * sprint when the month overlaps it (the prototype's {@code issuesIn}).
     */
    private static Future<JsonArray> issuesIn(CalcContext ctx, UUID person, JsonObject period, JsonObject active)
    {
        String kind = period.getString("kind");

        String key = period.getString("key").substring(2);

        if (PeriodCalendar.SPRINT.equals(kind))
        {
            return WorkSql.rows(ctx, ISSUE_ROW + " WHERE i.assignee_id = $2 AND s.key = $3 ORDER BY i.key",
                    Tuple.of(activityKinds(), person, key));
        }

        if (PeriodCalendar.RELEASE.equals(kind))
        {
            return WorkSql.rows(ctx, ISSUE_ROW + " WHERE i.assignee_id = $2 AND r.key = $3 ORDER BY i.key",
                    Tuple.of(activityKinds(), person, key));
        }

        LocalDate from = LocalDate.parse(period.getString("from"));

        LocalDate to = LocalDate.parse(period.getString("to"));

        String activeKey = active == null ? null : active.getString("key").substring(2);

        boolean overlapsActive = active != null && !LocalDate.parse(active.getString("from")).isAfter(to)
                && !LocalDate.parse(active.getString("to")).isBefore(from);

        return WorkSql.rows(ctx, ISSUE_ROW + """
                 WHERE i.assignee_id = $2
                   AND ((s.key IS DISTINCT FROM $5 AND coalesce(i.resolved_on, s.work_ends_on) BETWEEN $3 AND $4)
                        OR ($6 AND s.key = $5))
                ORDER BY i.key""",
                Tuple.of(activityKinds(), person, from, to).addString(activeKey).addBoolean(overlapsActive));
    }

    // ------------------------------------------------------------------ helpers

    private static int elapsedOf(PeriodCalendar calendar, JsonObject issue, LocalDate today, Predicate<LocalDate> company)
    {
        JsonObject sprint = calendar.activeSprint();

        if (sprint == null || !sprint.getString("key").substring(2).equals(issue.getString("sprintKey")))
        {
            return 0;
        }

        return Workload.sprintElapsedPct(LocalDate.parse(sprint.getString("from")), LocalDate.parse(sprint.getString("end")),
                today, company);
    }

    private static Future<JsonObject> period(CalcContext ctx, JsonObject params)
    {
        return ctx.calendar().map(calendar -> calendar.period(params.getString("period")));
    }

    private static JsonObject visitOn(JsonArray visits, LocalDate day)
    {
        for (Object item : visits)
        {
            JsonObject visit = (JsonObject) item;

            if (!day.isBefore(LocalDate.parse(visit.getString("from"))) && !day.isAfter(LocalDate.parse(visit.getString("to"))))
            {
                return visit;
            }
        }

        return null;
    }

    private static String[] activityKinds()
    {
        return COUNTED_ACTIVITY.toArray(String[]::new);
    }

    private static List<JsonObject> list(JsonArray rows)
    {
        List<JsonObject> out = new ArrayList<>();

        rows.forEach(row -> out.add((JsonObject) row));

        return out;
    }

    private static LocalDate date(JsonObject row, String key)
    {
        String value = row.getString(key);

        return value == null ? null : LocalDate.parse(value.substring(0, 10));
    }

    private static Set<String> union(Set<String> a, Set<String> b)
    {
        Set<String> out = new HashSet<>(a);

        out.addAll(b);

        return out;
    }

    private static UUID uuid(JsonObject params, String name)
    {
        String value = params.getString(name);

        if (value == null)
        {
            throw new IllegalArgumentException(name + ": An id is required.");
        }

        try
        {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException e)
        {
            throw new IllegalArgumentException(name + ": '" + value + "' is not an id.");
        }
    }
}
