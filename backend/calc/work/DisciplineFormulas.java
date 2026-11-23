package com.pulse.calc.work;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code work.disciplineInputs {person}} port of {@code core.discipline} (task 10.2, docs/calc.md
 * section 5): {@code {days, stale, replan, status, block, logSame, bulk, dod, review, standup}} as
 * {@code Discipline.metrics} reads them, null for a person with no Discipline data (the score stays
 * 3.0). The daily-update days and rules c-g come from {@code daily_updates} and
 * {@code discipline_inputs} (V250); stale tickets are the live sprint's; the PR review part of rule g
 * is the person's PR standard this month ({@code prDiscReview}), falling back to the stored value when
 * they have no PRs.
 */
public final class DisciplineFormulas implements FormulaModule
{

    public static final String INPUTS = "work.disciplineInputs";

    /**
     * Discipline inputs or daily updates changed.
     */
    public static final String DISCIPLINE = "work.discipline";

    @Override
    public String id()
    {
        return "work";
    }

    @Override
    public List<Formula> formulas()
    {
        Set<String> topics = Set.of(DISCIPLINE, WorkloadFormulas.ISSUES, PrFormulas.PULL_REQUESTS, CalcService.PEOPLE,
                CalcService.SPRINTS);

        return List.of(Formula.of(INPUTS, Set.of("person"), topics, WorkloadFormulas.safe(DisciplineFormulas::inputs)));
    }

    private static Future<Object> inputs(CalcContext ctx, JsonObject params)
    {
        UUID person = WorkloadFormulas.uuid(params, "person");

        Future<JsonObject> row = WorkSql.row(ctx, """
                SELECT replan_proactive AS "replanProactive", replan_late AS "replanLate", status_wrong AS "statusWrong",
                       status_checked AS "statusChecked", log_same_pct AS "logSame", bulk_entries AS bulk,
                       block_early AS "blockEarly", block_raised AS "blockRaised", dod_pct AS dod, review_pct AS review,
                       standup_pct AS standup
                FROM discipline_inputs WHERE person_id = $1""", Tuple.of(person));

        Future<JsonArray> days = WorkSql.rows(ctx, """
                SELECT to_char(day, 'YYYY-MM-DD') AS day, updated FROM daily_updates
                WHERE person_id = $1 ORDER BY day""", Tuple.of(person));

        return Future.all(row, days).compose(loaded ->
        {
            JsonObject in = row.result();

            if (in == null)
            {
                return Future.succeededFuture(null);
            }

            Future<Object> live = ctx.get(WorkloadFormulas.LIVE_SPRINT, params);

            Future<Object> prs = ctx.get(PrFormulas.PERSON, new JsonObject().put("person", person.toString())
                    .put("period", "m:" + YearMonth.from(ctx.today())));

            return Future.all(live, prs).map(all ->
            {
                JsonArray updates = new JsonArray();

                for (Object item : days.result())
                {
                    JsonObject day = (JsonObject) item;

                    updates.add(new JsonArray().add(day.getString("day")).add(day.getBoolean("updated")));
                }

                int stale = live.result() instanceof JsonObject m ? m.getJsonArray("stale", new JsonArray()).size() : 0;

                Object comp = prs.result() instanceof JsonObject s ? s.getValue("comp") : null;

                return (Object) new JsonObject()
                        .put("days", updates)
                        .put("stale", stale)
                        .put("replan", new JsonArray().add(in.getInteger("replanProactive")).add(in.getInteger("replanLate")))
                        .put("status", new JsonArray().add(in.getInteger("statusWrong")).add(in.getInteger("statusChecked")))
                        .put("block", new JsonArray().add(in.getInteger("blockEarly")).add(in.getInteger("blockRaised")))
                        .put("logSame", in.getInteger("logSame"))
                        .put("bulk", in.getInteger("bulk"))
                        .put("dod", in.getInteger("dod"))
                        .put("review", comp instanceof Number n ? (Object) n : in.getInteger("review"))
                        .put("standup", in.getInteger("standup"));
            });
        });
    }
}
