package com.pulse.calc.people;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.ResultGuard;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Track 3's culture input ports (docs/calc.md §5), ported from the prototype's {@code valData}
 * (app.js ~2202) for the parameters measured from people data:
 *
 * <ul>
 *   <li>{@code people.cultureInputs {person, period (quarter)}}: {@code {"<param key>": {raw}}} for
 *       {@code office_presence} (office policy %, left out when rule {@code att.inCulture} is off),
 *       {@code customer_visit_rating} (average CSAT of the quarter's visits with feedback),
 *       {@code training_rating} (the overall training rating), {@code kudos_received} (kudos given to the
 *       person in the quarter) and {@code incidents_handled} (done "incident" extra-mile items of the
 *       quarter plus reported Escalation visits that started in it). A parameter with no data is left
 *       out; {@code core.cultureData} treats it as missing.</li>
 *   <li>{@code people.disciplineOverride {person}}: the latest Discipline override that is not cleared,
 *       {@code {score, reason}}, or null (task 19.1, V370).</li>
 * </ul>
 *
 * <p>Every table is optional: a port reads only the tables that are installed (core-only tests).
 */
public final class CultureInputsFormulas implements FormulaModule
{
    /** Topic of the kudos table; the reviews module publishes it after a kudos is given. */
    public static final String KUDOS = "people.kudos";

    /** Topic of the Discipline overrides; the culture module publishes it after a change. */
    public static final String OVERRIDES = "people.disciplineOverrides";

    private static final Set<String> TOPICS = Set.of(AttendanceFormulas.ATTENDANCE, CalcService.importTopic("attendance"),
            VisitFormulas.VISITS, CalcService.importTopic("visits"), TrainingFormulas.TRAINING, ReviewFormulas.REVIEWS, KUDOS,
            "work.extraItems", CalcService.PEOPLE);

    @Override
    public String id()
    {
        return "people";
    }

    @Override
    public List<Formula> formulas()
    {
        return List.of(
                Formula.of("people.cultureInputs", Set.of("person", "period"), TOPICS, CultureInputsFormulas::cultureInputs),
                Formula.of("people.disciplineOverride", Set.of("person"), Set.of(OVERRIDES), CultureInputsFormulas::override));
    }

    private static Future<Object> cultureInputs(CalcContext ctx, JsonObject params)
    {
        UUID person = UUID.fromString(params.getString("person"));

        return ctx.get("core.period", new JsonObject().put("period", params.getString("period"))).compose(p ->
        {
            JsonObject period = (JsonObject) p;

            LocalDate from = LocalDate.parse(period.getString("from"));

            LocalDate to = LocalDate.parse(period.getString("to"));

            Future<JsonObject> tables = ctx.sql().queryOne("""
                    SELECT to_regclass('visits') IS NOT NULL AS visits, to_regclass('kudos') IS NOT NULL AS kudos,
                           to_regclass('extra_items') IS NOT NULL AS extra, to_regclass('training_assignments') IS NOT NULL
                           AND to_regclass('review_cycles') IS NOT NULL AS training
                    """, Tuple.tuple());

            Future<Object> attendance = ctx.get("people.attendance", params).map(ResultGuard::unguard);

            return Future.all(tables, attendance).compose(all ->
            {
                JsonObject t = tables.result();

                Future<JsonObject> csat = !t.getBoolean("visits") ? Future.succeededFuture(null) : ctx.sql().queryOne("""
                        SELECT avg(feedback_csat)::float8 AS avg FROM visits
                        WHERE person_id = $1 AND feedback_csat IS NOT NULL AND from_on BETWEEN $2 AND $3
                        """, Tuple.of(person, from, to));

                Future<JsonObject> escalations = !t.getBoolean("visits") ? Future.succeededFuture(null) : ctx.sql().queryOne("""
                        SELECT count(*)::int AS n FROM visits
                        WHERE person_id = $1 AND purpose = 'Escalation' AND reported_on IS NOT NULL AND from_on BETWEEN $2 AND $3
                        """, Tuple.of(person, from, to));

                Future<JsonObject> incidents = !t.getBoolean("extra") ? Future.succeededFuture(null) : ctx.sql().queryOne("""
                        SELECT count(*)::int AS n FROM extra_items
                        WHERE person_id = $1 AND kind = 'incident' AND is_done AND occurred_on BETWEEN $2 AND $3
                        """, Tuple.of(person, from, to));

                Future<JsonObject> kudos = !t.getBoolean("kudos") ? Future.succeededFuture(null) : ctx.sql().queryOne("""
                        SELECT count(*)::int AS n FROM kudos
                        WHERE person_id = $1 AND (given_at AT TIME ZONE 'Asia/Kolkata')::date BETWEEN $2 AND $3
                        """, Tuple.of(person, from, to));

                Future<Object> training = !t.getBoolean("training") ? Future.succeededFuture(null)
                        : ctx.get("people.trainingOverall", new JsonObject().put("person", params.getString("person"))).map(ResultGuard::unguard);

                return Future.all(List.of(csat, escalations, incidents, kudos, training)).map(done ->
                {
                    JsonObject inputs = new JsonObject();

                    boolean inCulture = ctx.rules().getJsonObject("att", new JsonObject()).getBoolean("inCulture", true);

                    if (inCulture && attendance.result() instanceof JsonObject s && s.getValue("officePct") != null)
                    {
                        inputs.put("office_presence", raw(s.getValue("officePct")));
                    }

                    if (csat.result() != null && csat.result().getValue("avg") != null)
                    {
                        inputs.put("customer_visit_rating", raw(csat.result().getValue("avg")));
                    }

                    if (training.result() instanceof JsonObject r && r.getValue("score") instanceof Number score)
                    {
                        inputs.put("training_rating", raw(score));
                    }

                    if (kudos.result() != null)
                    {
                        inputs.put("kudos_received", raw(kudos.result().getInteger("n")));
                    }

                    if (escalations.result() != null || incidents.result() != null)
                    {
                        int n = (escalations.result() == null ? 0 : escalations.result().getInteger("n"))
                                + (incidents.result() == null ? 0 : incidents.result().getInteger("n"));

                        inputs.put("incidents_handled", raw(n));
                    }

                    return (Object) inputs;
                });
            });
        });
    }

    private static Future<Object> override(CalcContext ctx, JsonObject params)
    {
        return ctx.sql().queryOne("SELECT to_regclass('discipline_overrides') IS NOT NULL AS installed", Tuple.tuple()).compose(t ->
                !t.getBoolean("installed") ? Future.succeededFuture(null) : ctx.sql().queryOne("""
                        SELECT score::float8 AS score, reason FROM discipline_overrides
                        WHERE person_id = $1 AND cleared_at IS NULL ORDER BY set_at DESC LIMIT 1
                        """, Tuple.of(UUID.fromString(params.getString("person")))).map(row -> (Object) row));
    }

    private static JsonObject raw(Object value)
    {
        return new JsonObject().put("raw", value);
    }
}
