package com.pulse.calc.people;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
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
 * </ul>
 *
 * <p>The input port {@code people.trainingPlans} that {@code core.training} reads (docs/calc.md §5) is
 * not registered yet: the core tests run without the training tables (see ReviewFormulas).
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
                Formula.of("people.trainingOverall", Set.of("person"), TOPICS, TrainingFormulas::overall));
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

                        JsonObject person = TrainingRating.person(plans);

                        return (Object) person.put("band", TrainingRating.band(person.getDouble("score")));
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
