package com.pulse.calc.work;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.CoreThresholds;
import com.pulse.calc.core.CultureParameters;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.pulse.calc.work.WorkloadFormulas.list;
import static com.pulse.calc.work.WorkloadFormulas.safe;
import static com.pulse.calc.work.WorkloadFormulas.uuid;

/**
 * Progress (task 13.3, spec insight/progress-pulse), ported from the prototype's {@code achievements},
 * {@code nextActions} and {@code pProgress}:
 * <ul>
 *   <li>{@code work.achievements {person}}: {@code [{title, detail, value}]} earned from data: Daily updates,
 *   PR standard, Great reviewer, Commitment keeper, Proactive fixer, Track 3's (Delighted customer,
 *   Training completed, Mentor) and Key result achieved.</li>
 *   <li>{@code work.nextSteps {person}}: {@code [{text, target, selfOnly}]} collected from every screen:
 *   stale issues, code-only days, PR titles to fix, Track 3's (reviews to give, training feedback,
 *   sign-off, check-ins, visit reports), this week's pulse ({@code selfOnly}) and OKRs off track.</li>
 *   <li>{@code work.progressTrends {person}}: the index over 4 quarters, culture values last quarter to
 *   this one by biggest gain (each with its {@code tone}), OKRs this quarter, the PR standard by month (its
 *   {@code tone} from rule {@code prTarget}) and Track 3's training.</li>
 * </ul>
 *
 * <p>Track 3's parts come from the optional port {@code people.progress {person}}
 * ({@code {achievements, nextSteps, training}}, docs/calc.md §5) and are absent until it is registered.
 */
public final class ProgressFormulas implements FormulaModule
{

    public static final String ACHIEVEMENTS = "work.achievements";

    public static final String NEXT_STEPS = "work.nextSteps";

    public static final String TRENDS = "work.progressTrends";

    /**
     * Track 3's Progress port.
     */
    public static final String PEOPLE_PROGRESS = "people.progress";

    /**
     * An objective under this progress is "off track" for next steps (prototype {@code okrProg(o) < 0.4}).
     */
    public static final double OKR_OFF_TRACK = 0.4;

    private static final String DEFAULT_VALUE = "Outcome Ownership";

    @Override
    public String id()
    {
        return "work";
    }

    @Override
    public List<Formula> formulas()
    {
        Set<String> topics = new HashSet<>(Set.of(CalcService.PEOPLE, CalcService.SPRINTS, CalcService.PERFORMANCE, WorkloadFormulas.ISSUES,
                WorkloadFormulas.WORKLOGS, WorkloadFormulas.BUGS, PrFormulas.PULL_REQUESTS, OkrFormulas.OKRS, EffortFormulas.EXTRA_ITEMS,
                ActionCenterFormulas.PULSE, WorkFormulas.HOLIDAYS, CalcService.importTopic("okrs")));

        return List.of(
                Formula.of(ACHIEVEMENTS, Set.of("person"), topics, safe(ProgressFormulas::achievements)),
                Formula.of(NEXT_STEPS, Set.of("person"), topics, safe(ProgressFormulas::nextSteps)),
                Formula.of(TRENDS, Set.of("person"), topics, safe(ProgressFormulas::trends)));
    }

    // ------------------------------------------------------------------ achievements

