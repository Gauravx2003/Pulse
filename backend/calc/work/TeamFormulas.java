package com.pulse.calc.work;

import com.pulse.calc.core.Appraisal;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Team utilization formulas of {@code calc/work} (task 9.1): the loaders around {@link TeamViews}. The
 * caller passes the people the viewer may see as {@code people}, comma-separated ids; the formulas
 * never widen that set.
 * <ul>
 *   <li>{@code work.teamLive {people}}: the active sprint's KPIs, rebalance suggestion, heatmap and
 *       people table; null without an active sprint;</li>
 *   <li>{@code work.teamPeriod {people, period}}: a closed sprint, a month or a release: KPIs, people
 *       table, the release card and team utilization by sprint.</li>
 * </ul>
 */
public final class TeamFormulas implements FormulaModule
{

    public static final String TEAM_LIVE = "work.teamLive";

    public static final String TEAM_PERIOD = "work.teamPeriod";

    /**
     * Skills matrix changed (Road map, task 15.2): drops the rebalance match.
     */
    public static final String SKILLS = "work.skills";

    /**
     * A joiner counts as new (never under-used in a closed period) for this many months.
     */
    public static final int NEW_JOINER_MONTHS = 3;

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
                CalcService.importTopic("visits"), SKILLS);

        return List.of(
                Formula.of(TEAM_LIVE, Set.of("people"), topics, WorkloadFormulas.safe(TeamFormulas::teamLive)),
                Formula.of(TEAM_PERIOD, Set.of("people", "period"), topics, WorkloadFormulas.safe(TeamFormulas::teamPeriod)));
    }

    // ------------------------------------------------------------------ live sprint

    private static Future<Object> teamLive(CalcContext ctx, JsonObject params)
    {
        List<UUID> ids = ids(params);

        return ctx.calendar().compose(calendar ->
        {
            JsonObject sprint = calendar.activeSprint();

            if (sprint == null)
            {
                return Future.succeededFuture(null);
            }

            String quarter = "q:Q" + ((ctx.today().getMonthValue() - 1) / 3 + 1);

            Future<Map<String, JsonObject>> people = people(ctx, ids);

            List<Future<Object>> live = new ArrayList<>();

            List<Future<Object>> onboarding = new ArrayList<>();

            List<Future<Object>> heat = new ArrayList<>();

            List<Future<Object>> issues = new ArrayList<>();

            List<Future<Object>> discipline = new ArrayList<>();

            List<Future<Object>> index = new ArrayList<>();

            for (UUID id : ids)
            {
                JsonObject person = new JsonObject().put("person", id.toString());

                live.add(ctx.get(WorkloadFormulas.LIVE_SPRINT, person));

                onboarding.add(ctx.get(WorkViewFormulas.ONBOARDING, person));

                heat.add(ctx.get(WorkViewFormulas.HEATMAP_ROW, person));

                issues.add(ctx.get(WorkViewFormulas.LIVE_ISSUES, person));

                discipline.add(ctx.optional("core.discipline", person));

                index.add(ctx.optional("core.index", person.copy().put("period", quarter)));
            }

            List<Future<?>> everything = new ArrayList<>(List.of(people));

            everything.addAll(live);

            everything.addAll(onboarding);

            everything.addAll(heat);

            everything.addAll(issues);

            everything.addAll(discipline);

            everything.addAll(index);

            return Future.all(everything).map(done ->
            {
                JsonObject rules = ctx.rules();

                List<JsonObject> rows = new ArrayList<>();

                Map<String, List<JsonObject>> toDo = new HashMap<>();

                Map<String, JsonObject> heatRows = new HashMap<>();

                Map<String, JsonObject> extras = new HashMap<>();

                for (int i = 0; i < ids.size(); i++)
                {
                    String id = ids.get(i).toString();

                    JsonObject person = people.result().getOrDefault(id, new JsonObject().put("id", id));

                    JsonObject metrics = object(live.get(i).result());

                    if (metrics == null)
                    {
                        continue;
                    }

                    boolean onPlan = object(onboarding.get(i).result()) != null;

                    rows.add(person.copy()
                            .put("util", metrics.getValue("util"))
                            .put("work", metrics.getValue("work"))
                            .put("cap", metrics.getValue("cap"))
                            .put("band", metrics.getValue("band"))
                            .put("open", metrics.getJsonArray("open", new JsonArray()).size())
                            .put("stale", metrics.getJsonArray("stale", new JsonArray()).size())
                            .put("onboarding", onPlan));

                    List<JsonObject> waiting = new ArrayList<>();

                    JsonObject list = object(issues.get(i).result());

                    for (Object item : list == null ? new JsonArray() : list.getJsonArray("items", new JsonArray()))
                    {
                        JsonObject issue = (JsonObject) item;

                        if ("To Do".equals(issue.getString("status")))
                        {
                            waiting.add(new JsonObject()
                                    .put("key", issue.getString("key"))
                                    .put("summary", issue.getString("summary"))
                                    .put("remainingHours", issue.getValue("remainingHours")));
                        }
                    }

                    toDo.put(id, waiting);

                    JsonObject row = object(heat.get(i).result());

                    if (row != null)
                    {
                        heatRows.put(id, row);
                    }

                    JsonObject disc = object(discipline.get(i).result());

                    JsonObject discMetrics = disc == null ? null : object(disc.getValue("metrics"));

                    extras.put(id, new JsonObject()
                            .put("dailyUpdates", discMetrics == null ? null : number(discMetrics.getValue("compliance")))
                            .put("discipline", disc == null ? null : number(disc.getValue("score")))
                            .put("index", number(index.get(i).result())));
                }

                List<JsonObject> sorted = TeamViews.byUtilization(rows);

                JsonArray table = new JsonArray();

                JsonArray heatmap = new JsonArray();

                JsonArray days = new JsonArray();

                for (JsonObject row : sorted)
                {
                    String id = row.getString("id");

                    table.add(new JsonObject()
                            .put("id", id)
                            .put("name", row.getString("name"))
                            .put("title", row.getString("title"))
                            .put("level", row.getString("level"))
                            .put("open", row.getInteger("open"))
                            .put("stale", row.getInteger("stale"))
                            .put("work", row.getValue("work"))
                            .put("capacity", row.getValue("cap"))
                            .put("utilization", row.getValue("util"))
                            .put("band", row.getValue("band"))
                            .put("onboarding", row.getBoolean("onboarding"))
                            .mergeIn(extras.get(id)));

                    JsonObject cells = heatRows.get(id);

                    if (cells != null)
                    {
                        if (days.isEmpty())
                        {
                            cells.getJsonArray("cells").forEach(cell -> days.add(((JsonObject) cell).getString("date")));
                        }

                        heatmap.add(new JsonObject()
                                .put("id", id)
                                .put("name", row.getString("name"))
                                .put("cells", cells.getJsonArray("cells"))
                                .put("now", cells.getValue("now"))
                                .put("onboarding", cells.getBoolean("onboarding")));
                    }
                }

                return (Object) new JsonObject()
                        .put("sprint", sprint.getString("key"))
                        .put("kpis", TeamViews.liveKpis(rows, rules))
                        .put("suggestion", TeamViews.rebalance(rows, toDo, TeamViews::skillsMatch, rules))
                        .put("heatmap", new JsonObject()
                                .put("days", days)
                                .put("today", ctx.today().toString())
                                .put("rows", heatmap))
                        .put("people", table);
            });
        });
    }

    // ------------------------------------------------------------------ closed sprint, month, release

    private static Future<Object> teamPeriod(CalcContext ctx, JsonObject params)
    {
        List<UUID> all = ids(params);

        Future<JsonObject> period = WorkloadFormulas.period(ctx, params);

        Future<Map<String, JsonObject>> people = people(ctx, all);

        Future<PeriodCalendar> calendar = ctx.calendar();

        Future<JsonArray> releases = WorkSql.rows(ctx, "SELECT key, starts_on AS \"from\", ends_on AS \"to\" FROM releases");

        return Future.all(period, people, calendar, releases).compose(loaded ->
        {
            JsonObject p = period.result();

            String key = p.getString("key");

            LocalDate end = LocalDate.parse(p.getString("to"));

            List<JsonObject> members = new ArrayList<>();

            for (UUID id : all)
            {
                JsonObject person = people.result().get(id.toString());

                String joinedOn = person == null ? null : person.getString("joinedOn");

                if (person != null && (joinedOn == null || !end.isBefore(LocalDate.parse(joinedOn))))
                {
                    members.add(person);
                }
            }

            List<Future<Object>> metrics = new ArrayList<>();

            List<Future<Object>> utils = new ArrayList<>();

            List<Future<Object>> issues = new ArrayList<>();

            for (JsonObject member : members)
            {
                JsonObject withPeriod = new JsonObject().put("person", member.getString("id")).put("period", key);

                metrics.add(ctx.get(WorkloadFormulas.SPRINT_METRICS, withPeriod));

                utils.add(ctx.get(WorkloadFormulas.UTILIZATION, withPeriod));

                issues.add(ctx.get(WorkViewFormulas.PERIOD_ISSUES, withPeriod));
            }

            List<JsonObject> sprints = WorkViewFormulas.sprintsOf(p, calendar.result().sprints(), releases.result()).reversed();

            Future<JsonArray> chart = sprints.size() > 1 ? sprintChart(ctx, members, sprints) : Future.succeededFuture(new JsonArray());

            Future<Object> release = PeriodCalendar.RELEASE.equals(p.getString("kind")) && !members.isEmpty()
                    ? ctx.get(WorkViewFormulas.RELEASE_CARD, new JsonObject().put("person", members.get(0).getString("id")).put("period", key))
                    : Future.succeededFuture(null);

            List<Future<?>> everything = new ArrayList<>(metrics);

            everything.addAll(utils);

            everything.addAll(issues);

            everything.add(chart);

            everything.add(release);

            return Future.all(everything).map(done ->
            {
                JsonObject rules = ctx.rules();

                List<JsonObject> rows = new ArrayList<>();

                for (int i = 0; i < members.size(); i++)
                {
                    JsonObject member = members.get(i);

                    JsonObject m = object(metrics.get(i).result());

                    JsonObject list = object(issues.get(i).result());

                    JsonArray items = list == null ? new JsonArray() : list.getJsonArray("items", new JsonArray());

                    long doneIssues = items.stream().filter(item -> "Done".equals(((JsonObject) item).getString("status"))).count();

                    Double util = number(utils.get(i).result());

                    String joinedOn = member.getString("joinedOn");

                    boolean newJoiner = joinedOn != null
                            && Appraisal.monthsBetween(LocalDate.parse(joinedOn), ctx.today()) < NEW_JOINER_MONTHS;

                    rows.add(member.copy()
                            .put("issues", items.size())
                            .put("issuesDone", doneIssues)
                            .put("com", m == null ? null : m.getValue("com"))
                            .put("dsp", m == null ? null : m.getValue("dsp"))
                            .put("commit", m == null ? null : m.getValue("commit"))
                            .put("carry", m == null ? 0 : m.getJsonArray("carry", new JsonArray()).size())
                            .put("logged", m == null ? null : m.getValue("logged"))
                            .put("work", m == null ? null : m.getValue("work"))
                            .put("util", util == null ? 0.0 : util)
                            .put("newJoiner", newJoiner));
                }

                JsonObject kpis = TeamViews.closedKpis(rows, rules);

                JsonArray table = new JsonArray();

                for (JsonObject row : TeamViews.byUtilization(rows))
                {
                    int util = (int) Math.round(row.getDouble("util"));

                    table.add(new JsonObject()
                            .put("id", row.getString("id"))
                            .put("name", row.getString("name"))
                            .put("title", row.getString("title"))
                            .put("level", row.getString("level"))
                            .put("issuesDone", row.getLong("issuesDone"))
                            .put("issues", row.getInteger("issues"))
                            .put("storyPointsDone", row.getValue("dsp"))
                            .put("committed", row.getValue("com"))
                            .put("commitment", row.getValue("commit"))
                            .put("carriedOver", row.getInteger("carry"))
                            .put("logged", row.getValue("logged"))
                            .put("utilization", util)
                            .put("band", Workload.band(util, rules))
                            .put("newJoiner", row.getBoolean("newJoiner")));
                }

                JsonObject card = object(release.result());

                if (card != null)
                {
                    card = card.copy();

                    card.remove("personDone");

                    card.remove("share");

                    card.put("teamDone", kpis.getValue("storyPointsDone")).put("committed", kpis.getValue("committed"));
                }

                return (Object) new JsonObject()
                        .put("kpis", kpis)
                        .put("people", table)
                        .put("release", card)
                        .put("sprints", chart.result());
            });
        });
    }

    /**
     * Team utilization by sprint, oldest first: the active sprint as the average live utilization (a
     * forecast), the others as hours logged over working days × {@code dayHours}.
     */
    private static Future<JsonArray> sprintChart(CalcContext ctx, List<JsonObject> members, List<JsonObject> sprints)
    {
        List<Future<Object>> cells = new ArrayList<>();

        for (JsonObject sprint : sprints)
        {
            boolean live = sprint.getBoolean("live", false);

            for (JsonObject member : members)
            {
                JsonObject person = new JsonObject().put("person", member.getString("id"));

                cells.add(live
                        ? ctx.get(WorkloadFormulas.LIVE_SPRINT, person)
                        : ctx.get(WorkloadFormulas.PERIOD_WORK, person.put("period", sprint.getString("key"))));
            }
        }

        return Future.all(cells).map(done ->
        {
            JsonObject rules = ctx.rules();

            JsonArray out = new JsonArray();

            int i = 0;

            for (JsonObject sprint : sprints)
            {
                boolean live = sprint.getBoolean("live", false);

                List<Double> liveUtils = new ArrayList<>();

                double logged = 0;

                double work = 0;

                for (int m = 0; m < members.size(); m++, i++)
                {
                    JsonObject result = object(cells.get(i).result());

                    if (result == null)
                    {
                        continue;
                    }

                    if (live)
                    {
                        Double util = number(result.getValue("util"));

                        liveUtils.add(util == null ? 0.0 : util);
                    }
                    else
                    {
                        logged += Workload.orZero(Workload.number(result, "logged"));

                        work += Workload.orZero(Workload.number(result, "work"));
                    }
                }

                int util = TeamViews.sprintUtilization(live, liveUtils, logged, work, rules);

                out.add(new JsonObject()
                        .put("period", sprint.getString("key"))
                        .put("short", sprint.getString("short"))
                        .put("live", live)
                        .put("utilization", util)
                        .put("band", Workload.band(util, rules)));
            }

            return out;
        });
    }

    // ------------------------------------------------------------------ helpers

    /**
     * {@code people}: comma-separated person ids, none for an empty team; duplicates are dropped.
     */
    static List<UUID> ids(JsonObject params)
    {
        String value = params.getString("people");

        if (value == null)
        {
            throw new IllegalArgumentException("people: A list of ids is required.");
        }

        Set<UUID> out = new LinkedHashSet<>();

        for (String part : value.split(","))
        {
            if (!part.isBlank())
            {
                out.add(WorkloadFormulas.uuid(new JsonObject().put("person", part.strip()), "person"));
            }
        }

        return List.copyOf(out);
    }

    /**
     * Id to {@code {id, name, title, level, team, joinedOn, skills}}.
     */
    private static Future<Map<String, JsonObject>> people(CalcContext ctx, List<UUID> ids)
    {
        UUID[] array = ids.toArray(UUID[]::new);

        Future<JsonArray> rows = WorkSql.rows(ctx, """
                SELECT id, name, title, level, department AS team, joined_on AS "joinedOn"
                FROM people WHERE id = ANY($1)""", Tuple.of(array));

        Future<JsonArray> skills = WorkSql.rows(ctx, """
                SELECT person_id AS "personId", skill, current_level AS current
                FROM skills_matrix WHERE person_id = ANY($1) ORDER BY person_id, sort_order""", Tuple.of(array));

        return Future.all(rows, skills).map(all ->
        {
            Map<String, JsonObject> out = new HashMap<>();

            for (JsonObject row : WorkloadFormulas.list(rows.result()))
            {
                out.put(row.getString("id"), row.put("skills", new JsonArray()));
            }

            for (JsonObject skill : WorkloadFormulas.list(skills.result()))
            {
                JsonObject person = out.get(skill.getString("personId"));

                if (person != null)
                {
                    person.getJsonArray("skills").add(new JsonObject()
                            .put("skill", skill.getString("skill"))
                            .put("current", skill.getInteger("current")));
                }
            }

            return out;
        });
    }

    /**
     * A result that is an object and not "not applicable"; null otherwise.
     */
    private static JsonObject object(Object value)
    {
        return value instanceof JsonObject o && !o.getBoolean("notApplicable", false) ? o : null;
    }

    private static Double number(Object value)
    {
        return value instanceof Number n ? n.doubleValue() : null;
    }
}
