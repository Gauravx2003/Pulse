package com.pulse.modules.pull_requests;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.PeriodCalendar;
import com.pulse.calc.core.ResultGuard;
import com.pulse.calc.work.PrFormulas;
import com.pulse.modules.work.PersonRepository;
import com.pulse.modules.work.Reports;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pull requests (task 10.2, spec work/pull-requests): the Person and Team tabs, the PR detail and the
 * standard, every number from the {@code work.pr*} formulas with the standard taken from Rules. Results
 * are signals for 1:1s, never a ranking. A PR is visible to whoever may see its author and to the people
 * who reviewed it.
 */
public final class PullRequestService
{

    /**
     * Rows of the period tables.
     */
    static final int HISTORY = 6;

    private final ModuleContext ctx;

    private final PolicyService policy;

    private final CalcService calc;

    PullRequestService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();

        this.calc = ctx.calc();
    }

    /**
     * The Person tab: {@code {person, period, stats, view, standard}}; for {@code all} the PRs are also
     * grouped by month ({@code groups}), and the period dropdown is hidden ({@code periodPicker: false}).
     */
    Future<JsonObject> person(JsonObject user, String personId, String periodKey)
    {
        return policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON).compose(scope -> period(periodKey).compose(period ->
        {
            JsonObject params = new JsonObject().put("person", personId).put("period", period.getString("key"));

            Future<Object> stats = calc.get(PrFormulas.PERSON, params);

            Future<Object> view = calc.get(PrFormulas.PERSON_VIEW, params);

            Future<Object> standard = calc.get(PrFormulas.STANDARD, new JsonObject());

            Future<JsonObject> identity = new PersonRepository(ctx.sql()).identity(scope, UUID.fromString(personId));

            Future<JsonArray> history = history(period).compose(keys -> personHistory(personId, keys));

            return Future.all(List.of(stats, view, standard, identity, history)).map(all ->
            {
                boolean everything = PeriodCalendar.ALL.equals(period.getString("kind"));

                JsonObject body = new JsonObject()
                        .put("person", identity.result())
                        .put("period", period)
                        .put("history", history.result())
                        .put("periodPicker", !everything)
                        .put("stats", stats.result())
                        .put("view", view.result())
                        .put("standard", standard.result());

                if (everything)
                {
                    body.put("groups", byMonth(((JsonObject) view.result()).getJsonArray("prs")));
                }

                return body;
            });
        }));
    }

    /**
     * The Team tab for a manager's reports (everyone for {@code all.view}): {@code {period, team, stats,
     * people, belowStandard, standard}}; {@code team} is the reports' team unless one of theirs is named.
     */
    Future<JsonObject> team(JsonObject user, String periodKey, String teamName)
    {
        return Reports.of(ctx, user).compose(people -> period(periodKey).compose(period ->
        {
            Future<JsonArray> teams = people.isEmpty() ? Future.succeededFuture(new JsonArray()) : ctx.sql().queryList("""
                    SELECT department AS team, count(*) AS people FROM people
                    WHERE id = ANY($1) AND department IS NOT NULL GROUP BY department ORDER BY count(*) DESC, department""",
                    Tuple.of(ids(people)));

            return teams.compose(rows ->
            {
                List<String> names = rows.stream().map(r -> ((JsonObject) r).getString("team")).toList();

                if (teamName != null && !names.contains(teamName))
                {
                    return Future.failedFuture(ApiException.badRequest("invalid_parameter", "Unknown team.").field("team"));
                }

                String chosen = teamName != null ? teamName : names.isEmpty() ? null : names.get(0);

                String key = period.getString("key");

                Future<Object> stats = chosen == null ? Future.succeededFuture(null)
                        : calc.get(PrFormulas.TEAM, new JsonObject().put("team", chosen).put("period", key));

                List<Future<Object>> rowsOf = new ArrayList<>();

                for (JsonObject person : people)
                {
                    rowsOf.add(calc.get(PrFormulas.PERSON, new JsonObject().put("person", person.getString("id")).put("period", key)));
                }

                Future<Object> standard = calc.get(PrFormulas.STANDARD, new JsonObject());

                Future<JsonArray> history = chosen == null ? Future.succeededFuture(new JsonArray())
                        : history(period).compose(keys -> teamHistory(chosen, keys));

                List<Future<?>> all = new ArrayList<>(rowsOf);

                all.add(stats);

                all.add(standard);

                all.add(history);

                return Future.all(all).compose(done ->
                {
                    JsonArray table = new JsonArray();

                    for (int i = 0; i < people.size(); i++)
                    {
                        JsonObject s = (JsonObject) rowsOf.get(i).result();

                        if (s.getJsonArray("mine", new JsonArray()).isEmpty() && s.getJsonArray("rv", new JsonArray()).isEmpty())
                        {
                            continue;
                        }

                        table.add(new JsonObject()
                                .put("id", people.get(i).getString("id"))
                                .put("name", people.get(i).getString("name"))
                                .put("prs", s.getJsonArray("mine").size())
                                .put("merged", s.getValue("merged"))
                                .put("comp", s.getValue("comp"))
                                .put("reviews", s.getJsonArray("rv").size())
                                .put("comments", s.getJsonArray("cm").size())
                                .put("stdPct", s.getValue("stdPct"))
                                .put("stamps", s.getValue("stamps"))
                                .put("firstReview", s.getValue("firstRev")));
                    }

                    JsonObject teamStats = stats.result() instanceof JsonObject t && !ResultGuard.isNotApplicable(t) ? t : null;

                    JsonArray below = teamStats == null ? new JsonArray() : teamStats.getJsonArray("belowStandard", new JsonArray());

                    return details(below).map(listed -> new JsonObject()
                            .put("period", period)
                            .put("history", history.result())
                            .put("team", chosen)
                            .put("teams", new JsonArray(new ArrayList<>(names)))
                            .put("stats", teamStats)
                            .put("people", table)
                            .put("belowStandard", listed)
                            .put("standard", standard.result()));
                });
            });
        }));
    }

    /**
     * One PR: its detail with checks, reviewers, classed comments, rewrites and the link to the source;
     * 404 when it does not exist or the caller may neither see its author nor reviewed it.
     */
    Future<JsonObject> detail(JsonObject user, UUID id)
    {
        return calc.get(PrFormulas.DETAIL, new JsonObject().put("pr", id.toString())).compose(result ->
        {
            if (!(result instanceof JsonObject detail) || ResultGuard.isNotApplicable(detail))
            {
                return Future.failedFuture(notFound());
            }

            String author = detail.getJsonObject("pr").getString("authorId");

            boolean reviewer = detail.getJsonArray("reviews").stream()
                    .anyMatch(r -> user.getString("personId") != null && user.getString("personId").equals(((JsonObject) r).getString("reviewerId")));

            if (reviewer)
            {
                return Future.succeededFuture(detail);
            }

            return author == null ? Future.failedFuture(notFound())
                    : policy.requireVisible(user, author, PolicyService.DOMAIN_PERSON).map(scope -> detail)
                    .recover(error -> Future.failedFuture(notFound()));
        });
    }

    /**
     * The PR standard in force.
     */
    Future<Object> standard()
    {
        return calc.get(PrFormulas.STANDARD, new JsonObject());
    }

    // ------------------------------------------------------------------ helpers

    private Future<JsonObject> period(String key)
    {
        String chosen = key == null || key.isBlank() ? "m:" + YearMonth.from(calc.clock().today()) : key;

        return calc.get("core.period", new JsonObject().put("period", chosen))
                .map(period -> (JsonObject) period)
                .recover(error -> error instanceof IllegalArgumentException
                        ? Future.failedFuture(ApiException.badRequest("invalid_parameter", "Unknown period.").field("period"))
                        : Future.failedFuture(error));
    }

    /**
     * The PRs merged below the standard, each with its detail (checks, lines, approvals, dates).
     */
    private Future<JsonArray> details(JsonArray ids)
    {
        List<Future<Object>> all = new ArrayList<>();

        for (Object id : ids)
        {
            all.add(calc.get(PrFormulas.DETAIL, new JsonObject().put("pr", String.valueOf(id))));
        }

        return Future.all(all).map(done ->
        {
            JsonArray out = new JsonArray();

            all.forEach(f -> out.add(f.result()));

            return out;
        });
    }

    /**
     * The chosen sprint or month and the {@value #HISTORY} - 1 before it, newest first; for All, the
     * last {@value #HISTORY} months.
     */
    private Future<List<JsonObject>> history(JsonObject period)
    {
        return calc.get("core.periods", new JsonObject()).map(result ->
        {
            JsonObject periods = (JsonObject) result;

            boolean sprint = PeriodCalendar.SPRINT.equals(period.getString("kind"));

            JsonArray list = sprint ? periods.getJsonArray("sprints") : periods.getJsonArray("months");

            List<JsonObject> out = new ArrayList<>();

            boolean found = !sprint && !"month".equals(period.getString("kind"));

            for (Object o : list)
            {
                JsonObject p = (JsonObject) o;

                found |= p.getString("key").equals(period.getString("key"));

                if (found && out.size() < HISTORY)
                {
                    out.add(p);
                }
            }

            return out;
        });
    }

    private Future<JsonArray> personHistory(String personId, List<JsonObject> periods)
    {
        List<Future<Object>> all = new ArrayList<>();

        for (JsonObject p : periods)
        {
            all.add(calc.get(PrFormulas.PERSON, new JsonObject().put("person", personId).put("period", p.getString("key"))));
        }

        return Future.all(all).map(done ->
        {
            JsonArray out = new JsonArray();

            for (int i = 0; i < periods.size(); i++)
            {
                JsonObject s = (JsonObject) all.get(i).result();

                out.add(new JsonObject()
                        .put("period", periods.get(i).getString("key"))
                        .put("short", periods.get(i).getString("short"))
                        .put("prs", s.getJsonArray("mine", new JsonArray()).size())
                        .put("merged", s.getValue("merged"))
                        .put("comp", s.getValue("comp"))
                        .put("titles", s.getValue("title"))
                        .put("size", s.getValue("size"))
                        .put("reviews", s.getJsonArray("rv", new JsonArray()).size())
                        .put("stdPct", s.getValue("stdPct"))
                        .put("stamps", s.getValue("stamps")));
            }

            return out;
        });
    }

    private Future<JsonArray> teamHistory(String team, List<JsonObject> periods)
    {
        List<Future<Object>> all = new ArrayList<>();

        for (JsonObject p : periods)
        {
            all.add(calc.get(PrFormulas.TEAM, new JsonObject().put("team", team).put("period", p.getString("key"))));
        }

        return Future.all(all).map(done ->
        {
            JsonArray out = new JsonArray();

            for (int i = 0; i < periods.size(); i++)
            {
                JsonObject s = (JsonObject) all.get(i).result();

                out.add(new JsonObject()
                        .put("period", periods.get(i).getString("key"))
                        .put("short", periods.get(i).getString("short"))
                        .put("prs", s.getValue("prs"))
                        .put("merged", s.getValue("merged"))
                        .put("comp", s.getValue("comp"))
                        .put("titlePct", s.getValue("titlePct"))
                        .put("comments", s.getValue("comments"))
                        .put("stdPct", s.getValue("stdPct"))
                        .put("stamps", s.getValue("stamps")));
            }

            return out;
        });
    }

    private static JsonArray byMonth(JsonArray prs)
    {
        Map<String, JsonArray> groups = new LinkedHashMap<>();

        for (Object item : prs)
        {
            JsonObject pr = (JsonObject) item;

            String opened = pr.getString("openedAt", "");

            groups.computeIfAbsent(opened.length() >= 7 ? opened.substring(0, 7) : opened, m -> new JsonArray()).add(pr);
        }

        JsonArray out = new JsonArray();

        groups.forEach((month, list) -> out.add(new JsonObject().put("month", month).put("prs", list)));

        return out;
    }

    private static UUID[] ids(List<JsonObject> people)
    {
        return people.stream().map(p -> UUID.fromString(p.getString("id"))).toArray(UUID[]::new);
    }

    private static ApiException notFound()
    {
        return ApiException.notFound("not_found", "Pull request not found.");
    }
}