    private static Future<Object> achievements(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        JsonObject ref = new JsonObject().put("person", person.toString());

        LocalDate today = ctx.today();

        String month = monthKey(today);

        return ctx.calendar().compose(calendar ->
        {
            List<String> closed = new ArrayList<>();

            for (Object o : calendar.sprints())
            {
                JsonObject s = (JsonObject) o;

                if (!s.getBoolean("live", false) && closed.size() < ActionCenterFormulas.VELOCITY_SPRINTS)
                {
                    closed.add(s.getString("key"));
                }
            }

            Future<Object> discipline = ctx.get("core.discipline", ref);

            Future<Object> prs = ctx.get(PrFormulas.PERSON, ref.copy().put("period", month));

            Future<Object> bugs = ctx.get(WorkloadFormulas.BUG_STATS, ref.copy().put("period", quarterKey(today)));

            List<Future<Object>> sprints = closed.stream()
                    .map(k -> ctx.get(WorkloadFormulas.SPRINT_METRICS, ref.copy().put("period", k)))
                    .toList();

            Future<List<JsonObject>> okrs = objectives(ctx, person, quarter(today));

            Future<Object> port = ctx.optional(PEOPLE_PROGRESS, ref);

            List<Future<?>> waits = new ArrayList<>(List.of(discipline, prs, bugs, okrs, port));

            waits.addAll(sprints);

            return Future.all(waits).map(all ->
            {
                JsonArray out = new JsonArray();

                JsonObject metrics = discipline.result() instanceof JsonObject d ? d.getJsonObject("metrics") : null;

                if (metrics != null && metrics.getValue("compliance") instanceof Number c && c.intValue() >= 90)
                {
                    out.add(achievement("Daily updates", "Updated Jira on " + c.intValue() + "% of working days", CultureParameters.DISCIPLINE));
                }

                if (prs.result() instanceof JsonObject pr)
                {
                    Integer comp = pr.getValue("comp") instanceof Number n ? n.intValue() : null;

                    int mine = pr.getJsonArray("mine", new JsonArray()).size();

                    if (comp != null && comp >= ctx.rules().getInteger("prTarget", 85) && mine >= 3)
                    {
                        out.add(achievement("PR standard", monthName(today) + ": " + comp + "% of PR checks passed", CultureParameters.DISCIPLINE));
                    }

                    Integer standard = pr.getValue("stdPct") instanceof Number n ? n.intValue() : null;

                    if (standard != null && standard >= 80 && pr.getJsonArray("cm", new JsonArray()).size() >= 5)
                    {
                        out.add(achievement("Great reviewer", standard + "% of review comments to the standard", "Collaboration"));
                    }
                }

                List<Integer> commits = new ArrayList<>();

                sprints.forEach(s ->
                {
                    if (s.result() instanceof JsonObject m && m.getValue("commit") instanceof Number n)
                    {
                        commits.add(n.intValue());
                    }
                });

                if (commits.size() == ActionCenterFormulas.VELOCITY_SPRINTS && commits.stream().allMatch(c -> c >= 90))
                {
                    out.add(achievement("Commitment keeper", "90%+ of committed SP in the last 3 sprints", DEFAULT_VALUE));
                }

                if (bugs.result() instanceof JsonObject b && b.getInteger("proactive", 0) > 0)
                {
                    out.add(achievement("Proactive fixer", b.getInteger("proactive") + " bug(s) in others’ code fixed", "Problem Solving"));
                }

                if (port.result() instanceof JsonObject p)
                {
                    p.getJsonArray("achievements", new JsonArray()).forEach(a -> out.add(a));
                }

                for (JsonObject objective : okrs.result())
                {
                    String value = objective.getString("value") == null ? DEFAULT_VALUE : objective.getString("value");

                    for (Object k : objective.getJsonArray("keyResults"))
                    {
                        JsonObject kr = (JsonObject) k;

                        if (kr.getDouble("progress") >= 1)
                        {
                            out.add(achievement("Key result achieved", kr.getString("description"), value));
                        }
                    }
                }

                return (Object) out;
            });
        });
    }

    private static JsonObject achievement(String title, String detail, String value)
    {
        return new JsonObject().put("title", title).put("detail", detail).put("value", value);
    }

    // ------------------------------------------------------------------ next steps

