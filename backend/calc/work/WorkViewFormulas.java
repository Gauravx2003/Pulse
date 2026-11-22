package com.pulse.calc.work;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.PeriodCalendar;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Overview and Work items formulas of {@code calc/work} (task 8.2): the active sprint's issues with
 * their signals, a period's issues, the daily load, utilization by sprint, the sprints of a period, the
 * release card and the overview alert. Onboarding comes from Track 3's optional port
 * {@code people.onboarding {person}} ({@code {plan, progress, mentor}} while on an active onboarding
 * plan, null otherwise); until it exists nobody is on onboarding.
 */
public final class WorkViewFormulas implements FormulaModule
{

    public static final String LIVE_ISSUES = "work.liveIssues";

    public static final String PERIOD_ISSUES = "work.periodIssues";

    public static final String DAILY_LOAD = "work.dailyLoad";

    public static final String UTILIZATION_TREND = "work.utilizationTrend";

    public static final String SPRINT_BREAKDOWN = "work.sprintBreakdown";

    public static final String RELEASE_CARD = "work.releaseCard";

    public static final String LIVE_ALERT = "work.liveAlert";

    public static final String ONBOARDING = "work.onboarding";

    /**
     * Sprints shown in "Utilization by sprint" (the prototype's last 8).
     */
    public static final int TREND_SPRINTS = 8;

    @Override
    public String id()
    {
        return "work";
    }

    @Override
    public List<Formula> formulas()
    {
        Set<String> topics = Set.of(WorkloadFormulas.ISSUES, WorkloadFormulas.WORKLOGS, CalcService.PEOPLE, CalcService.SPRINTS,
                WorkFormulas.HOLIDAYS, CalcService.importTopic("holidays"), CalcService.importTopic("attendance"),
                CalcService.importTopic("visits"));

        return List.of(
                Formula.of(LIVE_ISSUES, Set.of("person"), topics, WorkloadFormulas.safe(WorkViewFormulas::liveIssues)),
                Formula.of(PERIOD_ISSUES, Set.of("person", "period"), topics, WorkloadFormulas.safe(WorkViewFormulas::periodIssues)),
                Formula.of(DAILY_LOAD, Set.of("person"), topics, WorkloadFormulas.safe(WorkViewFormulas::dailyLoad)),
                Formula.of(UTILIZATION_TREND, Set.of("person"), topics, WorkloadFormulas.safe(WorkViewFormulas::utilizationTrend)),
                Formula.of(SPRINT_BREAKDOWN, Set.of("person", "period"), topics, WorkloadFormulas.safe(WorkViewFormulas::sprintBreakdown)),
                Formula.of(RELEASE_CARD, Set.of("person", "period"), topics, WorkloadFormulas.safe(WorkViewFormulas::releaseCard)),
                Formula.of(LIVE_ALERT, Set.of("person"), topics, WorkloadFormulas.safe(WorkViewFormulas::liveAlert)),
                Formula.of(ONBOARDING, Set.of("person"), topics, WorkloadFormulas.safe(WorkViewFormulas::onboarding)));
    }

    // ------------------------------------------------------------------ formulas

    /**
     * {@code work.liveIssues {person}}: the active sprint's issues of the person with their flags, "no
     * estimate", remaining hours (with the fallback chain), last activity and idle working days, plus the
     * sprint's elapsed % and the company working days left; {@code items} is empty with no active sprint.
     */
    private static Future<Object> liveIssues(CalcContext ctx, JsonObject params)
    {
        UUID person = WorkloadFormulas.uuid(params, "person");

        return ctx.calendar().compose(calendar ->
        {
            JsonObject sprint = calendar.activeSprint();

            if (sprint == null)
            {
                return Future.succeededFuture(new JsonObject().putNull("sprint").put("items", new JsonArray()));
            }

            Future<JsonArray> rows = WorkSql.rows(ctx, WorkloadFormulas.ISSUE_ROW + " WHERE i.assignee_id = $2 AND s.key = $3 ORDER BY i.key",
                    Tuple.of(WorkloadFormulas.activityKinds(), person, sprint.getString("key").substring(2)));

            Future<WorkloadFormulas.Days> days = WorkloadFormulas.days(ctx, person);

            Future<Object> perPoint = ctx.get(WorkloadFormulas.HOURS_PER_POINT, params);

            return Future.all(rows, days, perPoint).map(all ->
            {
                LocalDate today = ctx.today();

                LocalDate workEnd = LocalDate.parse(sprint.getString("end"));

                int elapsed = Workload.sprintElapsedPct(LocalDate.parse(sprint.getString("from")), workEnd, today,
                        days.result().company());

                double hoursPerPoint = perPoint.result() instanceof Number n ? n.doubleValue() : 0;

                JsonArray items = new JsonArray();

                for (JsonObject row : WorkloadFormulas.list(rows.result()))
                {
                    LocalDate last = LocalDate.parse(row.getString("lastActivity").substring(0, 10));

                    items.add(row.copy()
                            .put("flags", Workload.flags(row, today, ctx.rules(), elapsed, days.result().person()))
                            .put("noEstimate", Workload.noEstimate(row))
                            .put("remainingHours", Workload.remainingHours(row, hoursPerPoint))
                            .put("idleDays", Workload.idleDays(last, today, days.result().person())));
                }

                return (Object) new JsonObject()
                        .put("sprint", sprint.getString("key"))
                        .put("elapsedPct", elapsed)
                        .put("workingDaysLeft", Workload.count(today, workEnd, days.result().company()))
                        .put("items", items);
            });
        });
    }

