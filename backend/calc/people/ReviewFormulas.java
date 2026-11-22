package com.pulse.calc.people;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 360° formulas (task 20.3). The maths is in {@link Review360Results}.
 *
 * <ul>
 *   <li>{@code people.review360Results {cycle, person}}: the anonymity-safe results of one subject in
 *       one cycle (counts per group, averages of shown groups, weighted score, hidden strengths, blind
 *       spots, grouped comments). Null for an unknown cycle.</li>
 *   <li>{@code people.reviewCycles {person}}: the appraisal input port (docs/calc.md §5) — the person's
 *       360° cycles as subject, oldest first, {@code [{name, responses: [{group, status, scores}]}]} with
 *       {@code status} "Submitted" for a submitted review and {@code scores} in {@link #RATED_VALUES} order
 *       (null for Not observed), by due date. Training checkpoint cycles count too (same form, as in the
 *       prototype's {@code aprParts}); declined requests are left out and reviewer keys never leave the
 *       database. Null when the reviews tables are not installed (core-only tests)
 *       or the person has no cycle.</li>
 * </ul>
 *
 * <p>The reviews module publishes {@link #REVIEWS} after every assignment, submission, decline or
 * removal.
 */
public final class ReviewFormulas implements FormulaModule
{
    /** Topic of the 360° tables (review_cycles, review_requests). */
    public static final String REVIEWS = "people.review360";

    /** The 10 rated values, in order (the 11 culture values without Discipline). */
    public static final List<String> RATED_VALUES = List.of("Outcome Ownership", "Moral & Ethics", "Decision Making",
            "Problem Solving", "Risk Appetite", "Adaptability", "Agility in Execution", "Collaboration", "Resilience",
            "Resourcefulness");

    @Override
    public String id()
    {
        return "people";
    }

    @Override
    public List<Formula> formulas()
    {
        return List.of(
                Formula.of("people.review360Results", Set.of("cycle", "person"), Set.of(REVIEWS), ReviewFormulas::results),
                Formula.of("people.reviewCycles", Set.of("person"), Set.of(REVIEWS), ReviewFormulas::cycles));
    }

    private static Future<Object> cycles(CalcContext ctx, JsonObject params)
    {
        UUID person = UUID.fromString(params.getString("person"));

        return ctx.sql().queryOne("SELECT to_regclass('review_requests') IS NOT NULL AS installed", Tuple.tuple()).compose(t ->
        {
            if (!t.getBoolean("installed"))
            {
                return Future.succeededFuture(null);
            }

            return ctx.sql().queryList("""
                    SELECT c.id AS cycle, c.name, r.rater_group AS "group", r.status, r.scores
                    FROM review_cycles c JOIN review_requests r ON r.cycle_id = c.id
                    WHERE r.subject_id = $1 AND r.status <> 'declined'
                    ORDER BY c.due_on, c.created_at, c.id
                    """,
                    Tuple.of(person)).map(ReviewFormulas::byCycle);
        });
    }

    /** Rows of one subject's requests, ordered by cycle, as {@code people.reviewCycles} returns them. */
    static Object byCycle(JsonArray rows)
    {
        JsonArray cycles = new JsonArray();

        JsonObject current = null;

        for (Object o : rows)
        {
            JsonObject r = (JsonObject) o;

            if (current == null || !current.getString("id").equals(r.getString("cycle")))
            {
                current = new JsonObject().put("id", r.getString("cycle")).put("name", r.getString("name")).put("responses", new JsonArray());

                cycles.add(current);
            }

            JsonObject answers = r.getJsonObject("scores", new JsonObject());

            JsonArray scores = new JsonArray();

            RATED_VALUES.forEach(v -> scores.add(answers.getValue(v)));

            current.getJsonArray("responses").add(new JsonObject()
                    .put("group", r.getString("group"))
                    .put("status", "submitted".equals(r.getString("status")) ? "Submitted" : r.getString("status"))
                    .put("scores", scores));
        }

        for (Object c : cycles)
        {
            ((JsonObject) c).remove("id");
        }

        return cycles.isEmpty() ? null : cycles;
    }

    private static Future<Object> results(CalcContext ctx, JsonObject params)
    {
        UUID cycleId = UUID.fromString(params.getString("cycle"));

        Future<JsonObject> cycle = ctx.sql().queryOne(
                "SELECT anonymity_threshold AS threshold, weights FROM review_cycles WHERE id = $1", Tuple.of(cycleId));

        Future<JsonArray> responses = ctx.sql().queryList("""
                SELECT rater_group AS "group", status, scores, keep_doing AS "keepDoing", start_doing AS "startDoing"
                FROM review_requests WHERE cycle_id = $1 AND subject_id = $2 AND status <> 'declined'
                """,
                Tuple.of(cycleId, UUID.fromString(params.getString("person"))));

        return Future.all(cycle, responses).map(all -> cycle.result() == null ? null
                : (Object) Review360Results.of(responses.result(), RATED_VALUES, cycle.result().getInteger("threshold"),
                        cycle.result().getJsonObject("weights")));
    }
}
