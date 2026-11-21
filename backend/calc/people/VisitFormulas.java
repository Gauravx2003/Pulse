package com.pulse.calc.people;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.WorkingDays;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Customer-visit formulas (task 18.1), Track 3's second {@code calc/people} descriptor. The maths is in
 * {@link VisitPoints}; this class loads the inputs.
 *
 * <ul>
 *   <li>{@code people.visitPoints {visit}}: {@code {points: {parts, total}, values}} for one visit.</li>
 *   <li>{@code people.visitStats {person, period}}: {@link VisitPoints#stats} for visits starting in
 *       the period (the screen uses {@code q:Q3} or {@code all} for the year to date).</li>
 *   <li>{@code people.visitMonth {person, period}}: a month's visit points under the monthly cap
 *       ({@code m:2026-09}).</li>
 * </ul>
 *
 * <p>The visits module publishes {@link #VISITS} after every write; imports invalidate through
 * {@code imports.visits}, and holiday imports through {@code imports.holidays}.
 */
public final class VisitFormulas implements FormulaModule
{
    /** Topic of the {@code visits} table. */
    public static final String VISITS = "people.visits";

    private static final Set<String> TOPICS = Set.of(VISITS, CalcService.importTopic("visits"), CalcService.importTopic("holidays"));

    private static final String COLUMNS = """
            SELECT from_on AS "from", to_on AS "to", purpose, reported_on AS "reportedOn",
                   report_onsite_hours AS "onsiteHours", report_travel_hours AS "travelHours",
                   feedback_csat AS csat, feedback_again AS "askAgain", verification
            FROM visits
            """;

    @Override
    public String id()
    {
        return "people";
    }

    @Override
    public List<Formula> formulas()
    {
        return List.of(
                Formula.of("people.visitPoints", Set.of("visit"), TOPICS, VisitFormulas::visitPoints),
                Formula.of("people.visitStats", Set.of("person", "period"), TOPICS, VisitFormulas::visitStats),
                Formula.of("people.visitMonth", Set.of("person", "period"), TOPICS, VisitFormulas::visitMonth));
    }

    private static Future<Object> visitPoints(CalcContext ctx, JsonObject params)
    {
        Future<JsonObject> visit = ctx.sql().queryOne(COLUMNS + " WHERE id = $1", Tuple.of(UUID.fromString(params.getString("visit"))));

        return Future.all(visit, holidays(ctx)).map(all ->
        {
            if (visit.result() == null)
            {
                return null;
            }

            Set<LocalDate> days = all.resultAt(1);

            return (Object) new JsonObject()
                    .put("points", VisitPoints.points(visit.result(), days, pts(ctx)))
                    .put("values", VisitPoints.values(visit.result(), days));
        });
    }

    private static Future<Object> visitStats(CalcContext ctx, JsonObject params)
    {
        Future<Object> period = ctx.get("core.period", new JsonObject().put("period", params.getString("period")));

        Future<JsonArray> visits = ctx.sql().queryList(COLUMNS + " WHERE person_id = $1",
                Tuple.of(UUID.fromString(params.getString("person"))));

        return Future.all(period, visits, holidays(ctx)).map(all ->
        {
            JsonObject p = (JsonObject) period.result();

            return (Object) VisitPoints.stats(visits.result(), LocalDate.parse(p.getString("from")), LocalDate.parse(p.getString("to")),
                    ctx.today(), all.resultAt(2), pts(ctx));
        });
    }

    private static Future<Object> visitMonth(CalcContext ctx, JsonObject params)
    {
        return ctx.get("core.period", new JsonObject().put("period", params.getString("period"))).compose(result ->
        {
            JsonObject p = (JsonObject) result;

            Future<JsonArray> visits = ctx.sql().queryList(COLUMNS + " WHERE person_id = $1 AND from_on BETWEEN $2 AND $3",
                    Tuple.of(UUID.fromString(params.getString("person")), LocalDate.parse(p.getString("from")),
                            LocalDate.parse(p.getString("to"))));

            Number cap = ctx.rules().getNumber("visitCap");

            return Future.all(visits, holidays(ctx)).map(all -> (Object) VisitPoints.month(visits.result(), all.resultAt(1), pts(ctx),
                    cap == null ? 8 : cap.doubleValue()));
        });
    }

    /** Organisation-wide holidays (a visit day on one adds weekend/holiday points). */
    private static Future<Set<LocalDate>> holidays(CalcContext ctx)
    {
        return ctx.sql().queryList("SELECT holiday_on AS date, name, location FROM holidays", Tuple.tuple())
                .map(rows -> WorkingDays.holidays(rows, null));
    }

    private static JsonObject pts(CalcContext ctx)
    {
        JsonObject pts = ctx.rules().getJsonObject("pts");

        return pts == null ? new JsonObject() : pts;
    }
}
