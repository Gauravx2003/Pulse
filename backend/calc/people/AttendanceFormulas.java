package com.pulse.calc.people;

import com.pulse.calc.core.Attendance;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Attendance formulas (task 17.1), loading the inputs of {@link Attendance#stats} (task 4.6).
 *
 * <ul>
 *   <li>{@code people.attendance {person, period}}: {@link Attendance#stats} for the period (month
 *       {@code m:2026-08}, sprint or quarter), cut at today and counted only in uploaded months.</li>
 *   <li>{@code people.attendanceDiscipline {person}}: {@code {score, absent}} for the current quarter,
 *       the input port of Discipline rule h ({@code core.discipline}); null with no uploaded working day.</li>
 *   <li>{@code people.leaveDays {person}}: the person's full leave and absent days ({@code L}, {@code S},
 *       {@code A}), which leave capacity and every working-day denominator
 *       ({@link WorkingDays} person {@code leave}).</li>
 * </ul>
 *
 * <p>The attendance module publishes {@link #ATTENDANCE} after a write; uploads and their undo invalidate
 * through {@code imports.attendance}. When the attendance tables are not installed (core-only tests) every
 * formula reads as no data.
 */
public final class AttendanceFormulas implements FormulaModule
{
    /** Topic of the {@code attendance_records} and {@code attendance_uploads} tables. */
    public static final String ATTENDANCE = "people.attendance";

    private static final Set<String> TOPICS = Set.of(ATTENDANCE, CalcService.importTopic("attendance"), CalcService.importTopic("holidays"),
            VisitFormulas.VISITS, CalcService.importTopic("visits"), CalcService.PEOPLE);

    private static final Set<String> FULL_DAY_OFF = Set.of("L", "S", "A");

    @Override
    public String id()
    {
        return "people";
    }

    @Override
    public List<Formula> formulas()
    {
        return List.of(
                Formula.of("people.attendance", Set.of("person", "period"), TOPICS, AttendanceFormulas::stats),
                Formula.of("people.attendanceDiscipline", Set.of("person"), TOPICS, AttendanceFormulas::discipline),
                Formula.of("people.leaveDays", Set.of("person"), TOPICS, AttendanceFormulas::leaveDays));
    }

    private static Future<Object> stats(CalcContext ctx, JsonObject params)
    {
        return ctx.get("core.period", new JsonObject().put("period", params.getString("period"))).compose(result ->
        {
            JsonObject period = (JsonObject) result;

            return load(ctx, UUID.fromString(params.getString("person")), LocalDate.parse(period.getString("from")),
                    LocalDate.parse(period.getString("to")));
        });
    }

    private static Future<Object> discipline(CalcContext ctx, JsonObject params)
    {
        return ctx.get("people.attendance", params.copy().put("period", currentQuarter(ctx.today()))).map(result ->
        {
            if (!(result instanceof JsonObject s) || s.getInteger("workingDays", 0) == 0)
            {
                return null;
            }

            return new JsonObject().put("score", s.getValue("score")).put("absent", s.getValue("absent"));
        });
    }

    private static Future<Object> leaveDays(CalcContext ctx, JsonObject params)
    {
        return installed(ctx).compose(tables ->
        {
            if (!tables.getBoolean("attendance"))
            {
                return Future.succeededFuture(new JsonArray());
            }

            return ctx.sql().queryList("SELECT day FROM attendance_records WHERE person_id = $1 AND status = ANY($2) ORDER BY day",
                            Tuple.of(UUID.fromString(params.getString("person")), (Object) FULL_DAY_OFF.toArray(new String[0])))
                    .map(rows ->
                    {
                        JsonArray days = new JsonArray();

                        rows.forEach(r -> days.add(((JsonObject) r).getString("day")));

                        return days;
                    });
        });
    }

    /** {@code attStats} inputs for one person between two days; null when the person does not exist. */
    private static Future<Object> load(CalcContext ctx, UUID person, LocalDate from, LocalDate to)
    {
        return installed(ctx).compose(tables ->
        {
            if (!tables.getBoolean("attendance"))
            {
                return Future.succeededFuture(null);
            }

            Future<JsonObject> who = ctx.sql().queryOne("SELECT joined_on AS \"joinedOn\" FROM people WHERE id = $1", Tuple.of(person));

            Future<JsonArray> records = ctx.sql().queryList("""
                    SELECT day, status, leave_type AS "leaveType", applied_on AS "appliedOn",
                           to_char(check_in, 'HH24:MI') AS "checkIn", to_char(check_out, 'HH24:MI') AS "checkOut"
                    FROM attendance_records WHERE person_id = $1 AND day BETWEEN $2 AND $3
                    """, Tuple.of(person, from, to));

            Future<JsonArray> visits = !tables.getBoolean("visits") ? Future.succeededFuture(new JsonArray())
                    : ctx.sql().queryList("SELECT from_on AS \"from\", to_on AS \"to\" FROM visits WHERE person_id = $1 AND to_on >= $2 "
                            + "AND from_on <= $3", Tuple.of(person, from, to));

            Future<JsonArray> holidays = ctx.sql().queryList("SELECT holiday_on AS date, name, location FROM holidays", Tuple.tuple());

            Future<JsonArray> uploads = ctx.sql().queryList("SELECT month FROM attendance_uploads", Tuple.tuple());

            return Future.all(who, records, visits, holidays, uploads).map(all ->
            {
                if (who.result() == null)
                {
                    return null;
                }

                JsonObject byDay = new JsonObject();

                for (Object o : records.result())
                {
                    JsonObject r = (JsonObject) o;

                    byDay.put(r.getString("day"), r);
                }

                Set<String> uploaded = new HashSet<>();

                uploads.result().forEach(u -> uploaded.add(((JsonObject) u).getString("month")));

                JsonObject rules = ctx.rules().getJsonObject("att", new JsonObject());

                return (Object) Attendance.stats(who.result(), byDay, visits.result(), WorkingDays.holidays(holidays.result(), null),
                        uploaded, from, to, ctx.today(), rules);
            });
        });
    }

    /** Which of the attendance and visits tables exist ({@code {attendance, visits}}). */
    private static Future<JsonObject> installed(CalcContext ctx)
    {
        return ctx.sql().queryOne("SELECT to_regclass('attendance_records') IS NOT NULL AS attendance, "
                + "to_regclass('visits') IS NOT NULL AS visits", Tuple.tuple());
    }

    /** The key of the quarter that contains {@code day}, e.g. {@code q:2026-Q3}. */
    static String currentQuarter(LocalDate day)
    {
        return "q:" + day.getYear() + "-Q" + ((day.getMonthValue() - 1) / 3 + 1);
    }
}
