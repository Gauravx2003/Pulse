package com.pulse.modules.attendance;

import com.pulse.modules.imports.csv.CsvColumns;
import com.pulse.modules.imports.type.ImportType;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.notify.NotificationService;
import com.pulse.platform.notify.NotificationTriggers;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Monthly attendance upload (spec time/attendance "Upload by HR only", admin/data-imports-exports;
 * tasks 16.3 and 17.2), ported from the prototype's {@code IMPORTERS.attendance}.
 *
 * <ul>
 *   <li>Columns {@code employee_email, date, status, leave_type, applied_on, check_in, check_out}, for
 *       holders of {@code attendance.upload} only (managers do not upload).</li>
 *   <li><b>Replace months</b>: every stored day of the people in the file, in the months of the file, is
 *       replaced by the file. <b>Merge days</b>: the file's days are added or overwritten, the rest stays.</li>
 *   <li>Each month of the file is recorded in {@code attendance_uploads} (month, uploader, time, file, rows);
 *       the managers of the people in the file are told.</li>
 *   <li>Undo is the pipeline's row restore: records and upload rows come back as they were, and the
 *       {@code imports.attendance} topic drops capacity, Discipline rule h and the Collaboration presence
 *       values both ways.</li>
 * </ul>
 */
public class AttendanceImportType implements ImportType
{
    static final String REPLACE = "replace_months";

    static final String MERGE = "merge_days";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    private static final Map<String, String> EXPORT_STATUS = Map.of("O", "Office", "W", "WFH", "D", "On duty", "L", "Leave",
            "S", "Sick", "HD", "Half day", "A", "Absent", "H", "Holiday");

    @Override
    public String id()
    {
        return "attendance";
    }

    @Override
    public String name()
    {
        return "Attendance";
    }

    @Override
    public String feeds()
    {
        return "Attendance score, capacity, Discipline rule h and Collaboration office presence";
    }

    @Override
    public List<String> permissions()
    {
        return List.of("attendance.upload");
    }

    @Override
    public JsonArray columns()
    {
        return JsonArray.of(
                CsvColumns.required("employee_email", CsvColumns.EMAIL, "email", "employee"),
                CsvColumns.required("date", CsvColumns.DATE, "day"),
                CsvColumns.required("status", CsvColumns.TEXT, "attendance"),
                CsvColumns.optional("leave_type", CsvColumns.TEXT, "leave"),
                CsvColumns.optional("applied_on", CsvColumns.DATE, "applied"),
                CsvColumns.optional("check_in", CsvColumns.TIME, "in", "in_time"),
                CsvColumns.optional("check_out", CsvColumns.TIME, "out", "out_time"));
    }

    @Override
    public JsonArray modes()
    {
        return JsonArray.of(
                new JsonObject().put("key", REPLACE).put("label", "Replace months"),
                new JsonObject().put("key", MERGE).put("label", "Merge days"));
    }

    @Override
    public JsonObject templateExample()
    {
        // A weekday of last month, so the sample stays inside the kept months.
        LocalDate day = YearMonth.from(LocalDate.now(PolicyService.ZONE)).minusMonths(1).atDay(1);

        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY)
        {
            day = day.plusDays(1);
        }

