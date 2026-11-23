package com.pulse.modules.attendance;

import com.pulse.calc.core.Attendance;
import com.pulse.calc.core.ResultGuard;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import com.pulse.platform.policy.Scope;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Attendance views (spec time/attendance "Views and privacy"; task 17.1). The numbers are
 * {@code people.attendance} from calc/people ({@code Attendance.stats}); this service picks the people
 * and hides what the viewer may not see.
 *
 * <ul>
 *   <li>Team: the people in the viewer's attendance scope with their score, a day-by-day grid for a month
 *       or sprint, the monthly uploads and the not-uploaded banner.</li>
 *   <li>Person: KPIs, calendar, score build-up, leave list, late check-ins, month by month and the culture
 *       impact (Discipline rule h input, Collaboration presence).</li>
 *   <li>Check-in times (late check-ins, hours, the calendar's in/out) only for the person and their
 *       direct manager (policy domain {@code attendance.checkin}); employees see only their own record.</li>
 * </ul>
 */
final class AttendanceService
{
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    /** Stats fields that carry check-in times. */
    private static final List<String> CHECK_IN_FIELDS = List.of("lateDays", "hours", "hoursN", "avgHours", "timed", "late",
            "allowance", "lateExcess", "punctualityPct");

    private final ModuleContext ctx;

    private final PolicyService policy;

    AttendanceService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();
    }

    LocalDate today()
    {
        return ctx.calc() == null ? LocalDate.now(PolicyService.ZONE) : ctx.calc().clock().today();
    }

    static boolean canUpload(JsonObject user)
    {
        return PolicyService.permissions(user).contains("attendance.upload");
    }

    // ------------------------------------------------------------------ periods

    /**
     * The period dropdown: the kept months (flagged when not uploaded), recent sprints and quarters, and
     * the default (the last uploaded month).
     */
    Future<JsonObject> periods(JsonObject user)
    {
        LocalDate today = today();

        return Future.all(new AttendanceRepository(ctx.sql()).uploads(), ctx.calc().get("core.periods", new JsonObject())).map(all ->
        {
            Set<String> uploaded = uploadedMonths(all.resultAt(0));

            JsonArray months = new JsonArray();

            String latest = null;

            for (String m : AttendanceRules.months(today))
            {
                months.add(new JsonObject().put("key", "m:" + m).put("label", MONTH.format(YearMonth.parse(m)))
                        .put("uploaded", uploaded.contains(m)));

                latest = latest == null && uploaded.contains(m) ? "m:" + m : latest;
            }

            JsonArray sprints = new JsonArray();

            JsonArray quarters = new JsonArray();

            if (all.resultAt(1) instanceof JsonObject periods && periods.getJsonArray("sprints") != null)
            {
                for (Object o : periods.getJsonArray("sprints"))
                {
                    JsonObject p = (JsonObject) o;

                    String key = p.getString("key", "");

                    if (key.startsWith("s:") && sprints.size() < 12 && !LocalDate.parse(p.getString("from")).isAfter(today))
                    {
                        sprints.add(new JsonObject().put("key", key).put("label", p.getString("label")));
                    }
                }
            }

            int q = (today.getMonthValue() - 1) / 3 + 1;

            for (int i = 0; i < 2; i++)
            {
                int year = today.getYear() - (q - i <= 0 ? 1 : 0);

                int quarter = q - i <= 0 ? q - i + 4 : q - i;

                quarters.add(new JsonObject().put("key", "q:" + year + "-Q" + quarter).put("label", "Q" + quarter + " " + year));
            }

            return new JsonObject()
                    .put("months", months)
                    .put("sprints", sprints)
                    .put("quarters", quarters)
                    .put("default", latest == null ? "m:" + AttendanceRules.months(today).get(0) : latest)
                    .put("canUpload", canUpload(user));
        });
    }

    // ------------------------------------------------------------------ team

    /** The Team tab for a period. */
    Future<JsonObject> team(JsonObject user, String periodKey)
    {
        AttendanceRepository repo = new AttendanceRepository(ctx.sql());

        return period(periodKey).compose(period -> policy.scope(user, PolicyService.DOMAIN_ATTENDANCE).compose(scope ->
                Future.all(repo.people(scope), repo.uploads(), repo.holidays()).compose(all ->
                {
                    JsonArray people = all.resultAt(0);

                    LocalDate from = LocalDate.parse(period.getString("from"));

                    LocalDate to = LocalDate.parse(period.getString("to"));

                    UUID[] ids = ids(people);

                    List<Future<JsonObject>> stats = new ArrayList<>();

                    for (Object o : people)
                    {
                        stats.add(stats(((JsonObject) o).getString("id"), period.getString("key")));
                    }

                    boolean grid = ChronoUnit.DAYS.between(from, to) <= 31;

                    Future<JsonArray> records = grid ? repo.records(ids, from, to) : Future.succeededFuture(new JsonArray());

                    Future<JsonArray> visits = grid ? repo.visits(ids, from, to) : Future.succeededFuture(new JsonArray());

                    Future<Scope> checkins = policy.scope(user, PolicyService.DOMAIN_CHECKIN);

                    return Future.all(Future.all(stats), records, visits, checkins).map(more ->
                    {
                        Set<String> uploaded = uploadedMonths(all.resultAt(1));

                        Scope seeTimes = more.resultAt(3);

                        JsonArray rows = new JsonArray();

                        for (int i = 0; i < people.size(); i++)
                        {
                            JsonObject p = people.getJsonObject(i);

                            JsonObject s = stats.get(i).result();

                            rows.add(row(p, s, seeTimes.allows(p.getString("id"))));
                        }

                        JsonObject out = new JsonObject()
                                .put("period", period)
                                .put("kpis", kpis(rows))
                                .put("people", rows)
                                .put("missing", missing(from, to, uploaded))
                                .put("uploads", all.<JsonArray>resultAt(1))
                                .put("canUpload", canUpload(user));

                        if (grid)
                        {
                            out.put("grid", grid(people, more.resultAt(1), more.resultAt(2), holidays(all.resultAt(2)), uploaded, from, to));
                        }

                        return out;
                    });
                })));
    }

    private static JsonObject row(JsonObject person, JsonObject s, boolean times)
    {
        JsonObject row = new JsonObject().put("personId", person.getString("id")).put("name", person.getString("name"))
                .put("department", person.getString("department"));

        if (s == null || s.getInteger("workingDays", 0) == 0)
        {
            return row.put("workingDays", 0).put("score", null).put("band", Attendance.NO_DATA);
        }

        row.put("workingDays", s.getInteger("workingDays"))
                .put("office", s.getValue("office"))
                .put("wfh", s.getValue("wfh"))
                .put("duty", s.getValue("duty"))
                .put("leave", s.getValue("leave"))
                .put("shortNotice", s.getValue("shortNotice"))
                .put("absent", s.getValue("absent"))
                .put("officePct", s.getValue("officePct"))
                .put("noticePct", s.getValue("noticePct"))
                .put("score", s.getValue("score"))
                .put("band", s.getString("band"));

        if (times)
        {
            row.put("late", s.getValue("late")).put("punctualityPct", s.getValue("punctualityPct"));
        }

        return row;
    }

    private static JsonObject kpis(JsonArray rows)
    {
        int scored = 0;

        double scoreSum = 0;

        double leave = 0;

        double absent = 0;

        int talk = 0;

        for (Object o : rows)
        {
            JsonObject r = (JsonObject) o;

            if (r.getValue("score") instanceof Number n)
            {
                scored++;

                scoreSum += n.doubleValue();
            }

            leave += r.getValue("leave") instanceof Number n ? n.doubleValue() : 0;

            absent += r.getValue("absent") instanceof Number n ? n.doubleValue() : 0;

            talk += Attendance.TALK.equals(r.getString("band")) ? 1 : 0;
        }

        return new JsonObject()
                .put("people", rows.size())
                .put("averageScore", scored == 0 ? null : Math.round(scoreSum / scored))
                .put("leaveDays", leave)
                .put("absentDays", absent)
                .put("talk", talk);
    }

    /**
     * The day-by-day grid: {@code {days: [{date, weekend, holiday}], rows: [{personId, cells: {date: code}}]}};
     * a cell is the status, {@code D} on a customer visit, {@code H} on a holiday, {@code ""} with no record
     * and {@code "x"} in a month that is not uploaded.
     */
    private JsonObject grid(JsonArray people, JsonArray records, JsonArray visits, Set<LocalDate> holidays, Set<String> uploaded,
                            LocalDate from, LocalDate to)
    {
        LocalDate end = to.isAfter(today()) ? today() : to;

        Map<String, String> byDay = new HashMap<>();

        for (Object o : records)
        {
            JsonObject r = (JsonObject) o;

            byDay.put(r.getString("personId") + "|" + r.getString("day"), r.getString("status"));
        }

        JsonArray days = new JsonArray();

        for (LocalDate d = from; !d.isAfter(end); d = d.plusDays(1))
        {
            days.add(new JsonObject().put("date", d.toString()).put("weekend", weekend(d)).put("holiday", holidays.contains(d)));
        }

        JsonArray rows = new JsonArray();

        for (Object o : people)
        {
            String person = ((JsonObject) o).getString("id");

            JsonObject cells = new JsonObject();

            for (LocalDate d = from; !d.isAfter(end); d = d.plusDays(1))
            {
                String status = byDay.get(person + "|" + d);

                boolean visit = onVisit(visits, person, d);

                if (!uploaded.contains(YearMonth.from(d).toString()))
                {
                    cells.put(d.toString(), visit ? "D" : "x");
                }
                else if (visit && !"L".equals(status) && !"S".equals(status) && !"A".equals(status))
                {
                    cells.put(d.toString(), "D");
                }
                else if (status != null)
                {
                    cells.put(d.toString(), status);
                }
                else if (holidays.contains(d))
                {
                    cells.put(d.toString(), "H");
                }
                else if (!weekend(d))
                {
                    cells.put(d.toString(), "");
                }
            }

            rows.add(new JsonObject().put("personId", person).put("cells", cells));
        }

        return new JsonObject().put("days", days).put("rows", rows);
    }

    // ------------------------------------------------------------------ person

    /** The Person tab for a period; 404 when the person is outside the viewer's attendance scope. */
    Future<JsonObject> person(JsonObject user, String personId, String periodKey)
    {
        AttendanceRepository repo = new AttendanceRepository(ctx.sql());

        return policy.requireVisible(user, personId, PolicyService.DOMAIN_ATTENDANCE).compose(scope -> period(periodKey)).compose(period ->
        {
            UUID id = UUID.fromString(personId);

            LocalDate from = LocalDate.parse(period.getString("from"));

            LocalDate to = LocalDate.parse(period.getString("to"));

            List<String> months = AttendanceRules.months(today());

            List<Future<JsonObject>> monthly = new ArrayList<>();

            for (String m : months)
            {
                monthly.add(stats(personId, "m:" + m));
            }

            Future<JsonObject> stats = stats(personId, period.getString("key"));

            Future<Object> discipline = ctx.calc().get("people.attendanceDiscipline", new JsonObject().put("person", personId))
                    .map(ResultGuard::unguard);

            Future<Object> culture = ctx.calc().get("people.cultureInputs", new JsonObject().put("person", personId)
                    .put("period", "q:" + today().getYear() + "-Q" + ((today().getMonthValue() - 1) / 3 + 1))).map(ResultGuard::unguard);

            return Future.all(List.of(repo.person(id), stats, repo.records(new UUID[] {id}, from, to), repo.visits(new UUID[] {id}, from, to),
                            discipline, culture, Future.all(monthly), policy.scope(user, PolicyService.DOMAIN_CHECKIN)))
                    .map(all ->
                    {
                        Scope checkins = all.resultAt(7);

                        boolean times = checkins.allows(personId);

                        JsonObject s = stats.result() == null ? null : stats.result().copy();

                        if (s != null && !times)
                        {
                            CHECK_IN_FIELDS.forEach(s::remove);
                        }

                        JsonArray calendar = new JsonArray();

                        for (Object o : all.<JsonArray>resultAt(2))
                        {
                            JsonObject r = ((JsonObject) o).copy();

                            r.remove("personId");

                            if (!times)
                            {
                                r.remove("checkIn");

                                r.remove("checkOut");
                            }

                            calendar.add(r);
                        }

                        JsonArray byMonth = new JsonArray();

                        for (int i = 0; i < months.size(); i++)
                        {
                            JsonObject m = monthly.get(i).result();

                            byMonth.add(new JsonObject().put("month", months.get(i))
                                    .put("label", MONTH.format(YearMonth.parse(months.get(i))))
                                    .put("stats", m == null || m.getInteger("workingDays", 0) == 0 ? null : summary(m, times)));
                        }

                        return new JsonObject()
                                .put("person", all.<JsonObject>resultAt(0))
                                .put("period", period)
                                .put("stats", s)
                                .put("calendar", calendar)
                                .put("visits", all.<JsonArray>resultAt(3))
                                .put("months", byMonth)
                                .put("culture", culture(all.resultAt(4), all.resultAt(5)))
                                .put("checkInsVisible", times);
                    });
        });
    }

    private static JsonObject summary(JsonObject s, boolean times)
    {
        JsonObject out = new JsonObject();

        for (String f : List.of("office", "wfh", "duty", "leave", "shortNotice", "absent", "officePct", "score", "band"))
        {
            out.put(f, s.getValue(f));
        }

        if (times)
        {
            out.put("late", s.getValue("late"));
        }

        return out;
    }

    /** Culture impact: the Discipline rule h input and the Collaboration presence parameter. */
    private JsonObject culture(Object discipline, Object inputs)
    {
        JsonObject att = discipline instanceof JsonObject d ? d : null;

        JsonObject presence = inputs instanceof JsonObject i ? i.getJsonObject("office_presence") : null;

        Long officePct = presence == null || presence.getValue("raw") == null ? null : presence.getLong("raw");

        return new JsonObject()
                .put("ruleH", att)
                .put("presence", new JsonObject().put("officePct", officePct).put("score", Attendance.presenceScore(officePct, true)));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * {@code people.attendance} for one person and period, with missing values as plain nulls
     * ({@link ResultGuard#unguard}); null when there is no data.
     */
    Future<JsonObject> stats(String personId, String periodKey)
    {
        return ctx.calc().get("people.attendance", new JsonObject().put("person", personId).put("period", periodKey))
                .map(r -> ResultGuard.unguard(r) instanceof JsonObject o ? o : null);
    }

    /** The period of a key ({@code m:2026-08}, a sprint, a quarter); 400 for an unknown key. */
    Future<JsonObject> period(String key)
    {
        return ctx.calc().get("core.period", new JsonObject().put("period", key))
                .map(r -> (JsonObject) r)
                .recover(error -> error instanceof IllegalArgumentException
                        ? Future.failedFuture(ApiException.badRequest("invalid_parameter", error.getMessage()).field("period"))
                        : Future.failedFuture(error));
    }

    /** Months of the period up to today that are not uploaded. */
    private JsonArray missing(LocalDate from, LocalDate to, Set<String> uploaded)
    {
        JsonArray out = new JsonArray();

        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1))
        {
            if (!m.atDay(1).isAfter(today()) && !uploaded.contains(m.toString()))
            {
                out.add(new JsonObject().put("month", m.toString()).put("label", MONTH.format(m)));
            }
        }

        return out;
    }

    static Set<String> uploadedMonths(JsonArray uploads)
    {
        Set<String> out = new HashSet<>();

        uploads.forEach(u -> out.add(((JsonObject) u).getString("month")));

        return out;
    }

    private static Set<LocalDate> holidays(JsonArray rows)
    {
        Set<LocalDate> out = new HashSet<>();

        for (Object o : rows)
        {
            JsonObject h = (JsonObject) o;

            if (h.getString("location") == null)
            {
                out.add(LocalDate.parse(h.getString("date")));
            }
        }

        return out;
    }

    private static UUID[] ids(JsonArray people)
    {
        UUID[] ids = new UUID[people.size()];

        for (int i = 0; i < ids.length; i++)
        {
            ids[i] = UUID.fromString(people.getJsonObject(i).getString("id"));
        }

        return ids;
    }

    private static boolean weekend(LocalDate d)
    {
        return d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    private static boolean onVisit(JsonArray visits, String person, LocalDate day)
    {
        for (Object o : visits)
        {
            JsonObject v = (JsonObject) o;

            if (person.equals(v.getString("personId")) && !day.isBefore(LocalDate.parse(v.getString("from")))
                    && !day.isAfter(LocalDate.parse(v.getString("to"))))
            {
                return true;
            }
        }

        return false;
    }
}
