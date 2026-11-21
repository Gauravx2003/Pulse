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
 * </ul>
 *
 * <p>The appraisal input port {@code people.reviewCycles} (docs/calc.md §5) comes with task 22.1: core
 * tests run on a schema without the reviews tables, so registering it needs Track 1's test setup to
 * include module migrations first.
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
                Formula.of("people.review360Results", Set.of("cycle", "person"), Set.of(REVIEWS), ReviewFormulas::results));
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