    private static Future<Object> nextSteps(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        JsonObject ref = new JsonObject().put("person", person.toString());

        LocalDate today = ctx.today();

        LocalDate week = today.with(DayOfWeek.MONDAY);

        return ctx.calendar().compose(calendar ->
        {
            JsonObject sprint = calendar.activeSprint();

            Future<Object> live = ctx.get(WorkloadFormulas.LIVE_SPRINT, ref);

            Future<Object> effort = sprint == null ? Future.succeededFuture(null)
                    : ctx.get(EffortFormulas.EFFORT, ref.copy().put("period", sprint.getString("key")));

            Future<JsonArray> prs = sprint == null ? Future.succeededFuture(new JsonArray()) : WorkSql.rows(ctx, """
                    SELECT title FROM pull_requests
                    WHERE author_id = $1 AND status = 'Active' AND opened_at::date BETWEEN $2 AND $3""",
                    Tuple.of(person, LocalDate.parse(sprint.getString("from")), LocalDate.parse(sprint.getString("to"))));

            Future<JsonObject> pulse = WorkSql.row(ctx, "SELECT 1 AS answered FROM weekly_pulse WHERE person_id = $1 AND week_start >= $2",
                    Tuple.of(person, week));

            Future<List<JsonObject>> okrs = objectives(ctx, person, quarter(today));

            Future<Object> port = ctx.optional(PEOPLE_PROGRESS, ref);

            return Future.all(List.of(live, effort, prs, pulse, okrs, port)).map(all ->
            {
                JsonArray out = new JsonArray();

                if (live.result() instanceof JsonObject l)
                {
                    int stale = l.getJsonArray("stale", new JsonArray()).size();

                    if (stale > 0)
                    {
                        out.add(step(stale + " issue" + (stale > 1 ? "s" : "") + " without an update — add a comment or move the status",
                                target("work-items", null), false));
                    }
                }

                if (effort.result() instanceof JsonObject e && e.getInteger("codeOnly", 0) > 0)
                {
                    out.add(step(e.getInteger("codeOnly") + " day(s) with commits but no worklog — log the time", target("effort", null), false));
                }

                long badTitles = list(prs.result()).stream().filter(p -> !PrStandard.titleOk(p.getString("title"), ctx.rules())).count();

                if (badTitles > 0)
                {
                    out.add(step(badTitles + " open PR title(s) not to the standard", target("pull_requests", null), false));
                }

                if (port.result() instanceof JsonObject p)
                {
                    p.getJsonArray("nextSteps", new JsonArray()).forEach(s -> out.add(((JsonObject) s).copy()
                            .put("selfOnly", ((JsonObject) s).getBoolean("selfOnly", false))));
                }

                if (pulse.result() == null)
                {
                    out.add(step("Weekly pulse check-in for this week", target("progress", "pulse"), true));
                }

                for (JsonObject objective : okrs.result())
                {
                    if (objective.getDouble("progress") < OKR_OFF_TRACK)
                    {
                        out.add(step("OKR off track: " + objective.getString("title") + " (" + Math.round(objective.getDouble("progress") * 100) + "%)",
                                target("okrs", null), false));
                    }
                }

                return (Object) out;
            });
        });
    }

    private static JsonObject step(String text, JsonObject target, boolean selfOnly)
    {
        return new JsonObject().put("text", text).put("target", target).put("selfOnly", selfOnly);
    }

    private static JsonObject target(String module, String tab)
    {
        JsonObject out = new JsonObject().put("module", module);

        return tab == null ? out : out.put("tab", tab);
    }

    // ------------------------------------------------------------------ trends

    private static Future<Object> trends(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        JsonObject ref = new JsonObject().put("person", person.toString());

        LocalDate today = ctx.today();

        String quarterNow = quarterKey(today);

        String quarterBefore = quarterKey(today.minusMonths(3));

        Future<Object> history = ctx.optional("core.indexHistory", ref).recover(e -> Future.succeededFuture(null));

        Future<Object> index = ctx.optional("core.index", ref.copy().put("period", quarterNow)).recover(e -> Future.succeededFuture(null));

        Future<Object> cultureNow = ctx.optional("core.culture", ref.copy().put("period", quarterNow)).recover(e -> Future.succeededFuture(null));

        Future<Object> cultureBefore = ctx.optional("core.culture", ref.copy().put("period", quarterBefore))
                .recover(e -> Future.succeededFuture(null));

        Future<List<JsonObject>> okrs = objectives(ctx, person, quarter(today));

        List<LocalDate> months = List.of(today.minusMonths(2), today.minusMonths(1), today);

        List<Future<Object>> prs = months.stream().map(m -> ctx.get(PrFormulas.PERSON, ref.copy().put("period", monthKey(m)))).toList();

        Future<Object> port = ctx.optional(PEOPLE_PROGRESS, ref);

        List<Future<?>> waits = new ArrayList<>(List.of(history, index, cultureNow, cultureBefore, okrs, port));

        waits.addAll(prs);

        return Future.all(waits).map(all ->
        {
            JsonArray quarters = new JsonArray();

            for (int back = 3; back >= 0; back--)
            {
                LocalDate d = today.minusMonths(3L * back);

                quarters.add(new JsonObject().put("quarter", "Q" + ((d.getMonthValue() - 1) / 3 + 1) + " " + (d.getYear() % 100)));
            }

            if (history.result() instanceof JsonArray h)
            {
                for (int i = 0; i < Math.min(3, h.size()); i++)
                {
                    quarters.getJsonObject(i).put("index", h.getValue(i));
                }
            }

            quarters.getJsonObject(3).put("index", index.result());

            JsonArray culture = new JsonArray();

            if (cultureNow.result() instanceof JsonArray now)
            {
                JsonArray before = cultureBefore.result() instanceof JsonArray b ? b : new JsonArray();

                List<JsonObject> rows = new ArrayList<>();

                for (int i = 0; i < CultureParameters.VALUES.size() && i < now.size(); i++)
                {
                    Double n = now.getValue(i) instanceof Number x ? x.doubleValue() : null;

                    Double b = i < before.size() && before.getValue(i) instanceof Number x ? x.doubleValue() : null;

                    Double gain = n == null || b == null ? null : Math.round((n - b) * 10) / 10.0;

                    rows.add(new JsonObject()
                            .put("value", CultureParameters.VALUES.get(i))
                            .put("before", b)
                            .put("now", n)
                            .put("gain", gain)
                            .put("tone", WorkThresholds.gainTone(gain)));
                }

                rows.sort((x, y) -> Double.compare(gain(y), gain(x)));

                rows.forEach(culture::add);
            }

            JsonArray objectives = new JsonArray();

            okrs.result().forEach(o -> objectives.add(new JsonObject()
                    .put("id", o.getString("id"))
                    .put("title", o.getString("title"))
                    .put("progress", o.getDouble("progress"))
                    .put("status", o.getString("status"))));

            JsonArray prByMonth = new JsonArray();

            JsonArray prTones = WorkThresholds.prComplianceTones(ctx.rules());

            for (int i = 0; i < months.size(); i++)
            {
                JsonObject pr = prs.get(i).result() instanceof JsonObject p ? p : new JsonObject();

                prByMonth.add(new JsonObject()
                        .put("month", monthKey(months.get(i)).substring(2))
                        .put("label", monthName(months.get(i)))
                        .put("comp", pr.getValue("comp"))
                        .put("tone", CoreThresholds.tone(pr.getNumber("comp"), prTones))
                        .put("stdPct", pr.getValue("stdPct")));
            }

            return (Object) new JsonObject()
                    .put("index", quarters)
                    .put("culture", culture)
                    .put("cultureFrom", label(quarterBefore))
                    .put("cultureTo", label(quarterNow))
                    .put("okrs", objectives)
                    .put("okrQuarter", label(quarterNow))
                    .put("prByMonth", prByMonth)
                    .put("training", port.result() instanceof JsonObject p ? p.getJsonArray("training", new JsonArray()) : null);
        });
    }

