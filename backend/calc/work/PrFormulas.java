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

/**
 * PR standard formulas of {@code calc/work} (task 10.1): the loaders around {@link PrStandard}. PRs are
 * read with their reviews and each reviewer's comments; a comment belongs to the review it names, or to
 * its author's review on that PR. The standard (title rule, size limit, approvals, comment length and
 * which checks are on) comes from the rules in force.
 */
public final class PrFormulas implements FormulaModule
{

    /**
     * Pull requests, reviews or comments changed (ingest of TFS and GitHub, task 5.4).
     */
    public static final String PULL_REQUESTS = "work.pullRequests";

    public static final String PERSON = "work.prStats";

    public static final String TEAM = "work.prTeam";

    public static final String DETAIL = "work.prDetail";

    private static final String PR_ROW = """
            SELECT p.id, p.number, p.author_id AS "authorId", p.title, p.suggested_title AS "suggestedTitle",
                   p.work_item_key AS "workItemKey", p.status,
                   to_char(p.opened_at AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM-DD') AS "openedAt",
                   p.lines_changed AS "linesChanged", p.has_summary AS "hasSummary", p.has_testing AS "hasTesting",
                   p.has_risk AS "hasRisk", p.build_passed AS "buildPassed", p.first_review_hours AS "firstReviewHours",
                   coalesce((SELECT json_agg(json_build_object(
                                 'reviewerId', r.reviewer_id, 'vote', r.vote,
                                 'comments', coalesce((SELECT json_agg(json_build_object('body', c.body, 'isResolved', c.is_resolved)
                                                                ORDER BY c.commented_at, c.id)
                                                       FROM pr_comments c
                                                       WHERE c.pull_request_id = p.id
                                                         AND (c.review_id = r.id
                                                              OR (c.review_id IS NULL AND c.author_id = r.reviewer_id))),
                                                      '[]'::json))
                                 ORDER BY r.reviewed_at NULLS LAST, r.id)
                             FROM pr_reviews r WHERE r.pull_request_id = p.id), '[]'::json) AS reviews
            FROM pull_requests p
            """;

    @Override
    public String id()
    {
        return "work";
    }

    @Override
    public List<Formula> formulas()
    {
        Set<String> topics = Set.of(PULL_REQUESTS, CalcService.PEOPLE, CalcService.SPRINTS);

        return List.of(
                new Formula(PERSON, Set.of("person", "period"), topics, Set.of(PeriodCalendar.SPRINT), safe(PrFormulas::person)),
                Formula.of(TEAM, Set.of("team", "period"), topics, safe(PrFormulas::team)),
                Formula.of(DETAIL, Set.of("pr"), topics, safe(PrFormulas::detail)));
    }

    /**
     * {@code work.prStats {person, period}}: {@link PrStandard#person} over PRs opened in the period
     * ({@code all} for every PR).
     */
    private static Future<Object> person(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        return range(ctx, params).compose(range -> WorkSql.rows(ctx, PR_ROW + """
                         WHERE ($1::date IS NULL OR (p.opened_at AT TIME ZONE 'Asia/Kolkata')::date >= $1)
                           AND ($2::date IS NULL OR (p.opened_at AT TIME ZONE 'Asia/Kolkata')::date <= $2)
                           AND (p.author_id = $3 OR EXISTS (SELECT 1 FROM pr_reviews r
                                                            WHERE r.pull_request_id = p.id AND r.reviewer_id = $3))
                        ORDER BY p.opened_at, p.id""",
                        Tuple.tuple().addLocalDate(range[0]).addLocalDate(range[1]).addUUID(person))
                .map(rows -> (Object) PrStandard.person(person.toString(), list(rows), range[0], range[1], ctx.rules())
                        .put("period", params.getString("period"))));
    }

