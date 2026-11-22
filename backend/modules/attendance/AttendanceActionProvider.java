package com.pulse.modules.attendance;

import com.pulse.platform.module.ActionProvider;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Attendance items for the Action Center (spec time/attendance "Reminders"; task 17.3), ported from the
 * prototype's {@code attActionItems}:
 *
 * <ul>
 *   <li>{@code attendance.month-missing} (uploaders): a past month with no upload, Medium.</li>
 *   <li>{@code attendance.upload-due} (uploaders): this month is not uploaded and 5 or fewer working days
 *       are left, Low.</li>
 *   <li>{@code attendance.people} (team.view / all.view): for the last finished month, once uploaded, each
 *       visible person (not the viewer) with an absence, 2 or more short-notice days, office policy under
 *       70% or late check-ins over the allowance; Medium with an absence, else Low. Late check-ins count
 *       only where the viewer may see check-in times (the direct manager).</li>
 * </ul>
 *
 * <p>Item shape: {@code {key, priority, source, personId, personName, text, button, target: {module, id}}}.
 */
public final class AttendanceActionProvider implements ActionProvider
{
    static final String MONTH_MISSING = "attendance.month-missing";

    static final String UPLOAD_DUE = "attendance.upload-due";

    static final String PEOPLE = "attendance.people";

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final String key;

    AttendanceActionProvider(String key)
    {
        this.key = key;
    }

    static List<ActionProvider> all()
    {
        return List.of(new AttendanceActionProvider(MONTH_MISSING), new AttendanceActionProvider(UPLOAD_DUE),
                new AttendanceActionProvider(PEOPLE));
    }

    @Override
    public String key()
    {
        return key;
    }

    @Override
    public Future<List<JsonObject>> actionsFor(JsonObject user, ModuleContext ctx)
    {
        AttendanceService service = new AttendanceService(ctx);

        AttendanceRepository repo = new AttendanceRepository(ctx.sql());

        LocalDate today = service.today();

        Set<String> permissions = PolicyService.permissions(user);

        if (!PEOPLE.equals(key))
        {
            if (!permissions.contains("attendance.upload"))
            {
                return Future.succeededFuture(List.of());
            }

            return Future.all(repo.uploads(), repo.holidays()).map(all ->
            {
                Set<String> uploaded = AttendanceService.uploadedMonths(all.resultAt(0));

                return MONTH_MISSING.equals(key) ? missing(today, uploaded) : due(today, uploaded, all.resultAt(1));
            });
        }

        if (!permissions.contains("team.view") && !permissions.contains("all.view"))
        {
            return Future.succeededFuture(List.of());
        }

        String last = YearMonth.from(today).minusMonths(1).toString();

        return repo.uploads().compose(uploads ->
        {
            if (!AttendanceService.uploadedMonths(uploads).contains(last))
            {
                return Future.succeededFuture(List.of());
            }

            return Future.all(ctx.policy().scope(user, PolicyService.DOMAIN_ATTENDANCE).compose(repo::people),
                            ctx.policy().scope(user, PolicyService.DOMAIN_CHECKIN))
                    .compose(scopes -> people(service, user, scopes.resultAt(0), scopes.resultAt(1), last));
        });
    }

    private List<JsonObject> missing(LocalDate today, Set<String> uploaded)
    {
        List<JsonObject> items = new ArrayList<>();

        for (String m : AttendanceRules.months(today))
        {
            YearMonth month = YearMonth.parse(m);

            if (month.atEndOfMonth().isBefore(today) && !uploaded.contains(m))
            {
                items.add(item("Medium", null, null, MONTH.format(month) + " attendance not uploaded", "Upload", m));
            }
        }

        return items;
    }

    private List<JsonObject> due(LocalDate today, Set<String> uploaded, JsonArray holidays)
    {
        YearMonth month = YearMonth.from(today);

        if (uploaded.contains(month.toString()))
        {
            return List.of();
        }

        int left = 0;

        for (LocalDate d = today; !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1))
        {
            boolean weekend = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;

            left += weekend || isHoliday(holidays, d) ? 0 : 1;
        }

        if (left > 5)
        {
            return List.of();
        }

        return List.of(item("Low", null, null, MONTH.format(month) + " attendance due after month end — upload the HR export by the 5th",
                "Open", month.toString()));
    }

    private Future<List<JsonObject>> people(AttendanceService service, JsonObject user, JsonArray people, Scope checkins, String month)
    {
        List<JsonObject> visible = new ArrayList<>();

        List<Future<JsonObject>> stats = new ArrayList<>();

        for (Object o : people)
        {
            JsonObject p = (JsonObject) o;

            if (!p.getString("id").equals(user.getString("personId")))
            {
                visible.add(p);

                stats.add(service.stats(p.getString("id"), "m:" + month));
            }
        }

        return Future.all(stats).map(all ->
        {
            List<JsonObject> items = new ArrayList<>();

            for (int i = 0; i < visible.size(); i++)
            {
                JsonObject p = visible.get(i);

                JsonObject s = stats.get(i).result();

                if (s == null || s.getInteger("workingDays", 0) == 0)
                {
                    continue;
                }

                List<String> bits = new ArrayList<>();

                double absent = s.getDouble("absent", 0d);

                double shortNotice = s.getDouble("shortNotice", 0d);

                if (absent > 0)
                {
                    bits.add(days(absent) + " absent without leave");
                }

                if (shortNotice >= 2)
                {
                    bits.add(days(shortNotice) + " of short-notice leave");
                }

                if (s.getValue("officePct") != null && s.getLong("officePct") < 70)
                {
                    bits.add("office policy " + s.getLong("officePct") + "%");
                }

                long excess = s.getLong("lateExcess", 0L);

                if (excess >= 3 && checkins.allows(p.getString("id")))
                {
                    bits.add(excess + " late check-ins over the allowance");
                }

                if (!bits.isEmpty())
                {
                    items.add(item(absent > 0 ? "Medium" : "Low", p.getString("id"), p.getString("name"),
                            MONTH.format(YearMonth.parse(month)) + ": " + String.join(", ", bits) + " — check in at the next 1:1", "Open",
                            p.getString("id")));
                }
            }

            return items;
        });
    }

    private static String days(double n)
    {
        String shown = n == Math.rint(n) ? String.valueOf((long) n) : String.valueOf(n);

        return shown + (n == 1 ? " day" : " days");
    }

    private static boolean isHoliday(JsonArray holidays, LocalDate d)
    {
        for (Object o : holidays)
        {
            JsonObject h = (JsonObject) o;

            if (h.getString("location") == null && d.toString().equals(h.getString("date")))
            {
                return true;
            }
        }

        return false;
    }

    private JsonObject item(String priority, String personId, String personName, String text, String button, String target)
    {
        return new JsonObject()
                .put("key", key)
                .put("priority", priority)
                .put("source", "Attendance")
                .put("personId", personId)
                .put("personName", personName)
                .put("text", text)
                .put("button", button)
                .put("target", new JsonObject().put("module", "attendance").put("id", target));
    }
}