    /**
     * The gain of a culture row; rows without one sort last.
     */
    private static double gain(JsonObject row)
    {
        return row.getValue("gain") instanceof Number n ? n.doubleValue() : Double.NEGATIVE_INFINITY;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The person's individual objectives of {@code quarter} ("2026-Q3") with their progress (as
     * {@link OkrProgress#detail}), titles, culture value and key-result descriptions.
     */
    static Future<List<JsonObject>> objectives(CalcContext ctx, UUID person, String quarter)
    {
        return WorkSql.rows(ctx, """
                SELECT o.id, o.title, o.culture_value AS value,
                       coalesce((SELECT json_agg(json_build_object('id', k.id, 'description', k.description,
                                         'start_value', k.start_value, 'target_value', k.target_value,
                                         'current_value', k.current_value) ORDER BY k.position)
                                 FROM key_results k WHERE k.objective_id = o.id), '[]'::json) AS key_results
                FROM objectives o
                WHERE o.level = 'Individual' AND o.owner_id = $1 AND o.quarter = $2
                ORDER BY o.created_at, o.id""", Tuple.of(person, quarter)).map(rows ->
        {
            List<JsonObject> out = new ArrayList<>();

            for (JsonObject row : list(rows))
            {
                JsonObject detail = OkrProgress.detail(row);

                Map<String, String> descriptions = new HashMap<>();

                row.getJsonArray("key_results", new JsonArray()).forEach(k ->
                        descriptions.put(((JsonObject) k).getString("id"), ((JsonObject) k).getString("description")));

                detail.getJsonArray("keyResults").forEach(k -> ((JsonObject) k).put("description", descriptions.get(((JsonObject) k).getString("id"))));

                out.add(detail.put("title", row.getString("title")).put("value", row.getString("value")));
            }

            return out;
        });
    }

    private static String quarter(LocalDate day)
    {
        return day.getYear() + "-Q" + ((day.getMonthValue() - 1) / 3 + 1);
    }

    private static String quarterKey(LocalDate day)
    {
        return "q:" + quarter(day);
    }

    private static String label(String quarterKey)
    {
        String q = quarterKey.substring(2);

        return q.substring(5) + " " + q.substring(0, 4);
    }

    private static String monthKey(LocalDate day)
    {
        return "m:" + day.getYear() + "-" + String.format("%02d", day.getMonthValue());
    }

    private static String monthName(LocalDate day)
    {
        return day.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }
}