    /**
     * {@code work.prTeam {team, period}}: {@link PrStandard#team} over the people of a team
     * ({@code people.department}).
     */
    private static Future<Object> team(CalcContext ctx, JsonObject params)
    {
        String team = params.getString("team");

        if (team == null || team.isBlank())
        {
            throw new IllegalArgumentException("team: A team is required.");
        }

        return range(ctx, params).compose(range ->
        {
            Future<JsonArray> people = WorkSql.rows(ctx, "SELECT id FROM people WHERE department = $1 ORDER BY name, id",
                    Tuple.of(team));

            Future<JsonArray> prs = WorkSql.rows(ctx, PR_ROW + """
                             WHERE ($1::date IS NULL OR (p.opened_at AT TIME ZONE 'Asia/Kolkata')::date >= $1)
                               AND ($2::date IS NULL OR (p.opened_at AT TIME ZONE 'Asia/Kolkata')::date <= $2)
                            ORDER BY p.opened_at, p.id""",
                    Tuple.tuple().addLocalDate(range[0]).addLocalDate(range[1]));

            return Future.all(people, prs).map(all ->
            {
                List<String> ids = new ArrayList<>();

                people.result().forEach(p -> ids.add(((JsonObject) p).getString("id")));

                return (Object) PrStandard.team(ids, list(prs.result()), range[0], range[1], ctx.rules())
                        .put("team", team)
                        .put("period", params.getString("period"));
            });
        });
    }

    /**
     * {@code work.prDetail {pr}}: one PR's checks with their reasons and a {@code state} of pass, fail or
     * pending (a pending check's {@code ok} reads as not applicable), its score and each reviewer's
     * comments with their class; null when it does not exist.
     */
    private static Future<Object> detail(CalcContext ctx, JsonObject params)
    {
        UUID id = uuid(params, "pr");

        return WorkSql.row(ctx, PR_ROW + " WHERE p.id = $1", Tuple.of(id)).map(pr ->
        {
            if (pr == null)
            {
                return null;
            }

            JsonObject rules = ctx.rules();

            JsonArray reviews = new JsonArray();

            for (Object item : pr.getJsonArray("reviews", new JsonArray()))
            {
                JsonObject review = ((JsonObject) item).copy();

                JsonArray classed = new JsonArray();

                for (Object c : review.getJsonArray("comments", new JsonArray()))
                {
                    JsonObject comment = (JsonObject) c;

                    classed.add(comment.copy().put("class", PrStandard.commentClass(comment.getString("body"), rules).getString(1)));
                }

                reviews.add(review.put("comments", classed));
            }

            boolean stamp = false;

            for (Object item : reviews)
            {
                JsonObject review = (JsonObject) item;

                stamp |= review.getString("vote", "").startsWith("Approved") && review.getJsonArray("comments").isEmpty()
                        && pr.getInteger("linesChanged", 0) > PrStandard.RUBBER_STAMP_LINES;
            }

            JsonArray checks = new JsonArray();

            for (Object item : PrStandard.checks(pr, rules))
            {
                JsonObject check = ((JsonObject) item).copy();

                Boolean ok = check.getBoolean("ok");

                checks.add(check.put("state", ok == null ? "pending" : ok ? "pass" : "fail"));
            }

            return new JsonObject()
                    .put("id", pr.getString("id"))
                    .put("checks", checks)
                    .put("score", PrStandard.score(pr, rules))
                    .put("titleOk", PrStandard.titleOk(pr.getString("title"), rules))
                    .put("overFirstReviewSla", Workload.orZero(Workload.number(pr, "firstReviewHours"))
                            > rules.getDouble("prSla", 24.0))
                    .put("rubberStamp", stamp)
                    .put("reviews", reviews);
        });
    }

    // ------------------------------------------------------------------ helpers

    /**
     * [from, to] of the period; both null for {@code all}.
     */
    private static Future<LocalDate[]> range(CalcContext ctx, JsonObject params)
    {
        return ctx.calendar().map(calendar ->
        {
            JsonObject period = calendar.period(params.getString("period"));

            if (PeriodCalendar.ALL.equals(period.getString("kind")))
            {
                return new LocalDate[] {null, null};
            }

            return new LocalDate[] {LocalDate.parse(period.getString("from")), LocalDate.parse(period.getString("to"))};
        });
    }

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

    private static List<JsonObject> list(JsonArray rows)
    {
        List<JsonObject> out = new ArrayList<>();

        rows.forEach(row -> out.add((JsonObject) row));

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
