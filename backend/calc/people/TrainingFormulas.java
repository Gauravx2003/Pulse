package com.pulse.calc.people;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.CoreThresholds;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.TrainingRating;
import com.pulse.calc.core.WorkingDays;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Training ratings (task 21.1 part C): loads an assignment's parts from the training tables and the
 * checkpoint reviews, and computes with Track 1's {@link TrainingRating} (task 4.7, oracle-verified).
 *
 * <ul>
 *   <li>{@code people.trainingRating {assignment}}: {@code {parts, score, final, state, band,
 *       checkIns}}; null for an unknown assignment.</li>
 *   <li>{@code people.trainingOverall {person}}: the plan-length-weighted overall rating
 *       {@code {score, final, n, band}} (Final ratings only when one exists).</li>
 *   <li>{@code people.onboarding {person}}: the input port of Track 2's {@code work.onboarding} (task 21.2):
 *       {@code {assignment, plan, progress, mentor}} while a new joiner (joined less than 3 months ago) is on
 *       a started, unfinished onboarding plan, else null. Utilization alerts and rebalance leave them out.
 *       Reads as null when the training tables are not installed.</li>
 *   <li>{@code people.menteeCount {person}}: Track 2's promotion-readiness port ("mentored 2 engineers",
 *       spec growth/roadmap) — the number of different people the person has mentored on a training plan
 *       that has started (running or finished); null when the training tables are not installed.</li>
 * </ul>
 *
 * <p>{@code people.trainingPlans {person}} is the input port {@code core.training} reads (docs/calc.md §5):
 * {@code [{weeks, rating}]}, null when the training tables are not installed, so the core-only tests
 * still see "no training".
 */
public final class TrainingFormulas implements FormulaModule
{
    /** Topic of the training tables; the training module publishes it after every change. */
    public static final String TRAINING = "people.training";

    private static final Set<String> TOPICS = Set.of(TRAINING, ReviewFormulas.REVIEWS, CalcService.importTopic("holidays"));

    @Override
    public String id()
    {
        return "people";
    }

    @Override
    public List<Formula> formulas()
    {
        return List.of(
                Formula.of("people.trainingRating", Set.of("assignment"), TOPICS, TrainingFormulas::rating),
                Formula.of("people.trainingOverall", Set.of("person"), TOPICS, TrainingFormulas::overall),
                Formula.of("people.trainingPlans", Set.of("person"), TOPICS, TrainingFormulas::trainingPlans),
                Formula.of("people.menteeCount", Set.of("person"), Set.of(TRAINING, CalcService.PEOPLE), TrainingFormulas::menteeCount),
                Formula.of("people.onboarding", Set.of("person"), Set.of(TRAINING, CalcService.PEOPLE), TrainingFormulas::onboarding));
    }

    /** Months after joining during which a person counts as a new joiner (prototype {@code isNewJoiner}). */
    static final int NEW_JOINER_MONTHS = CoreThresholds.NEW_JOINER_MONTHS;

    private static Future<Object> menteeCount(CalcContext ctx, JsonObject params)
    {
        UUID person = UUID.fromString(params.getString("person"));

        return ctx.sql().queryOne("SELECT to_regclass('training_assignments') IS NOT NULL AS installed", Tuple.tuple()).compose(t ->
                !t.getBoolean("installed") ? Future.succeededFuture(null) : ctx.sql().queryOne("""
                        SELECT count(DISTINCT a.person_id)::int AS n FROM training_assignments a JOIN people p ON p.user_id = a.mentor_id
                        WHERE p.id = $1 AND a.start_on <= $2
                        """,
                        Tuple.of(person, ctx.today())).map(row -> (Object) row.getInteger("n")));
    }

    private static Future<Object> onboarding(CalcContext ctx, JsonObject params)
    {
        UUID person = UUID.fromString(params.getString("person"));

        return ctx.sql().queryOne("SELECT to_regclass('training_assignments') IS NOT NULL AS installed", Tuple.tuple()).compose(t ->
        {
            if (!t.getBoolean("installed"))
            {
                return Future.succeededFuture(null);
            }

            return ctx.sql().queryOne("""
                    SELECT a.id AS assignment, v.name AS plan, v.modules, a.module_progress AS progress, m.name AS mentor
                    FROM training_assignments a
                    JOIN people p ON p.id = a.person_id
                    JOIN training_plan_versions v ON v.plan_id = a.plan_id AND v.plan_version = a.plan_version
                    LEFT JOIN users m ON m.id = a.mentor_id
                    WHERE a.person_id = $1 AND a.outcome IS NULL AND a.start_on <= $2
                      AND p.joined_on > $2 - make_interval(months => $3)
                      AND (v.name ILIKE '%onboarding%' OR v.audience ILIKE '%new %' OR v.audience ILIKE '%trainee%')
                    ORDER BY a.start_on DESC LIMIT 1
                    """,
                    Tuple.of(person, ctx.today(), NEW_JOINER_MONTHS)).map(row ->
            {
                if (row == null)
                {
                    return null;
                }

                JsonArray modules = row.getJsonArray("modules", new JsonArray());

                JsonObject done = row.getJsonObject("progress", new JsonObject());

                int finished = 0;

                for (Object o : modules)
                {
                    finished += o instanceof JsonObject m && done.containsKey(m.getString("id")) ? 1 : 0;
                }

                return (Object) new JsonObject()
                        .put("assignment", row.getString("assignment"))
                        .put("plan", row.getString("plan"))
                        .put("progress", modules.isEmpty() ? 0 : Math.round(100f * finished / modules.size()))
                        .put("mentor", row.getString("mentor"));
            });
        });
    }

