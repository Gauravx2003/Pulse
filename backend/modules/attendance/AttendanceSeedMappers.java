package com.pulse.modules.attendance;

import com.pulse.platform.ingest.PlatformSeedMappers;
import com.pulse.platform.ingest.SeedMapper;
import io.vertx.core.json.JsonObject;

import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The attendance seed (task 17.1) through {@code IngestService}: {@code seed/extracted/attendance.json}
 * ({@code {person, date, st, lt?, ap?, in?, out?}}) and {@code attendance_uploads.json}
 * ({@code {month, by, at: "4 May · 10:12", file, rows}}).
 *
 * <p>The prototype writes a half day as {@code H} and keeps sick leave as {@code L}; the stored codes
 * follow the spec: {@code HD} for a half day and {@code S} for Sick or Emergency leave.
 */
final class AttendanceSeedMappers
{
    private static final Pattern STAMP = Pattern.compile("(\\d{1,2}) ([A-Za-z]+) · (\\d{1,2}):(\\d{2})");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(Map.entry("jan", 1), Map.entry("feb", 2), Map.entry("mar", 3),
            Map.entry("apr", 4), Map.entry("may", 5), Map.entry("jun", 6), Map.entry("jul", 7), Map.entry("aug", 8),
            Map.entry("sep", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));

    private AttendanceSeedMappers()
    {
    }

    static List<SeedMapper> all()
    {
        return List.of(
                PlatformSeedMappers.mapper("hr", "attendance_records", 310, "attendance", AttendanceSeedMappers::record),
                PlatformSeedMappers.mapper("hr", "attendance_uploads", 311, "attendance_uploads", AttendanceSeedMappers::upload));
    }

    static JsonObject record(JsonObject a)
    {
        return new JsonObject()
                .put("external_id", a.getString("person") + ":" + a.getString("date"))
                .put("person_id", SeedMapper.ref("people", a.getString("person")))
                .put("day", a.getString("date"))
                .put("status", status(a.getString("st"), a.getString("lt")))
                .put("leave_type", a.getString("lt"))
                .put("applied_on", a.getString("ap"))
                .put("check_in", a.getString("in"))
                .put("check_out", a.getString("out"));
    }

    /** The stored status of a prototype code: {@code H} is a half day, sick or emergency leave is {@code S}. */
    static String status(String st, String leaveType)
    {
        if ("H".equals(st))
        {
            return "HD";
        }

        if ("L".equals(st) && AttendanceRules.EXCUSED.contains(leaveType))
        {
            return "S";
        }

        return st;
    }

    static JsonObject upload(JsonObject u)
    {
        return new JsonObject()
                .put("external_id", u.getString("month"))
                .put("month", u.getString("month"))
                .put("uploaded_by_name", u.getString("by"))
                .put("uploaded_at", uploadedAt(u.getString("month"), u.getString("at")))
                .put("file_name", u.getString("file"))
                .put("row_count", u.getInteger("rows"));
    }

    /** "4 May · 10:12" for an upload of April 2026 is 2026-05-04 10:12 in the company time zone. */
    static String uploadedAt(String month, String at)
    {
        Matcher m = at == null ? null : STAMP.matcher(at);

        YearMonth after = YearMonth.parse(month).plusMonths(1);

        if (m == null || !m.find())
        {
            return after.atDay(5) + "T10:00:00+05:30";
        }

        Integer mon = MONTHS.get(m.group(2).substring(0, 3).toLowerCase(Locale.ROOT));

        int year = mon != null && mon < after.getMonthValue() ? after.getYear() + 1 : after.getYear();

        return String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%s:00+05:30", year, mon == null ? after.getMonthValue() : mon,
                Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3)), m.group(4));
    }
}