    /**
     * {@code work.periodIssues {person, period}}: the person's issues of a sprint, month or release (the
     * prototype's {@code issuesIn}) with cycle time, whether they belong to the active sprint and whether
     * they went stale.
     */
    private static Future<Object> periodIssues(CalcContext ctx, JsonObject params)
    {
        UUID person = WorkloadFormulas.uuid(params, "person");

        return WorkloadFormulas.period(ctx, params).compose(period -> ctx.calendar().compose(calendar ->
        {
            JsonObject active = calendar.activeSprint();

            String activeKey = active == null ? null : active.getString("key").substring(2);

            Future<JsonArray> rows = WorkloadFormulas.issuesIn(ctx, person, period, active);

            Future<WorkloadFormulas.Days> days = WorkloadFormulas.days(ctx, person);

            return Future.all(rows, days).map(all ->
            {
                int elapsed = active == null ? 0 : Workload.sprintElapsedPct(LocalDate.parse(active.getString("from")),
                        LocalDate.parse(active.getString("end")), ctx.today(), days.result().company());

                JsonArray items = new JsonArray();

                for (JsonObject row : WorkloadFormulas.list(rows.result()))
                {
                    boolean live = activeKey != null && activeKey.equals(row.getString("sprintKey"));

                    boolean done = "Done".equals(row.getString("status"));

                    Integer cycle = done ? SprintMetrics.cycleDays(WorkloadFormulas.date(row, "startedOn"),
                            WorkloadFormulas.date(row, "resolvedOn"), days.result().company()) : null;

                    boolean stale = live
                            ? Workload.flags(row, ctx.today(), ctx.rules(), elapsed, days.result().person()).stream()
                                    .anyMatch(f -> "Stale".equals(((JsonArray) f).getString(1)))
                            : row.getBoolean("wasStale", false);

                    items.add(row.copy().put("cycle", cycle).put("live", live).put("wasStale", stale));
                }

                return (Object) new JsonObject().put("period", period.getString("key")).put("items", items);
            });
        }));
    }

    /**
     * {@code work.dailyLoad {person}}: {@link WorkViews#dailyLoad} of the active sprint; null with none.
     */
    private static Future<Object> dailyLoad(CalcContext ctx, JsonObject params)
    {
        UUID person = WorkloadFormulas.uuid(params, "person");

        return ctx.calendar().compose(calendar ->
        {
            JsonObject sprint = calendar.activeSprint();

            if (sprint == null)
            {
                return Future.succeededFuture(null);
            }

            LocalDate from = LocalDate.parse(sprint.getString("from"));

            LocalDate workEnd = LocalDate.parse(sprint.getString("end"));

            Future<WorkloadFormulas.Days> days = WorkloadFormulas.days(ctx, person);

            Future<JsonArray> logged = WorkSql.rows(ctx, """
                    SELECT work_date AS day, sum(hours) AS hours FROM worklogs
                    WHERE person_id = $1 AND work_date BETWEEN $2 AND $3 GROUP BY work_date""", Tuple.of(person, from, workEnd));

            Future<Object> live = ctx.get(WorkloadFormulas.LIVE_SPRINT, params);

            return Future.all(days, logged, live).map(all ->
            {
                Map<String, Double> hours = new HashMap<>();

                for (JsonObject row : WorkloadFormulas.list(logged.result()))
                {
                    hours.put(row.getString("day"), Workload.orZero(Workload.number(row, "hours")));
                }

                List<LocalDate> sprintDays = new ArrayList<>();

                for (LocalDate day = from; !day.isAfter(workEnd); day = day.plusDays(1))
                {
                    if (days.result().company().test(day))
                    {
                        sprintDays.add(day);
                    }
                }

                JsonObject profile = days.result().profile();

                Set<String> leave = new HashSet<>();

                profile.getJsonArray("leave").forEach(d -> leave.add(String.valueOf(d)));

                JsonObject metrics = (JsonObject) live.result();

                return (Object) new JsonObject()
                        .put("sprint", sprint.getString("key"))
                        .put("focus", ctx.rules().getDouble("focus", 6.0))
                        .put("days", WorkViews.dailyLoad(sprintDays, ctx.today(), hours, metrics.getDouble("rem"),
                                metrics.getInteger("cap"), days.result().person(), leave, visitDays(profile.getJsonArray("visits"))));
            });
        });
    }