    private static Future<Object> rating(CalcContext ctx, JsonObject params)
    {
        UUID id = UUID.fromString(params.getString("assignment"));

        Future<JsonObject> assignment = ctx.sql().queryOne("""
                SELECT a.start_on AS start, a.end_on AS "end", v.weeks, a.cadence, a.assessment AS assess,
                       a.final_reviews AS "final"
                FROM training_assignments a
                JOIN training_plan_versions v ON v.plan_id = a.plan_id AND v.plan_version = a.plan_version
                WHERE a.id = $1
                """,
                Tuple.of(id));

        Future<JsonArray> checkIns = ctx.sql().queryList(
                "SELECT period_start AS period, status FROM training_checkins WHERE assignment_id = $1", Tuple.of(id));

        Future<JsonArray> checkpoints = ctx.sql().queryList("""
                SELECT r.rater_group AS "group", r.status, r.scores
                FROM review_requests r JOIN review_cycles c ON c.id = r.cycle_id
                WHERE c.kind = 'training' AND c.assignment_id = $1
                """,
                Tuple.of(id));

        Future<JsonArray> holidays = ctx.sql().queryList("SELECT holiday_on AS date, name, location FROM holidays", Tuple.tuple());

        return Future.all(assignment, checkIns, checkpoints, holidays).map(all ->
        {
            JsonObject a = assignment.result();

            if (a == null)
            {
                return null;
            }

            a.put("updates", checkIns.result());

            if (a.getValue("final") == null)
            {
                a.put("final", new JsonObject());
            }

            JsonObject periods = TrainingRating.checkIns(a, WorkingDays.holidays(holidays.result(), null), ctx.today());

            Double checkpointAverage = TrainingRating.checkpointAverage(checkpointRequests(checkpoints.result()));

            return (Object) TrainingRating.rating(a, periods, checkpointAverage, ctx.rules())
                    .put("checkIns", periods.copy().put("periods", null));
        });
    }

    private static Future<Object> overall(CalcContext ctx, JsonObject params)
    {
        return plans(ctx, params).map(plans ->
        {
            JsonObject person = TrainingRating.person(plans);

            return (Object) person.put("band", TrainingRating.band(person.getDouble("score")));
        });
    }

    /**
     * The input port {@code people.trainingPlans}: every plan of the person with its rating, for
     * {@code core.training} (Growth pillar); null when the training tables are not installed (core-only
     * tests) or the person has no plan.
     */
    private static Future<Object> trainingPlans(CalcContext ctx, JsonObject params)
    {
        return ctx.sql().queryOne("SELECT to_regclass('training_assignments') IS NOT NULL AND to_regclass('review_cycles') IS NOT NULL "
                        + "AS installed", Tuple.tuple())
                .compose(t -> !t.getBoolean("installed") ? Future.succeededFuture(null)
                        : plans(ctx, params).map(plans -> plans.isEmpty() ? null : (Object) plans));
    }

    /** {@code [{weeks, rating}]} of every plan of the person, with {@code rating} from {@code people.trainingRating}. */
    private static Future<JsonArray> plans(CalcContext ctx, JsonObject params)
    {
        return ctx.sql().queryList("""
                SELECT a.id, v.weeks FROM training_assignments a
                JOIN training_plan_versions v ON v.plan_id = a.plan_id AND v.plan_version = a.plan_version
                WHERE a.person_id = $1
                """,
                Tuple.of(UUID.fromString(params.getString("person"))))
                .compose(rows ->
                {
                    List<Future<Object>> ratings = new ArrayList<>();

                    for (Object row : rows)
                    {
                        ratings.add(ctx.get("people.trainingRating", new JsonObject().put("assignment", ((JsonObject) row).getString("id"))));
                    }

                    return Future.all(ratings).map(all ->
                    {
                        JsonArray plans = new JsonArray();

                        for (int i = 0; i < rows.size(); i++)
                        {
                            plans.add(new JsonObject().put("weeks", rows.getJsonObject(i).getInteger("weeks"))
                                    .put("rating", (JsonObject) all.resultAt(i)));
                        }

                        return plans;
                    });
                });
    }

    /** Checkpoint requests as TrainingRating reads them: status "Submitted", scores in rated-value order. */
    static JsonArray checkpointRequests(JsonArray rows)
    {
        JsonArray out = new JsonArray();

        for (Object item : rows)
        {
            JsonObject row = (JsonObject) item;

            JsonArray scores = new JsonArray();

            for (String value : ReviewFormulas.RATED_VALUES)
            {
                scores.add(row.getJsonObject("scores").getValue(value));
            }

            out.add(new JsonObject()
                    .put("group", row.getString("group"))
                    .put("status", "submitted".equals(row.getString("status")) ? TrainingRating.SUBMITTED : row.getString("status"))
                    .put("scores", scores));
        }

        return out;
    }
}
