package com.pulse.calc.work;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.PeriodCalendar;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * OKR formulas of {@code calc/work} (task 14.2): the {@code work.okrPillar} input port that
 * {@code core.pillars} reads, the KPI row of an OKR list and one objective's progress. The maths is in
 * {@link OkrProgress}; this descriptor only loads the rows.
 */
public final class OkrFormulas implements FormulaModule
{

    /**
     * Objectives, key results or check-ins changed (the OKR service publishes it after every write).
     */
    public static final String OKRS = "work.okrs";

    public static final String PILLAR = "work.okrPillar";

    public static final String SUMMARY = "work.okrSummary";

    public static final String OBJECTIVE = "work.okrObjective";

    private static final String UNDEFINED_TABLE = "42P01";

    private static final String OBJECTIVES = """
            SELECT o.id,
                   coalesce((SELECT json_agg(json_build_object('id', k.id, 'start_value', k.start_value,
                                     'target_value', k.target_value, 'current_value', k.current_value)
                                     ORDER BY k.position)
                             FROM key_results k WHERE k.objective_id = o.id), '[]'::json) AS key_results
            FROM objectives o
            """;

    @Override
    public String id()
    {
        return "work";
    }

    @Override
    public List<Formula> formulas()
    {
        Set<String> topics = Set.of(OKRS, CalcService.importTopic("okrs"));

        return List.of(
                Formula.of(PILLAR, Set.of("person", "period"), topics, safe(OkrFormulas::pillar)),
                Formula.of(SUMMARY, Set.of("person", "team", "period"), topics, safe(OkrFormulas::summary)),
                Formula.of(OBJECTIVE, Set.of("objective"), topics, safe(OkrFormulas::objective)));
    }

    /**
     * Turns a bad parameter thrown while reading {@code params} into a failed future (answered as 400).
     */
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

    /**
     * {@code work.okrPillar {person, period}}: the OKR pillar of the quarter's Individual objectives,
     * or null when the person has none (the pillar then uses the manager's rating). Also null when the
     * OKR tables are not there, as in a test database built from the platform migrations only: no OKR
     * data, the same as before this port existed. In the application every module's migrations run
     * before the server starts, so the tables always exist.
     */
    private static Future<Object> pillar(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        return quarter(ctx, params)
                .compose(quarter -> ctx.sql().queryList(OBJECTIVES
                                + " WHERE o.level = 'Individual' AND o.owner_id = $1 AND o.quarter = $2", Tuple.of(person, quarter))
                        .recover(error -> error instanceof PgException pg && UNDEFINED_TABLE.equals(pg.getSqlState())
                                ? Future.succeededFuture(new JsonArray())
                                : Future.failedFuture(error)))
                .map(rows ->
                {
                    List<Double> progress = new ArrayList<>();

                    for (Object row : rows)
                    {
                        progress.add(OkrProgress.detail((JsonObject) row).getDouble("progress"));
                    }

                    return OkrProgress.pillar(progress);
                });
    }

    /**
     * {@code work.okrSummary {person | team, period}}: {@link OkrProgress#summary} of a person's
     * Individual objectives or a team's Team objectives in the quarter, plus the person's
     * {@code pillar}.
     */
    private static Future<Object> summary(CalcContext ctx, JsonObject params)
    {
        String team = params.getString("team");

        boolean forTeam = team != null && !team.isBlank();

        if (forTeam == (params.getValue("person") != null))
        {
            return Future.failedFuture(new IllegalArgumentException("person: Name a person or a team, not both."));
        }

        UUID person = forTeam ? null : uuid(params, "person");

        return quarter(ctx, params).compose(quarter ->
        {
            Future<JsonArray> rows = forTeam
                    ? ctx.sql().queryList(OBJECTIVES + " WHERE o.level = 'Team' AND o.team = $1 AND o.quarter = $2 "
                            + "ORDER BY o.created_at, o.id", Tuple.of(team, quarter))
                    : ctx.sql().queryList(OBJECTIVES + " WHERE o.level = 'Individual' AND o.owner_id = $1 "
                            + "AND o.quarter = $2 ORDER BY o.created_at, o.id", Tuple.of(person, quarter));

            Future<Object> pillar = forTeam ? Future.succeededFuture(null) : ctx.get(PILLAR, params);

            return Future.all(rows, pillar).map(all -> (Object) OkrProgress.summary(rows.result())
                    .put("quarter", quarter)
                    .put("pillar", pillar.result()));
        });
    }

    /**
     * {@code work.okrObjective {objective}}: {@link OkrProgress#detail} of one objective, or null when
     * it does not exist.
     */
    private static Future<Object> objective(CalcContext ctx, JsonObject params)
    {
        UUID id = uuid(params, "objective");

        return ctx.sql().queryOne(OBJECTIVES + " WHERE o.id = $1", Tuple.of(id))
                .map(row -> row == null ? null : OkrProgress.detail(row));
    }

    /**
     * The OKR quarter key ({@code 2026-Q3}) of a quarter period key ({@code q:Q3}, {@code q:2026-Q3}).
     */
    private static Future<String> quarter(CalcContext ctx, JsonObject params)
    {
        return ctx.calendar().compose(calendar ->
        {
            JsonObject period = calendar.period(params.getString("period"));

            if (!PeriodCalendar.QUARTER.equals(period.getString("kind")))
            {
                return Future.failedFuture(new IllegalArgumentException("period: '" + params.getString("period")
                        + "' is not a quarter; OKRs are quarterly."));
            }

            return Future.succeededFuture(CalcService.snapshotKey(period).substring(2));
        });
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
