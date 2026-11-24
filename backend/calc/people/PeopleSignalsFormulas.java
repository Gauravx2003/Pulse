package com.pulse.calc.people;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.TrainingRating;
import com.pulse.modules.training.TrainingLifecycle;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Track 3's ports for the Action center and Progress (Track 2, tasks 13.2–13.4; docs/calc.md §5), ported
 * from the prototype's {@code signals}, {@code capRow}, {@code teamWins}, {@code achievements} and
 * {@code nextActions}:
 * <ul>
 *   <li>{@code people.lastOneOnOne {person}}: the date of the last 1:1 held, or null ({@code LAST11}).</li>
 *   <li>{@code people.kudosReceived {person, period}}: kudos received in the quarter.</li>
 *   <li>{@code people.trainingBehind {person}}: {@code [{plan, overdue}]} of the unfinished plans
 *       ({@code trOverdue}: modules not done whose week has ended).</li>
 *   <li>{@code people.trainingLoad {person}}: {@code {plans: [{hoursPerWeek, end}], mentees}} of the
 *       active plans and the active trainees the person mentors ({@code trActiveStage}).</li>
 *   <li>{@code people.wins {person}}: kudos, visits rated 4.5 or more and completed training of the last
 *       90 days as {@code [{kind, what, text, at, value?}]}, {@code at} an ISO date.</li>
 *   <li>{@code people.progress {person}}: {@code {achievements, nextSteps, training}} — Delighted customer,
 *       Training completed and Mentor; reviews to give ({@code selfOnly}), feedback to acknowledge,
 *       sign-off, check-ins due and visit reports due; the person's plans with stage and rating.
 *       Deviation: feedback to acknowledge leaves out checkpoint reviews ({@code cp:} keys), which Pulse's
 *       training screens do not ask the trainee to acknowledge ({@link TrainingLifecycle#feedbackKeys}).</li>
 * </ul>
 *
 * <p>Every port is null when its tables are not installed (core-only test schemas), so Track 2 reads it
 * as absent.
 */
public final class PeopleSignalsFormulas implements FormulaModule
{
    /** Published when a 1:1 is created. */
    public static final String ONE_ON_ONES = "people.oneOnOnes";

    /** Days a win stays "recent". */
    static final int WIN_DAYS = 90;

    static final double WIN_CSAT = 4.5;

    private static final Set<String> TRAINING_TOPICS = Set.of(TrainingFormulas.TRAINING, ReviewFormulas.REVIEWS, CalcService.PEOPLE);

    @Override
    public String id()
    {
        return "people";
    }

    @Override
    public List<Formula> formulas()
    {
        Set<String> person = Set.of("person");

        return List.of(
                Formula.of("people.lastOneOnOne", person, Set.of(ONE_ON_ONES), PeopleSignalsFormulas::lastOneOnOne),
                Formula.of("people.kudosReceived", Set.of("person", "period"), Set.of(CultureInputsFormulas.KUDOS), PeopleSignalsFormulas::kudos),
                Formula.of("people.trainingBehind", person, TRAINING_TOPICS, PeopleSignalsFormulas::trainingBehind),
                Formula.of("people.trainingLoad", person, TRAINING_TOPICS, PeopleSignalsFormulas::trainingLoad),
                Formula.of("people.wins", person, Set.of(CultureInputsFormulas.KUDOS, VisitFormulas.VISITS, TrainingFormulas.TRAINING,
                        ReviewFormulas.REVIEWS), PeopleSignalsFormulas::wins),
                Formula.of("people.progress", person, Set.of(TrainingFormulas.TRAINING, ReviewFormulas.REVIEWS, VisitFormulas.VISITS,
                        CalcService.PEOPLE), PeopleSignalsFormulas::progress));
    }

    // ------------------------------------------------------------------ 1:1s and kudos

    private static Future<Object> lastOneOnOne(CalcContext ctx, JsonObject params)
    {
        return installed(ctx, "one_on_ones").compose(ok -> !ok ? Future.succeededFuture(null)
                : ctx.sql().queryOne("SELECT max(held_on)::text AS last FROM one_on_ones WHERE person_id = $1 AND held_on <= $2",
                        Tuple.of(person(params), ctx.today())).map(r -> r == null ? null : r.getValue("last")));
    }

    private static Future<Object> kudos(CalcContext ctx, JsonObject params)
    {
        return installed(ctx, "kudos").compose(ok -> !ok ? Future.succeededFuture(null)
                : ctx.get("core.period", new JsonObject().put("period", params.getString("period"))).compose(p ->
                {
                    JsonObject period = (JsonObject) p;

                    return ctx.sql().queryOne("""
                            SELECT count(*)::int AS n FROM kudos
                            WHERE person_id = $1 AND (given_at AT TIME ZONE 'Asia/Kolkata')::date BETWEEN $2 AND $3
                            """,
                            Tuple.of(person(params), LocalDate.parse(period.getString("from")), LocalDate.parse(period.getString("to"))))
                            .map(r -> (Object) r.getInteger("n"));
                }));
    }

    // ------------------------------------------------------------------ training

    private static Future<Object> trainingBehind(CalcContext ctx, JsonObject params)
    {
        LocalDate today = ctx.today();

        return assignments(ctx, "a.person_id = $1", person(params)).map(rows ->
        {
            if (rows == null)
            {
                return null;
            }

            JsonArray out = new JsonArray();

            for (JsonObject a : rows)
            {
                if (!TrainingLifecycle.finished(stage(a, today)))
                {
                    out.add(new JsonObject().put("plan", a.getString("plan")).put("overdue", overdue(a, today)));
                }
            }

            return out;
        });
    }

    private static Future<Object> trainingLoad(CalcContext ctx, JsonObject params)
    {
        LocalDate today = ctx.today();

        UUID id = person(params);

        Future<List<JsonObject>> own = assignments(ctx, "a.person_id = $1", id);

        Future<List<JsonObject>> mentored = assignments(ctx, "a.mentor_id = (SELECT user_id FROM people WHERE id = $1)", id);

        return Future.all(own, mentored).map(all ->
        {
            if (own.result() == null)
            {
                return null;
            }

            JsonArray plans = new JsonArray();

            for (JsonObject a : own.result())
            {
                if (active(stage(a, today)))
                {
                    plans.add(new JsonObject()
                            .put("hoursPerWeek", TrainingLifecycle.weeklyHours(new JsonObject().put("modules", a.getJsonArray("modules"))
                                    .put("weeks", a.getInteger("weeks"))))
                            .put("end", TrainingLifecycle.end(a, a.getInteger("weeks")).toString()));
                }
            }

            int mentees = (int) mentored.result().stream().filter(a -> active(stage(a, today))).count();

            return new JsonObject().put("plans", plans).put("mentees", mentees);
        });
    }

    // ------------------------------------------------------------------ wins

    private static Future<Object> wins(CalcContext ctx, JsonObject params)
    {
        UUID id = person(params);

        LocalDate today = ctx.today();

        LocalDate from = today.minusDays(WIN_DAYS);

        Future<JsonArray> kudos = installed(ctx, "kudos").compose(ok -> !ok ? Future.succeededFuture(new JsonArray()) : ctx.sql().queryList("""
                SELECT coalesce(u.name, 'Slack') AS "from", k.culture_value AS value, k.message,
                       (k.given_at AT TIME ZONE 'Asia/Kolkata')::date::text AS at
                FROM kudos k LEFT JOIN users u ON u.id = k.from_user_id
                WHERE k.person_id = $1 AND (k.given_at AT TIME ZONE 'Asia/Kolkata')::date BETWEEN $2 AND $3
                """,
                Tuple.of(id, from, today)));

        Future<JsonArray> visits = ratedVisits(ctx, id, WIN_CSAT, from, today);

        Future<List<JsonObject>> training = assignments(ctx, "a.person_id = $1", id);

        return Future.all(kudos, visits, training).compose(all -> completed(ctx, training.result(), today)).map(done ->
        {
            JsonArray out = new JsonArray();

            for (Object o : kudos.result())
            {
                JsonObject k = (JsonObject) o;

                out.add(new JsonObject().put("kind", "kudos").put("what", "Kudos from " + k.getString("from") + " · " + k.getString("value"))
                        .put("text", k.getString("message")).put("at", k.getString("at")).put("value", k.getString("value")));
            }

            for (Object o : visits.result())
            {
                JsonObject v = (JsonObject) o;

                out.add(new JsonObject().put("kind", "visit")
                        .put("what", "Customer rated the visit " + csat(v) + "/5 · " + v.getString("customer"))
                        .put("text", v.getString("quote", "")).put("at", v.getString("at")).put("value", "Outcome Ownership"));
            }

            for (JsonObject c : done)
            {
                if (!LocalDate.parse(c.getString("at")).isBefore(from))
                {
                    out.add(new JsonObject().put("kind", "training").put("what", "Completed " + c.getString("plan"))
                            .put("text", c.getValue("rating") == null ? "Training completed" : "Training rating " + fixed(c.getValue("rating")) + " / 5")
                            .put("at", c.getString("at")).put("value", "Adaptability"));
                }
            }

            out.getList().sort((a, b) -> String.valueOf(((JsonObject) b).getString("at")).compareTo(String.valueOf(((JsonObject) a).getString("at"))));

            return (Object) out;
        });
    }

    // ------------------------------------------------------------------ progress

    private static Future<Object> progress(CalcContext ctx, JsonObject params)
    {
        UUID id = person(params);

        LocalDate today = ctx.today();

        Future<List<JsonObject>> own = assignments(ctx, "a.person_id = $1", id);

        Future<List<JsonObject>> mentored = assignments(ctx, "a.mentor_id = (SELECT user_id FROM people WHERE id = $1)", id);

        Future<JsonArray> delighted = ratedVisits(ctx, id, 5, LocalDate.of(2000, 1, 1), today);

        Future<JsonArray> reportsDue = installed(ctx, "visits").compose(ok -> !ok ? Future.succeededFuture(new JsonArray()) : ctx.sql().queryList(
                "SELECT customer FROM visits WHERE person_id = $1 AND reported_on IS NULL AND to_on < $2 ORDER BY to_on", Tuple.of(id, today)));

        Future<Integer> toGive = installed(ctx, "review_reviewer_keys").compose(ok -> !ok ? Future.succeededFuture(0) : ctx.sql().queryOne("""
                SELECT count(*)::int AS n FROM review_requests r JOIN review_cycles c ON c.id = r.cycle_id
                JOIN review_reviewer_keys k ON k.pseudonym = r.reviewer_key JOIN people p ON p.user_id = k.user_id
                WHERE p.id = $1 AND r.status IN ('not_started', 'in_progress') AND c.status <> 'published'
                """,
                Tuple.of(id)).map(r -> r.getInteger("n")));

        Future<JsonArray> checkIns = installed(ctx, "training_checkins").compose(ok -> !ok ? Future.succeededFuture(new JsonArray()) : ctx.sql().queryList("""
                SELECT c.assignment_id AS "assignmentId", c.period_start::text AS "periodStart", c.status, c.comment
                FROM training_checkins c JOIN training_assignments a ON a.id = c.assignment_id WHERE a.person_id = $1
                """,
                Tuple.of(id)));

        Future<JsonArray> holidays = ctx.sql().queryList("SELECT holiday_on AS date, name, location FROM holidays", Tuple.tuple());

        return Future.all(List.of(own, mentored, delighted, reportsDue, toGive, checkIns, holidays))
                .compose(all -> completed(ctx, own.result(), today).compose(done -> ratings(ctx, own.result()).map(ratings ->
                {
                    if (own.result() == null)
                    {
                        return null;
                    }

                    JsonArray achievements = new JsonArray();

                    for (Object o : delighted.result())
                    {
                        achievements.add(achievement("Delighted customer", ((JsonObject) o).getString("customer") + " rated the visit 5 / 5",
                                "Outcome Ownership"));
                    }

                    for (JsonObject c : done)
                    {
                        achievements.add(achievement("Training completed", c.getString("plan")
                                + (c.getValue("rating") == null ? "" : " · rating " + fixed(c.getValue("rating"))), "Adaptability"));
                    }

                    if (!mentored.result().isEmpty())
                    {
                        achievements.add(achievement("Mentor", "Mentoring " + mentored.result().size() + " plan(s)", "Collaboration"));
                    }

                    JsonArray steps = new JsonArray();

                    int give = toGive.result();

                    if (give > 0)
                    {
                        steps.add(step(give + " review" + (give > 1 ? "s" : "") + " to give (360° / checkpoints)", "reviews", "360", true));
                    }

                    Set<LocalDate> days = holidaySet(holidays.result());

                    JsonArray training = new JsonArray();

                    for (JsonObject a : own.result())
                    {
                        String stage = stage(a, today);

                        String plan = a.getString("plan");

                        JsonArray mine = new JsonArray();

                        checkIns.result().forEach(c -> {
                            if (a.getString("id").equals(((JsonObject) c).getString("assignmentId")))
                            {
                                mine.add(c);
                            }
                        });

                        JsonObject acks = object(a.getValue("acknowledgements"));

                        long unacked = TrainingLifecycle.feedbackKeys(a, mine).stream().filter(k -> !acks.containsKey((String) k)).count();

                        if (unacked > 0)
                        {
                            steps.add(step(unacked + " training feedback item(s) to acknowledge · " + plan, "training", null, false));
                        }

                        if (TrainingLifecycle.SIGN_OFF.equals(stage))
                        {
                            steps.add(step("Sign off the final feedback for " + plan, "training", null, false));
                        }

                        if (TrainingLifecycle.IN_TRAINING.equals(stage) && checkInDue(a, mine, days, today))
                        {
                            steps.add(step(a.getString("cadence") + " training check-in due · " + plan, "training", null, false));
                        }

                        Object rating = ratings.getValue(a.getString("id"));

                        training.add(new JsonObject().put("plan", plan).put("stage", stage).put("rating", rating));
                    }

                    for (Object o : reportsDue.result())
                    {
                        steps.add(step("Visit report due: " + ((JsonObject) o).getString("customer"), "visits", null, false));
                    }

                    return (Object) new JsonObject().put("achievements", achievements).put("nextSteps", steps).put("training", training);
                })));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The person's (or mentor's) assignments with their plan version, or null without the training
     * tables: {@code {id, plan, weeks, modules, startOn, endOn, outcome, assessment, finalReviews, signOff,
     * acknowledgements, progress, history, attempt, cadence}}.
     */
    private static Future<List<JsonObject>> assignments(CalcContext ctx, String where, UUID id)
    {
        return installed(ctx, "training_assignments").compose(ok -> !ok ? Future.succeededFuture(null) : ctx.sql().queryList("""
                SELECT a.id, v.name AS plan, v.weeks, v.modules, a.start_on::text AS "startOn", a.end_on::text AS "endOn", a.outcome,
                       a.assessment, a.final_reviews AS "finalReviews", a.sign_off AS "signOff", a.acknowledgements,
                       a.module_progress AS progress, a.history, a.attempt, a.cadence
                FROM training_assignments a
                JOIN training_plan_versions v ON v.plan_id = a.plan_id AND v.plan_version = a.plan_version
                WHERE\s""" + where + " ORDER BY a.start_on",
                Tuple.of(id)).map(rows ->
        {
            List<JsonObject> out = new ArrayList<>();

            for (Object o : rows)
            {
                JsonObject a = (JsonObject) o;

                a.put("modules", array(a.getValue("modules")))
                        .put("finalReviews", object(a.getValue("finalReviews")))
                        .put("acknowledgements", object(a.getValue("acknowledgements")))
                        .put("progress", object(a.getValue("progress")))
                        .put("history", array(a.getValue("history")));

                if (a.getValue("assessment") instanceof String text)
                {
                    a.put("assessment", text.startsWith("{") ? new JsonObject(text) : null);
                }

                if (a.getValue("signOff") instanceof String text)
                {
                    a.put("signOff", text.startsWith("{") ? new JsonObject(text) : null);
                }

                out.add(a);
            }

            return out;
        }));
    }

    /** Completed assignments with plan, rating and the day they ended ({@code at}: sign-off, else end). */
    private static Future<List<JsonObject>> completed(CalcContext ctx, List<JsonObject> assignments, LocalDate today)
    {
        List<JsonObject> done = new ArrayList<>();

        if (assignments == null)
        {
            return Future.succeededFuture(done);
        }

        for (JsonObject a : assignments)
        {
            if (TrainingLifecycle.COMPLETED.equals(stage(a, today)))
            {
                JsonObject signOff = a.getValue("signOff") instanceof JsonObject s ? s : new JsonObject();

                String at = signOff.getString("at", TrainingLifecycle.end(a, a.getInteger("weeks")).toString());

                done.add(new JsonObject().put("id", a.getString("id")).put("plan", a.getString("plan")).put("at", at));
            }
        }

        return ratings(ctx, done).map(r ->
        {
            done.forEach(c -> c.put("rating", r.getValue(c.getString("id"))));

            return done;
        });
    }

    /** {@code people.trainingRating} score by assignment id. */
    private static Future<JsonObject> ratings(CalcContext ctx, List<JsonObject> assignments)
    {
        JsonObject out = new JsonObject();

        if (assignments == null || assignments.isEmpty())
        {
            return Future.succeededFuture(out);
        }

        List<Future<Object>> each = new ArrayList<>();

        for (JsonObject a : assignments)
        {
            each.add(ctx.optional("people.trainingRating", new JsonObject().put("assignment", a.getString("id"))));
        }

        return Future.all(each).map(all ->
        {
            for (int i = 0; i < assignments.size(); i++)
            {
                Object r = each.get(i).result();

                out.put(assignments.get(i).getString("id"), r instanceof JsonObject rating ? rating.getValue("score") : null);
            }

            return out;
        });
    }

    /** Visits of the person with feedback at least {@code min} CSAT, feedback recorded between two days. */
    private static Future<JsonArray> ratedVisits(CalcContext ctx, UUID person, double min, LocalDate from, LocalDate to)
    {
        return installed(ctx, "visits").compose(ok -> !ok ? Future.succeededFuture(new JsonArray()) : ctx.sql().queryList("""
                SELECT customer, feedback_csat::float8 AS csat, feedback_quote AS quote, feedback_on::text AS at FROM visits
                WHERE person_id = $1 AND feedback_csat >= $2 AND feedback_on BETWEEN $3 AND $4 ORDER BY feedback_on
                """,
                Tuple.of(person, min, from, to)));
    }

    private static String stage(JsonObject a, LocalDate today)
    {
        return TrainingLifecycle.stage(a, a.getInteger("weeks"), today);
    }

    /** {@code trActiveStage}: started and not finished. */
    private static boolean active(String stage)
    {
        return !TrainingLifecycle.NOT_STARTED.equals(stage) && !TrainingLifecycle.finished(stage);
    }

    /** {@code trOverdue}: modules not done whose week ended before today. */
    static int overdue(JsonObject a, LocalDate today)
    {
        LocalDate start = LocalDate.parse(a.getString("startOn"));

        JsonObject progress = a.getJsonObject("progress", new JsonObject());

        int n = 0;

        for (Object o : a.getJsonArray("modules", new JsonArray()))
        {
            JsonObject m = (JsonObject) o;

            if (!progress.containsKey(m.getString("id")) && start.plusDays(m.getInteger("week", 1) * 7L).isBefore(today))
            {
                n++;
            }
        }

        return n;
    }

    /** Whether the current check-in period is Due (prototype {@code pStat(a, cur) === 'Due'}). */
    private static boolean checkInDue(JsonObject a, JsonArray checkIns, Set<LocalDate> holidays, LocalDate today)
    {
        JsonArray updates = new JsonArray();

        checkIns.forEach(c -> updates.add(new JsonObject().put("period", ((JsonObject) c).getString("periodStart"))
                .put("status", ((JsonObject) c).getString("status"))));

        JsonObject state = TrainingRating.checkIns(new JsonObject().put("start", a.getString("startOn")).put("end", a.getString("endOn"))
                .put("weeks", a.getInteger("weeks")).put("cadence", a.getString("cadence")).put("updates", updates), holidays, today);

        return state.getJsonArray("periods", new JsonArray()).stream()
                .anyMatch(p -> ((JsonObject) p).getBoolean("current", false) && "Due".equals(((JsonObject) p).getString("status")));
    }

    private static Set<LocalDate> holidaySet(JsonArray rows)
    {
        Set<LocalDate> out = new HashSet<>();

        for (Object o : rows)
        {
            Object d = ((JsonObject) o).getValue("date");

            if (d != null)
            {
                out.add(LocalDate.parse(d.toString()));
            }
        }

        return out;
    }

    private static JsonObject achievement(String title, String detail, String value)
    {
        return new JsonObject().put("title", title).put("detail", detail).put("value", value);
    }

    private static JsonObject step(String text, String module, String tab, boolean selfOnly)
    {
        JsonObject target = new JsonObject().put("module", module);

        if (tab != null)
        {
            target.put("tab", tab);
        }

        return new JsonObject().put("text", text).put("target", target).put("selfOnly", selfOnly);
    }

    private static Future<Boolean> installed(CalcContext ctx, String table)
    {
        return ctx.sql().queryOne("SELECT to_regclass($1) IS NOT NULL AS installed", Tuple.of(table)).map(r -> r.getBoolean("installed"));
    }

    private static UUID person(JsonObject params)
    {
        return UUID.fromString(params.getString("person"));
    }

    private static String csat(JsonObject visit)
    {
        double v = visit.getDouble("csat");

        return v == Math.rint(v) ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fixed(Object value)
    {
        return value instanceof Number n ? String.format(Locale.ROOT, "%.1f", n.doubleValue()) : "—";
    }

    private static JsonObject object(Object value)
    {
        return value instanceof JsonObject o ? o : value instanceof String text && text.startsWith("{") ? new JsonObject(text) : new JsonObject();
    }

    private static JsonArray array(Object value)
    {
        return value instanceof JsonArray a ? a : value instanceof String text && text.startsWith("[") ? new JsonArray(text) : new JsonArray();
    }
}