    /**
     * {@code work.utilizationTrend {person}}: {@link WorkViews#utilizationTrend} over the last 8 sprints,
     * leaving out sprints that ended before the person joined.
     */
    private static Future<Object> utilizationTrend(CalcContext ctx, JsonObject params)
    {
        UUID person = WorkloadFormulas.uuid(params, "person");

        Future<PeriodCalendar> calendar = ctx.calendar();

        Future<WorkloadFormulas.Days> days = WorkloadFormulas.days(ctx, person);

        return Future.all(calendar, days).compose(all ->
        {
            String joined = days.result().profile().getString("joinedOn");

            JsonArray sprints = calendar.result().sprints();

            List<JsonObject> chosen = new ArrayList<>();

            for (int i = 0; i < Math.min(TREND_SPRINTS, sprints.size()); i++)
            {
                JsonObject sprint = sprints.getJsonObject(i);

                if (joined == null || !LocalDate.parse(sprint.getString("end")).isBefore(LocalDate.parse(joined)))
                {
                    chosen.add(0, sprint);
                }
            }

            List<Future<Object>> utils = new ArrayList<>();

            for (JsonObject sprint : chosen)
            {
                utils.add(ctx.get(WorkloadFormulas.UTILIZATION, params.copy().put("period", sprint.getString("key"))));
            }

            return Future.all(utils).map(done ->
            {
                JsonArray rows = new JsonArray();

                for (int i = 0; i < chosen.size(); i++)
                {
                    JsonObject sprint = chosen.get(i);

                    rows.add(new JsonObject()
                            .put("period", sprint.getString("key"))
                            .put("short", sprint.getString("short"))
                            .put("util", utils.get(i).result())
                            .put("live", sprint.getBoolean("live", false)));
                }

                return (Object) WorkViews.utilizationTrend(rows, ctx.rules());
            });
        });
    }

    /**
     * {@code work.sprintBreakdown {person, period}}: one row per sprint of the period (the sprint
     * itself, the sprints of a release, or those overlapping a month), newest first: issues, committed,
     * done, commitment, carried over, hours logged, utilization and the sprint's release.
     */
    private static Future<Object> sprintBreakdown(CalcContext ctx, JsonObject params)
    {
        Future<JsonObject> period = WorkloadFormulas.period(ctx, params);

        Future<PeriodCalendar> calendar = ctx.calendar();

        Future<JsonArray> releases = WorkSql.rows(ctx, "SELECT key, starts_on AS \"from\", ends_on AS \"to\" FROM releases");

        return Future.all(period, calendar, releases).compose(all ->
        {
            List<JsonObject> sprints = sprintsOf(period.result(), calendar.result().sprints(), releases.result());

            List<Future<Object>> metrics = new ArrayList<>();

            List<Future<Object>> utils = new ArrayList<>();

            List<Future<Object>> issues = new ArrayList<>();

            for (JsonObject sprint : sprints)
            {
                JsonObject p = params.copy().put("period", sprint.getString("key"));

                metrics.add(ctx.get(WorkloadFormulas.SPRINT_METRICS, p));

                utils.add(ctx.get(WorkloadFormulas.UTILIZATION, p));

                issues.add(ctx.get(PERIOD_ISSUES, p));
            }

            List<Future<Object>> everything = new ArrayList<>(metrics);

            everything.addAll(utils);

            everything.addAll(issues);

            return Future.all(everything).map(done ->
            {
                JsonArray rows = new JsonArray();

                for (int i = 0; i < sprints.size(); i++)
                {
                    JsonObject sprint = sprints.get(i);

                    JsonObject m = (JsonObject) metrics.get(i).result();

                    JsonArray list = ((JsonObject) issues.get(i).result()).getJsonArray("items");

                    double carriedPoints = 0;

                    for (Object item : list)
                    {
                        JsonObject issue = (JsonObject) item;

                        if (issue.getBoolean("carriedOver", false))
                        {
                            carriedPoints += Workload.orZero(Workload.number(issue, "storyPoints"));
                        }
                    }

                    rows.add(new JsonObject()
                            .put("period", sprint.getString("key"))
                            .put("short", sprint.getString("short"))
                            .put("from", sprint.getString("from"))
                            .put("end", sprint.getString("end"))
                            .put("live", sprint.getBoolean("live", false))
                            .put("release", releaseOf(sprint, releases.result()))
                            .put("issues", list.size())
                            .put("committed", m.getValue("com"))
                            .put("done", m.getValue("dsp"))
                            .put("commitment", m.getValue("commit"))
                            .put("carried", m.getJsonArray("carry").size())
                            .put("carriedPoints", carriedPoints)
                            .put("logged", m.getValue("logged"))
                            .put("util", utils.get(i).result()));
                }

                return (Object) new JsonObject().put("period", period.result().getString("key")).put("sprints", rows);
            });
        });
    }

