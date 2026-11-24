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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.pulse.calc.work.WorkloadFormulas.list;
import static com.pulse.calc.work.WorkloadFormulas.safe;
import static com.pulse.calc.work.WorkloadFormulas.uuid;

/**
 * The {@code work.cultureInputs {person, period (quarter)}} port of {@code core.cultureData} (docs/calc.md
 * §5; tasks 4.3 and 4.4): {@code {"<param key>": {raw, of?, high?}}} for the culture parameters measured
 * from work data, ported from the prototype's {@code valData} (app.js ~2202) on top of Track 2's
 * formulas.
 *
 * <ul>
 *   <li>Last 3 finished sprints ({@code work.sprintMetrics}): {@code commitment_met} (average commitment
 *       %), {@code issues_carried_over} and {@code logged_vs_estimate} (average estimate accuracy %);
 *       {@code median_cycle_time} (median over their done issues, {@code work.sprintCultureFacts}).</li>
 *   <li>Last 6 finished sprints: {@code bugs_fixed_without_reopening} (% of done bugs not reopened, of
 *       the done bugs, {@code work.sprintCultureFacts}) and {@code delivery_overloaded_sprints} (average commitment % in sprints over 100%
 *       utilization, in that many sprints).</li>
 *   <li>Bugs and support ({@code work.bugStats}): {@code open_bugs_own_code} (with the high ones),
 *       {@code others_bugs_fixed_proactively} and {@code support_tickets_resolved}.</li>
 *   <li>Pull requests of the month of the quarter's last day up to today ({@code work.prStats}):
 *       {@code median_pr_size}, {@code pr_reviews_given} and {@code review_comments_standard}.</li>
 *   <li>The active sprint ({@code work.liveSprint}): {@code stale_share}.</li>
 *   <li>Done extra-mile items of the quarter: {@code mid_sprint_work_absorbed}, {@code helped_others}
 *       (help and takeover, plus active mentees from {@code people.trainingLoad}) and
 *       {@code useful_tools_scripts} (untracked).</li>
 *   <li>{@code areas_worked_in} (coverage areas the person touched, of all areas) and
 *       {@code mood_heavy_weeks} (average mood of the weekly pulses with workload 4 or 5).</li>
 * </ul>
 *
 * <p>A parameter with no data reads as missing ({@code raw} null); a count of 0 stays 0, which the
 * no-opportunity parameters show as "0 — no opportunity".
 */
public final class WorkCultureFormulas implements FormulaModule
{
    public static final String CULTURE_INPUTS = "work.cultureInputs";

    /** The prototype's {@code SPRINTS.slice(1, 4)} and {@code slice(1, 7)}. */
    private static final int LAST_3 = 3;

    private static final int LAST_6 = 6;

    private static final Set<String> TOPICS = Set.of(WorkloadFormulas.ISSUES, WorkloadFormulas.WORKLOGS, WorkloadFormulas.BUGS,
            PrFormulas.PULL_REQUESTS, EffortFormulas.EXTRA_ITEMS, ActionCenterFormulas.PULSE, ActionCenterFormulas.COVERAGE_DATA,
            CalcService.PEOPLE, CalcService.SPRINTS);

    @Override
    public String id()
    {
        return "work";
    }

    @Override
    public List<Formula> formulas()
    {
        return List.of(Formula.of(CULTURE_INPUTS, Set.of("person", "period"), TOPICS, safe(WorkCultureFormulas::cultureInputs)));
    }