        return new JsonObject().put("employee_email", "priya@pulse.demo").put("date", day.toString()).put("status", "Office")
                .put("leave_type", "").put("applied_on", "").put("check_in", "09:40").put("check_out", "18:30");
    }

    @Override
    public List<String> targetTables()
    {
        return List.of("attendance_records", "attendance_uploads");
    }

    // ------------------------------------------------------------------ check

    @Override
    public Future<JsonArray> check(JsonArray rows, String mode, JsonObject actor, SqlExecutor sql, PolicyService policy)
    {
        Set<String> emails = new LinkedHashSet<>();

        LocalDate first = null;

        LocalDate last = null;

        for (Object item : rows)
        {
            JsonObject values = ((JsonObject) item).getJsonObject("values");

            if (values.getString("employee_email") != null)
            {
                emails.add(values.getString("employee_email"));
            }

            if (values.getString("date") != null)
            {
                LocalDate day = LocalDate.parse(values.getString("date"));

                first = first == null || day.isBefore(first) ? day : first;

                last = last == null || day.isAfter(last) ? day : last;
            }
        }

        LocalDate today = LocalDate.now(PolicyService.ZONE);

        AttendanceRepository repo = new AttendanceRepository(sql);

        LocalDate from = first == null ? today : YearMonth.from(first).atDay(1);

        LocalDate to = last == null ? today : YearMonth.from(last).atEndOfMonth();

        return repo.usersByEmail(emails.toArray(new String[0])).compose(users ->
        {
            Map<String, JsonObject> byEmail = new HashMap<>();

            List<UUID> people = new ArrayList<>();

            for (Object o : users)
            {
                JsonObject u = (JsonObject) o;

                byEmail.put(u.getString("email"), u);

                if (u.getString("personId") != null)
                {
                    people.add(UUID.fromString(u.getString("personId")));
                }
            }

            UUID[] ids = people.toArray(new UUID[0]);

            return Future.all(repo.stored(ids, from, to), repo.visits(ids, from, to), repo.holidays()).map(all ->
            {
                Map<String, JsonObject> stored = new HashMap<>();

                for (Object o : all.<JsonArray>resultAt(0))
                {
                    JsonObject r = (JsonObject) o;

                    stored.put(r.getString("personId") + "|" + r.getString("day"), r);
                }

                Set<LocalDate> holidays = new HashSet<>();

                for (Object o : all.<JsonArray>resultAt(2))
                {
                    JsonObject h = (JsonObject) o;

                    if (h.getString("location") == null)
                    {
                        holidays.add(LocalDate.parse(h.getString("date")));
                    }
                }

                Context c = new Context(mode, today, byEmail, stored, all.resultAt(1), holidays);

                for (Object item : rows)
                {
                    checkRow((JsonObject) item, c);
                }

                gaps(rows, c);

                return rows;
            });
        });
    }

    /** What the row checks read; {@code seen} and {@code covered} fill as rows are checked. */
    private record Context(String mode, LocalDate today, Map<String, JsonObject> users, Map<String, JsonObject> stored,
                           JsonArray visits, Set<LocalDate> holidays, Map<String, Integer> seen, Map<String, Set<String>> covered)
    {
        Context(String mode, LocalDate today, Map<String, JsonObject> users, Map<String, JsonObject> stored, JsonArray visits,
                Set<LocalDate> holidays)
        {
            this(mode, today, users, stored, visits, holidays, new HashMap<>(), new LinkedHashMap<>());
        }
    }

    private static void checkRow(JsonObject row, Context c)
    {
        if (!row.getJsonArray("errors").isEmpty())
        {
            return;
        }

        int line = row.getInteger("line");

        JsonObject v = row.getJsonObject("values");

        JsonObject user = c.users().get(v.getString("employee_email"));

        if (user == null)
        {
            error(row, "employee_email", "No user with " + v.getString("employee_email") + ".");

            return;
        }

        if (user.getString("personId") == null)
        {
            error(row, "employee_email", user.getString("name") + " has no performance profile in Pulse, so attendance can't be recorded.");

            return;
        }

        LocalDate day = LocalDate.parse(v.getString("date"));

        List<String> kept = AttendanceRules.months(c.today());

        if (day.isAfter(c.today()))
        {
            error(row, "date", "Date is in the future.");

            return;
        }

        if (!kept.contains(YearMonth.from(day).toString()))
        {
            error(row, "date", "Pulse keeps attendance for " + MONTH.format(YearMonth.parse(kept.get(kept.size() - 1))) + " – "
                    + MONTH.format(YearMonth.parse(kept.get(0))) + ".");

            return;
        }

        if (user.getString("joinedOn") != null && day.isBefore(LocalDate.parse(user.getString("joinedOn"))))
        {
            error(row, "date", "Before " + user.getString("name") + " joined (" + DAY.format(LocalDate.parse(user.getString("joinedOn"))) + ").");

            return;
        }

        String status = AttendanceRules.status(v.getString("status"));

        if (status == null)
        {
            error(row, "status", "“" + v.getString("status") + "” is not a status — use O, W, D, L, S, H, A or HD (Office, WFH, On duty, Leave, Sick, Holiday, Absent, Half day).");

            return;
        }

        String person = user.getString("personId");

        String key = person + "|" + day;

        Integer earlier = c.seen().putIfAbsent(key, line);

        if (earlier != null)
        {
            error(row, "date", "Second row for " + user.getString("name") + " on " + DAY.format(day) + " (first on line " + earlier + ").");

            return;
        }

        boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY;

        boolean holiday = c.holidays().contains(day);

        if (AttendanceRules.HOLIDAY_ROW.equals(status))
        {
            if (!holiday)
            {
                warn(row, "status", "Holiday on " + DAY.format(day) + " is not in the holiday calendar — upload it under Holiday calendar.");
            }

            row.put("action", "unchanged");

            return;
        }

        if ((weekend || holiday) && (AttendanceRules.isLeave(status) || "A".equals(status)))
        {
            warn(row, "date", "Leave on a " + (weekend ? "weekend" : "holiday") + " — skipped.");

            row.put("action", "unchanged");

            return;
        }

        JsonObject record = new JsonObject().put("personId", person).put("day", day.toString()).put("status", status);

        if (!leave(row, v, status, record) || !times(row, v, status, record))
        {
            return;
        }

        if (weekend && ("O".equals(status) || "W".equals(status)))
        {
            warn(row, "date", "Weekend work on " + DAY.format(day) + " — recorded; extra-mile credit still comes from Effort.");
        }

        if (onVisit(c.visits(), person, day) && !"D".equals(record.getString("status")))
        {
            if ("L".equals(status) || "S".equals(status) || "A".equals(status))
            {
                error(row, "status", "A customer visit is planned on this day — correct the visit or this row.");

                return;
            }

            warn(row, "status", "Customer visit on this day — shown as On duty.");

            record.put("status", "D");
        }

        c.covered().computeIfAbsent(person, k -> new TreeSet<>()).add(day.toString());

        row.put("record", record);

        JsonObject current = c.stored().get(key);

        if (current == null)
        {
            row.put("action", "new");
        }
        else if (MERGE.equals(c.mode()) && same(current, record))
        {
            row.put("action", "unchanged");
        }
        else
        {
            // Replace months rewrites every day of the month, so a stored day is always "to update".
            row.put("action", "update").put("targetId", current.getString("id"));
        }
    }

    /** Leave type and applied-on date of a leave row; false when the row has an error. */
    private static boolean leave(JsonObject row, JsonObject v, String status, JsonObject record)
    {
        if (!AttendanceRules.isLeave(status))
        {
            return true;
        }

        String type = AttendanceRules.leaveType(v.getString("leave_type"));

        if (type == null && "S".equals(status) && v.getString("leave_type") == null)
        {
            type = "Sick";
        }

        if (type == null)
        {
            error(row, "leave_type", v.getString("leave_type") == null
                    ? "Leave needs a type: " + String.join(", ", AttendanceRules.LEAVE_TYPES) + "."
                    : "“" + v.getString("leave_type") + "” — use " + String.join(", ", AttendanceRules.LEAVE_TYPES) + ".");

            return false;
        }

        String stored = "L".equals(status) && AttendanceRules.EXCUSED.contains(type) ? "S" : status;

        record.put("status", stored).put("leaveType", type);

        if (v.getString("applied_on") != null)
        {
            record.put("appliedOn", v.getString("applied_on"));
        }
        else if (!AttendanceRules.EXCUSED.contains(type))
        {
            warn(row, "applied_on", "No applied date — counted as short-notice leave.");
        }

        return true;
    }

    /** Check-in and check-out of an office, WFH or half day; false when the row has an error. */
    private static boolean times(JsonObject row, JsonObject v, String status, JsonObject record)
    {
        if (!"O".equals(status) && !"W".equals(status) && !"HD".equals(status))
        {
            return true;
        }

        String in = v.getString("check_in");

        String out = v.getString("check_out");

        if (in != null && out != null && out.compareTo(in) <= 0)
        {
            error(row, "check_out", "Check-out is not after check-in.");

            return false;
        }

        record.put("checkIn", in).put("checkOut", out);

        if (in == null && !"HD".equals(status))
        {
            warn(row, "check_in", "No check-in — not counted for punctuality.");
        }

        return true;
    }

    /** One warning per person with working days of the file's months that have no row. */
    private static void gaps(JsonArray rows, Context c)
    {
        Map<String, JsonObject> firstRow = new HashMap<>();

        for (Object item : rows)
        {
            JsonObject row = (JsonObject) item;

            if (row.containsKey("record"))
            {
                firstRow.putIfAbsent(row.getJsonObject("record").getString("personId"), row);
            }
        }

        Set<String> months = new TreeSet<>();

        c.covered().values().forEach(days -> days.forEach(d -> months.add(d.substring(0, 7))));

        for (Map.Entry<String, Set<String>> entry : c.covered().entrySet())
        {
            List<String> missing = new ArrayList<>();

            for (String month : months)
            {
                YearMonth m = YearMonth.parse(month);

                for (LocalDate d = m.atDay(1); !d.isAfter(m.atEndOfMonth()) && !d.isAfter(c.today()); d = d.plusDays(1))
                {
                    boolean weekend = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;

                    if (!weekend && !c.holidays().contains(d) && !onVisit(c.visits(), entry.getKey(), d)
                            && !entry.getValue().contains(d.toString()))
                    {
                        missing.add(DAY.format(d));
                    }
                }
            }

            if (!missing.isEmpty())
            {
                JsonObject row = firstRow.get(entry.getKey());

                warn(row, "date", missing.size() + " working day" + (missing.size() > 1 ? "s" : "") + " with no row ("
                        + String.join(", ", missing.subList(0, Math.min(3, missing.size()))) + (missing.size() > 3 ? " …" : "") + ")"
                        + (REPLACE.equals(c.mode()) ? " — shown as “No record”." : " — current record kept."));
            }
        }
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

    private static boolean same(JsonObject stored, JsonObject record)
    {
        for (String field : List.of("status", "leaveType", "appliedOn", "checkIn", "checkOut"))
        {
            if (!java.util.Objects.equals(stored.getString(field), record.getString(field)))
            {
                return false;
            }
        }

        return true;
    }

    // ------------------------------------------------------------------ commit

    @Override
    public Future<JsonObject> commit(JsonArray rows, String mode, JsonObject actor, SqlExecutor tx, PolicyService policy)
    {
        AttendanceRepository repo = new AttendanceRepository(tx);

        JsonArray changes = new JsonArray();

        Map<String, Integer> perMonth = new LinkedHashMap<>();

        Map<String, Set<String>> monthsOf = new LinkedHashMap<>();

        for (Object item : rows)
        {
            JsonObject record = ((JsonObject) item).getJsonObject("record");

            String month = record.getString("day").substring(0, 7);

            perMonth.merge(month, 1, Integer::sum);

            monthsOf.computeIfAbsent(record.getString("personId"), k -> new TreeSet<>()).add(month);
        }

        Future<Void> chain = Future.succeededFuture();

        if (REPLACE.equals(mode))
        {
            for (Map.Entry<String, Set<String>> entry : monthsOf.entrySet())
            {
                UUID person = UUID.fromString(entry.getKey());

                for (String month : entry.getValue())
                {
                    YearMonth m = YearMonth.parse(month);

                    chain = chain.compose(v -> repo.lockMonth(person, m.atDay(1), m.atEndOfMonth()).compose(before ->
                    {
                        Future<Void> deletes = Future.succeededFuture();

                        for (Object o : before)
                        {
                            JsonObject row = (JsonObject) o;

                            UUID id = UUID.fromString(row.getString("id"));

                            deletes = deletes.compose(x -> repo.delete(id).map(n ->
                            {
                                changes.add(change("attendance_records", "delete", id.toString(), row, null));

                                return null;
                            }));
                        }

                        return deletes;
                    }));
                }
            }
        }

        for (Object item : rows)
        {
            JsonObject row = (JsonObject) item;

            JsonObject record = row.getJsonObject("record");

            UUID person = UUID.fromString(record.getString("personId"));

            LocalDate day = LocalDate.parse(record.getString("day"));

            chain = chain.compose(v -> repo.lockDay(person, day).compose(before -> before == null
                    ? repo.insert(person, record).map(added ->
                    {
                        changes.add(change("attendance_records", "insert", added.getString("id"), null, added.getInteger("version")));

                        return (Void) null;
                    })
                    : repo.update(UUID.fromString(before.getString("id")), person, record).map(after ->
                    {
                        changes.add(change("attendance_records", "update", before.getString("id"), before, after.getInteger("version")));

                        return (Void) null;
                    })));
        }

        UUID by = UUID.fromString(actor.getString("id"));

        String byName = actor.getString("name", "HR");

        String file = actor.getString(IMPORT_FILE, "attendance.csv");

        for (Map.Entry<String, Integer> entry : perMonth.entrySet())
        {
            chain = chain.compose(v -> repo.lockUpload(entry.getKey()).compose(before -> before == null
                    ? repo.insertUpload(entry.getKey(), by, byName, file, entry.getValue()).map(added ->
                    {
                        changes.add(change("attendance_uploads", "insert", added.getString("id"), null, added.getInteger("version")));

                        return (Void) null;
                    })
                    : repo.replaceUpload(UUID.fromString(before.getString("id")), by, byName, file, entry.getValue()).map(after ->
                    {
                        changes.add(change("attendance_uploads", "update", before.getString("id"), before, after.getInteger("version")));

                        return (Void) null;
                    })));
        }

        UUID[] people = monthsOf.keySet().stream().map(UUID::fromString).toArray(UUID[]::new);

        List<String> months = new ArrayList<>(new TreeSet<>(perMonth.keySet()));

        return chain.compose(v -> notifyManagers(repo, tx, policy, actor, people, months)).map(notified -> new JsonObject()
                .put("imported", rows.size())
                .put("changes", changes)
                .put("notes", JsonArray.of(rows.size() + " days loaded for " + people.length + " people · "
                                + String.join(", ", months.stream().map(m -> MONTH.format(YearMonth.parse(m))).toList()),
                        notified + " manager" + (notified == 1 ? " was" : "s were") + " notified.")));
    }

    /** Tells each manager of the people in the file (not the uploader) once per month uploaded. */
    private static Future<Integer> notifyManagers(AttendanceRepository repo, SqlExecutor tx, PolicyService policy, JsonObject actor,
                                                  UUID[] people, List<String> months)
    {
        if (people.length == 0)
        {
            return Future.succeededFuture(0);
        }

        NotificationService notices = new NotificationService(tx, policy);

        return repo.managersOf(people).compose(managers ->
        {
            Future<Integer> chain = Future.succeededFuture(0);

            for (Object o : managers)
            {
                String manager = ((JsonObject) o).getString("managerId");

                if (manager.equals(actor.getString("id")))
                {
                    continue;
                }

                for (String month : months)
                {
                    chain = chain.compose(n -> notices.notify(tx, NotificationTriggers.attendanceUploaded(manager,
                            YearMonth.parse(month).atDay(1))).map(n));
                }

                chain = chain.map(n -> n + 1);
            }

            return chain;
        });
    }

    // ------------------------------------------------------------------ export

    @Override
    public Future<JsonArray> exportRows(JsonObject actor, JsonObject query, SqlExecutor sql, PolicyService policy)
    {
        LocalDate today = LocalDate.now(PolicyService.ZONE);

        YearMonth month;

        try
        {
            month = query.getString("month") == null ? YearMonth.from(today).minusMonths(1) : YearMonth.parse(query.getString("month"));
        }
        catch (RuntimeException e)
        {
            return Future.failedFuture(com.pulse.platform.http.ApiException.badRequest("invalid_parameter", "Use month=YYYY-MM.")
                    .field("month"));
        }

        return policy.scope(actor, PolicyService.DOMAIN_ATTENDANCE)
                .compose(scope -> new AttendanceRepository(sql).export(scope, month.atDay(1), month.atEndOfMonth()))
                .map(rows ->
                {
                    for (Object o : rows)
                    {
                        JsonObject r = (JsonObject) o;

                        r.put("status", EXPORT_STATUS.get(r.getString("status")));
                    }

                    return rows;
                });
    }

    private static JsonObject change(String table, String action, String id, JsonObject before, Integer afterVersion)
    {
        return new JsonObject()
                .put("table", table)
                .put("id", id)
                .put("action", action)
                .put("before", before)
                .put("afterVersion", afterVersion);
    }

    private static void error(JsonObject row, String column, String text)
    {
        row.getJsonArray("errors").add(new JsonObject().put("line", row.getInteger("line")).put("column", column).put("message", text));
    }

    private static void warn(JsonObject row, String column, String text)
    {
        row.getJsonArray("warnings").add(new JsonObject().put("line", row.getInteger("line")).put("column", column).put("message", text));
    }
}