    /**
     * {@code work.releaseCard {person, period}} for a release: its name, dates and status, working days
     * left until the release date, its sprints, the person's and the team's story points done and the
     * person's share; null for any other period.
     */
    private static Future<Object> releaseCard(CalcContext ctx, JsonObject params)
    {
        UUID person = WorkloadFormulas.uuid(params, "person");

        return WorkloadFormulas.period(ctx, params).compose(period ->
        {
            if (!PeriodCalendar.RELEASE.equals(period.getString("kind")))
            {
                return Future.succeededFuture(null);
            }

            String key = period.getString("key").substring(2);

            Future<JsonObject> release = WorkSql.row(ctx, """
                    SELECT key, name, starts_on AS "from", ends_on AS "to", release_date AS "releaseDate", status
                    FROM releases WHERE key = $1 ORDER BY synced_at DESC NULLS LAST LIMIT 1""", Tuple.of(key));

            Future<JsonArray> team = WorkSql.rows(ctx, """
                    SELECT t.id FROM people t JOIN people p ON p.id = $1
                    WHERE t.department IS NOT DISTINCT FROM p.department ORDER BY t.name, t.id""", Tuple.of(person));

            Future<PeriodCalendar> calendar = ctx.calendar();

            Future<WorkloadFormulas.Days> days = WorkloadFormulas.days(ctx, null);

            return Future.all(release, team, calendar, days).compose(all ->
            {
                List<Future<Object>> metrics = new ArrayList<>();

                for (JsonObject member : WorkloadFormulas.list(team.result()))
                {
                    metrics.add(ctx.get(WorkloadFormulas.SPRINT_METRICS, new JsonObject().put("person", member.getString("id"))
                            .put("period", period.getString("key"))));
                }

                return Future.all(metrics).map(done ->
                {
                    double teamDone = 0;

                    double mine = 0;

                    List<JsonObject> members = WorkloadFormulas.list(team.result());

                    for (int i = 0; i < members.size(); i++)
                    {
                        double points = Workload.orZero(Workload.number((JsonObject) metrics.get(i).result(), "dsp"));

                        teamDone += points;

                        if (person.toString().equals(members.get(i).getString("id")))
                        {
                            mine = points;
                        }
                    }

                    JsonObject r = release.result();

                    JsonArray sprints = new JsonArray();

                    for (JsonObject sprint : sprintsOf(period, calendar.result().sprints(), new JsonArray().add(r)))
                    {
                        sprints.add(sprint.getString("key"));
                    }

                    boolean released = "Released".equals(r.getString("status"));

                    LocalDate releaseDate = r.getString("releaseDate") == null ? LocalDate.parse(r.getString("to"))
                            : LocalDate.parse(r.getString("releaseDate"));

                    return (Object) r.copy()
                            .put("workingDaysLeft", released ? 0 : Workload.count(ctx.today(), releaseDate, days.result().company()))
                            .put("sprints", new JsonArray(sprints.getList().reversed()))
                            .put("personDone", mine)
                            .put("teamDone", teamDone)
                            .put("share", WorkViews.releaseShare(mine, teamDone));
                });
            });
        });
    }