    private static Future<Object> cultureInputs(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        String id = person.toString();

        JsonObject ref = new JsonObject().put("person", id);

        return ctx.calendar().compose(calendar ->
        {
            JsonObject quarter = calendar.period(params.getString("period"));

            if (!PeriodCalendar.QUARTER.equals(quarter.getString("kind")))
            {
                return Future.failedFuture(new IllegalArgumentException("period: '" + params.getString("period")
                        + "' is not a quarter; culture values are quarterly."));
            }

            LocalDate today = ctx.today();

            LocalDate from = LocalDate.parse(quarter.getString("from"));

            LocalDate to = LocalDate.parse(quarter.getString("to"));

            LocalDate lastDay = to.isBefore(today) ? to : today;

            String month = "m:" + lastDay.toString().substring(0, 7);

            List<String> finished = new ArrayList<>();

            for (Object o : calendar.sprints())
            {
                JsonObject sprint = (JsonObject) o;

                if (finished.size() < LAST_6 && LocalDate.parse(sprint.getString("to")).isBefore(lastDay))
                {
                    finished.add(sprint.getString("key"));
                }
            }

            List<Future<Object>> metrics = finished.stream()
                    .map(k -> ctx.get(WorkloadFormulas.SPRINT_METRICS, ref.copy().put("period", k)))
                    .toList();

            // cycle times and done bugs come from a formula that is never frozen, so sprints closed before
            // these inputs existed still have them
            List<Future<Object>> facts = finished.stream()
                    .map(k -> ctx.get(WorkloadFormulas.SPRINT_CULTURE_FACTS, ref.copy().put("period", k)))
                    .toList();

            List<Future<Object>> utilization = finished.stream()
                    .map(k -> ctx.get(WorkloadFormulas.UTILIZATION, ref.copy().put("period", k)))
                    .toList();

            Future<Object> bugs = ctx.get(WorkloadFormulas.BUG_STATS, params);

            Future<Object> prs = ctx.get(PrFormulas.PERSON, ref.copy().put("period", month));

            Future<Object> live = ctx.get(WorkloadFormulas.LIVE_SPRINT, ref);

            Future<Object> training = ctx.optional("people.trainingLoad", ref);

            Future<JsonArray> extra = WorkSql.rows(ctx, "SELECT kind FROM extra_items WHERE person_id = $1 AND is_done "
                    + "AND occurred_on BETWEEN $2 AND $3", Tuple.of(person, from, to));

            Future<JsonObject> areas = WorkSql.row(ctx, "SELECT (SELECT count(*) FROM coverage_areas) AS total, (SELECT count(DISTINCT area) "
                    + "FROM coverage_counts WHERE person_id = $1 AND touches > 0) AS touched", Tuple.of(person));

            Future<JsonArray> pulses = WorkSql.rows(ctx, "SELECT mood FROM weekly_pulse WHERE person_id = $1 AND workload >= 4",
                    Tuple.of(person));

            List<Future<?>> waits = new ArrayList<>(List.of(bugs, prs, live, training, extra, areas, pulses));

            waits.addAll(metrics);
            waits.addAll(facts);
            waits.addAll(utilization);

            return Future.all(waits).map(all ->
            {
                List<JsonObject> sprints = metrics.stream().map(f -> (JsonObject) f.result()).toList();

                List<JsonObject> last3 = sprints.subList(0, Math.min(LAST_3, sprints.size()));

                List<JsonObject> sprintFacts = facts.stream().map(f -> (JsonObject) f.result()).toList();

                JsonObject inputs = new JsonObject();

                sprintInputs(inputs, last3, sprints, sprintFacts, utilization);

                bugInputs(inputs, (JsonObject) bugs.result());

                prInputs(inputs, (JsonObject) prs.result());

                JsonObject sprint = live.result() instanceof JsonObject l ? l : null;

                int open = sprint == null ? 0 : sprint.getJsonArray("open", new JsonArray()).size();

                int stale = sprint == null ? 0 : sprint.getJsonArray("stale", new JsonArray()).size();

                inputs.put("stale_share", raw(open == 0 ? 0 : Math.round(stale * 100.0 / open)));

                extraInputs(inputs, list(extra.result()), training.result() instanceof JsonObject t ? t.getInteger("mentees", 0) : 0);

                JsonObject a = areas.result();

                if (a != null)
                {
                    inputs.put("areas_worked_in", raw(a.getInteger("touched")).put("of", a.getInteger("total")));
                }

                List<Double> moods = list(pulses.result()).stream().map(p -> p.getDouble("mood")).toList();

                inputs.put("mood_heavy_weeks", raw(moods.isEmpty() ? null : Math.round(average(moods) * 10) / 10.0));

                return inputs;
            });
        });
    }