    /**
     * {@code work.liveAlert {person}}: {@link WorkViews#alert} for the active sprint, with the person's
     * upcoming leave and the biggest To Do issue; null when no alert applies or no sprint is active.
     */
    private static Future<Object> liveAlert(CalcContext ctx, JsonObject params)
    {
        UUID person = WorkloadFormulas.uuid(params, "person");

        Future<Object> live = ctx.get(WorkloadFormulas.LIVE_SPRINT, params);

        Future<Object> issues = ctx.get(LIVE_ISSUES, params);

        Future<Object> onboarding = ctx.get(ONBOARDING, params);

        Future<WorkloadFormulas.Days> days = WorkloadFormulas.days(ctx, person);

        return Future.all(live, issues, onboarding, days).map(all ->
        {
            if (!(live.result() instanceof JsonObject metrics))
            {
                return null;
            }

            List<String> leave = new ArrayList<>();

            for (Object day : days.result().profile().getJsonArray("leave"))
            {
                if (!LocalDate.parse(String.valueOf(day)).isBefore(ctx.today()))
                {
                    leave.add(String.valueOf(day));
                }
            }

            leave.sort(null);

            JsonObject biggest = WorkloadFormulas.list(((JsonObject) issues.result()).getJsonArray("items")).stream()
                    .filter(issue -> "To Do".equals(issue.getString("status")))
                    .max(Comparator.comparingDouble(issue -> issue.getDouble("remainingHours")))
                    .map(issue -> new JsonObject()
                            .put("key", issue.getString("key"))
                            .put("storyPoints", issue.getValue("storyPoints"))
                            .put("remainingHours", issue.getValue("remainingHours")))
                    .orElse(null);

            JsonObject plan = onboarding.result() instanceof JsonObject o ? o : null;

            return WorkViews.alert(metrics, plan, leave, biggest, ctx.rules());
        });
    }

    /**
     * {@code work.onboarding {person}}: Track 3's onboarding plan of the person, or null.
     */
    private static Future<Object> onboarding(CalcContext ctx, JsonObject params)
    {
        return ctx.optional("people.onboarding", params).map(plan -> plan instanceof JsonObject o ? o : null);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The sprints of a period, newest first: the sprint itself, the sprints starting inside a release, or
     * the sprints overlapping a month.
     */
    private static List<JsonObject> sprintsOf(JsonObject period, JsonArray sprints, JsonArray releases)
    {
        String kind = period.getString("kind");

        List<JsonObject> out = new ArrayList<>();

        for (Object item : sprints)
        {
            JsonObject sprint = (JsonObject) item;

            if (PeriodCalendar.SPRINT.equals(kind))
            {
                if (sprint.getString("key").equals(period.getString("key")))
                {
                    out.add(sprint);
                }
            }
            else if (PeriodCalendar.RELEASE.equals(kind))
            {
                String release = period.getString("key").substring(2);

                if (release.equals(releaseOf(sprint, releases)))
                {
                    out.add(sprint);
                }
            }
            else if (!LocalDate.parse(sprint.getString("from")).isAfter(LocalDate.parse(period.getString("to")))
                    && !LocalDate.parse(sprint.getString("to")).isBefore(LocalDate.parse(period.getString("from"))))
            {
                out.add(sprint);
            }
        }

        out.sort(Comparator.comparing((JsonObject s) -> s.getString("from")).reversed());

        return out;
    }

    /**
     * The release a sprint belongs to: the one whose dates contain the sprint's first day.
     */
    private static String releaseOf(JsonObject sprint, JsonArray releases)
    {
        LocalDate from = LocalDate.parse(sprint.getString("from"));

        for (Object item : releases)
        {
            JsonObject release = (JsonObject) item;

            if (!from.isBefore(LocalDate.parse(release.getString("from"))) && !from.isAfter(LocalDate.parse(release.getString("to"))))
            {
                return release.getString("key");
            }
        }

        return null;
    }

    private static Set<String> visitDays(JsonArray visits)
    {
        Set<String> out = new HashSet<>();

        for (Object item : visits)
        {
            JsonObject visit = (JsonObject) item;

            for (LocalDate day = LocalDate.parse(visit.getString("from")); !day.isAfter(LocalDate.parse(visit.getString("to")));
                 day = day.plusDays(1))
            {
                out.add(day.toString());
            }
        }

        return out;
    }
}