    // ------------------------------------------------------------------ groups

    private static void sprintInputs(JsonObject inputs, List<JsonObject> last3, List<JsonObject> last6, List<JsonObject> facts,
                                     List<Future<Object>> utilization)
    {
        List<Double> commits = numbers(last3, "commit");

        inputs.put("commitment_met", raw(commits.isEmpty() ? null : Math.round(average(commits))));

        inputs.put("issues_carried_over", raw(last3.stream().mapToInt(m -> m.getJsonArray("carry").size()).sum()));

        List<Double> cycles = new ArrayList<>();

        facts.subList(0, Math.min(LAST_3, facts.size()))
                .forEach(f -> f.getJsonArray("cycles").forEach(c -> cycles.add(((Number) c).doubleValue())));

        inputs.put("median_cycle_time", raw(cycles.isEmpty() ? null : SprintMetrics.median(cycles)));

        List<Double> estimates = numbers(last3, "est");

        inputs.put("logged_vs_estimate", raw(estimates.isEmpty() ? null : Math.round(average(estimates))));

        int done = facts.stream().mapToInt(f -> f.getJsonObject("doneBugs").getInteger("done")).sum();

        int reopened = facts.stream().mapToInt(f -> f.getJsonObject("doneBugs").getInteger("reopened")).sum();

        inputs.put("bugs_fixed_without_reopening", raw(done == 0 ? null : Math.round((1 - reopened / (double) done) * 100)).put("of", done));

        List<Double> overloaded = new ArrayList<>();

        for (int i = 0; i < last6.size(); i++)
        {
            Object util = utilization.get(i).result();

            Object commit = last6.get(i).getValue("commit");

            if (util instanceof Number u && u.doubleValue() > 100 && commit instanceof Number c)
            {
                overloaded.add(c.doubleValue());
            }
        }

        inputs.put("delivery_overloaded_sprints", raw(overloaded.isEmpty() ? null : Math.round(average(overloaded))).put("of", overloaded.size()));
    }

    private static void bugInputs(JsonObject inputs, JsonObject q)
    {
        inputs.put("open_bugs_own_code", raw(q.getInteger("open")).put("high", q.getInteger("high")));

        inputs.put("others_bugs_fixed_proactively", raw(q.getInteger("proactive")));

        inputs.put("support_tickets_resolved", raw(q.getInteger("supResolved", 0)));
    }

    private static void prInputs(JsonObject inputs, JsonObject pr)
    {
        boolean mine = !pr.getJsonArray("mine", new JsonArray()).isEmpty();

        inputs.put("median_pr_size", raw(mine ? Math.round(pr.getDouble("size")) : null));

        inputs.put("pr_reviews_given", raw(pr.getJsonArray("rv", new JsonArray()).size()));

        inputs.put("review_comments_standard", raw(pr.getValue("stdPct")));
    }

    private static void extraInputs(JsonObject inputs, List<JsonObject> items, int activeMentees)
    {
        inputs.put("mid_sprint_work_absorbed", raw(count(items, "midsprint")));

        inputs.put("helped_others", raw(count(items, "help") + count(items, "takeover") + activeMentees));

        inputs.put("useful_tools_scripts", raw(count(items, "untracked")));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject raw(Object value)
    {
        return new JsonObject().put("raw", value);
    }

    private static int count(List<JsonObject> items, String kind)
    {
        return (int) items.stream().filter(x -> kind.equals(x.getString("kind"))).count();
    }

    private static List<Double> numbers(List<JsonObject> rows, String key)
    {
        return rows.stream().map(r -> r.getValue(key)).filter(v -> v instanceof Number).map(v -> ((Number) v).doubleValue()).toList();
    }

    private static double average(List<Double> values)
    {
        return values.stream().mapToDouble(Double::doubleValue).sum() / values.size();
    }
}
