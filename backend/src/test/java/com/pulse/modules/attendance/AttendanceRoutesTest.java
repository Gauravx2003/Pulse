package com.pulse.modules.attendance;

import com.pulse.platform.auth.SessionHandler;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.pulse.testsupport.TestApi.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attendance through the real server (tasks 17.1–17.3): the seed, the Team and Person views with their
 * privacy rules, the HR-only upload with Replace and Merge, manager notices, the rule h and Collaboration
 * inputs updating and reverting with undo, and the Action Center items.
 */
class AttendanceRoutesTest
{
    private static final int PORT = 18_310;

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Kolkata"));

    /** The last finished month: uploaded by the end-to-end test. */
    private static final YearMonth LAST = YearMonth.from(TODAY).minusMonths(1);

    private static TestApi api;

    private static WebClient client;

    private static String priya;

    private static String arjun;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null, "imports", "visits", "attendance");

        priya = personOf(RoleMatrix.EMPLOYEE);

        arjun = personOf(RoleMatrix.PEER);

        client = WebClient.create(api.vertx());
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    private static String personOf(String email) throws Exception
    {
        JsonObject row = api.queryOne("SELECT p.id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = '" + email + "'");

        return row == null ? null : row.getString("id");
    }

    private static TestApi.Session as(String email) throws Exception
    {
        return RoleMatrix.session(api, email);
    }

    private static JsonObject ok(HttpResponse<Buffer> res) throws Exception
    {
        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static HttpResponse<Buffer> check(TestApi.Session session, String mode, String fileName, String csv) throws Exception
    {
        return await(client.post(PORT, "localhost", "/api/v1/imports/attendance/check?mode=" + mode + "&fileName=" + fileName)
                .putHeader("X-Real-IP", TestApi.newIp())
                .putHeader("Cookie", session.cookie())
                .putHeader(SessionHandler.CSRF_HEADER, session.csrf())
                .putHeader("Content-Type", "text/csv")
                .sendBuffer(Buffer.buffer(csv.getBytes(StandardCharsets.UTF_8))));
    }

    /** Checks and commits a file; resolves to the import id. */
    private static String upload(String mode, String fileName, String csv) throws Exception
    {
        TestApi.Session hr = as(RoleMatrix.ADMIN);

        JsonObject checked = ok(check(hr, mode, fileName, csv));

        assertEquals(0, checked.getInteger("errors"), checked.encodePrettily());

        JsonObject committed = ok(api.post("/api/v1/imports/attendance/commit", hr, new JsonObject().put("checkId", checked.getString("checkId"))));

        return committed.getString("importId");
    }

    /** Priya's working days of the month up to yesterday that are not holidays or customer-visit days. */
    private static List<LocalDate> plainDays(YearMonth month) throws Exception
    {
        Set<String> skip = new HashSet<>();

        api.sql();

        for (String sql : List.of("SELECT holiday_on::text AS d FROM holidays WHERE location IS NULL",
                "SELECT generate_series(from_on, to_on, interval '1 day')::date::text AS d FROM visits WHERE person_id = '" + priya + "'"))
        {
            for (Object o : await(api.sql().queryList(sql, io.vertx.sqlclient.Tuple.tuple())))
            {
                skip.add(((JsonObject) o).getString("d"));
            }
        }

        List<LocalDate> days = new ArrayList<>();

        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()) && d.isBefore(TODAY); d = d.plusDays(1))
        {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !skip.contains(d.toString()))
            {
                days.add(d);
            }
        }

        return days;
    }

    // ------------------------------------------------------------------ seed and statuses

    @Test
    void theSeedLoadsThroughIngestWithSpecCodes() throws Exception
    {
        assertTrue(api.count("SELECT count(*) FROM attendance_records WHERE source = 'seed'") > 600);

        assertEquals(5, api.count("SELECT count(*) FROM attendance_uploads WHERE source = 'seed'"));

        // The prototype's "H" is a half day; sick leave is S.
        assertEquals(0, api.count("SELECT count(*) FROM attendance_records WHERE status = 'H' AND leave_type IS NOT NULL"));

        assertTrue(api.count("SELECT count(*) FROM attendance_records WHERE status = 'HD'") > 0);

        assertEquals(0, api.count("SELECT count(*) FROM attendance_records WHERE status = 'L' AND leave_type IN ('Sick', 'Emergency')"));

        assertEquals("2026-05-04", api.queryOne("SELECT (uploaded_at AT TIME ZONE 'Asia/Kolkata')::date::text AS d FROM attendance_uploads "
                + "WHERE month = '2026-04'").getString("d"));
    }

    @Test
    void sickLeaveIsExcusedAndNeverPenalizes() throws Exception
    {
        // Spec "Sick leave is excused": Priya's seeded sick day on 10 Jun 2026.
        JsonObject view = ok(api.get("/api/v1/attendance/people/" + priya + "?period=m:2026-06", as(RoleMatrix.EMPLOYEE)));

        JsonObject sick = null;

        for (Object o : view.getJsonObject("stats").getJsonArray("leaves"))
        {
            if ("2026-06-10".equals(((JsonObject) o).getString("date")))
            {
                sick = (JsonObject) o;
            }
        }

        assertNotNull(sick, view.encodePrettily());

        assertEquals("S", sick.getString("status"));

        assertEquals("excused", sick.getString("class"));

        assertEquals(0, view.getJsonObject("stats").getInteger("shortNotice"));
    }

    // ------------------------------------------------------------------ views and privacy

    @Test
    void employeesSeeOnlyTheirOwnRecord() throws Exception
    {
        TestApi.Session employee = as(RoleMatrix.EMPLOYEE);

        JsonArray people = ok(api.get("/api/v1/attendance/team?period=m:2026-08", employee)).getJsonArray("people");

        assertEquals(1, people.size(), people.encodePrettily());

        assertEquals(priya, people.getJsonObject(0).getString("personId"));

        assertEquals(403, api.get("/api/v1/attendance/people/" + arjun + "?period=m:2026-08", employee).statusCode());

        assertEquals(404, api.get("/api/v1/attendance/people/not-a-uuid", employee).statusCode());

        assertEquals(400, api.get("/api/v1/attendance/team?period=nonsense", employee).statusCode());
    }

    @Test
    void checkInTimesAreForThePersonAndTheirDirectManagerOnly() throws Exception
    {
        String path = "/api/v1/attendance/people/" + priya + "?period=m:2026-08";

        JsonObject own = ok(api.get(path, as(RoleMatrix.EMPLOYEE)));

        JsonObject manager = ok(api.get(path, as(RoleMatrix.MANAGER)));

        JsonObject hr = ok(api.get(path, as(RoleMatrix.ADMIN)));

        assertTrue(own.getBoolean("checkInsVisible") && manager.getBoolean("checkInsVisible"));

        assertTrue(manager.getJsonArray("calendar").encode().contains("checkIn"));

        assertTrue(manager.getJsonObject("stats").containsKey("lateDays"));

        // HR sees everyone's attendance but not Priya's check-in times: Rahul is her direct manager.
        assertFalse(hr.getBoolean("checkInsVisible"));

        assertFalse(hr.getJsonArray("calendar").encode().contains("checkIn"), hr.encodePrettily());

        assertFalse(hr.getJsonObject("stats").containsKey("lateDays"));

        assertEquals(own.getJsonObject("stats").getValue("score"), hr.getJsonObject("stats").getValue("score"));
    }

    @Test
    void theTeamTabHasAGridAndTheNotUploadedBanner() throws Exception
    {
        JsonObject team = ok(api.get("/api/v1/attendance/team?period=m:2026-08", as(RoleMatrix.MANAGER)));

        assertTrue(team.getJsonArray("people").size() >= 3, team.encodePrettily());

        assertNotNull(team.getJsonObject("grid"));

        assertTrue(team.getJsonArray("missing").isEmpty());

        assertFalse(team.getBoolean("canUpload"));

        // Spec "Not-uploaded banner": a month with no upload, Upload CSV for uploaders only.
        String month = YearMonth.from(TODAY).toString();

        JsonObject hr = ok(api.get("/api/v1/attendance/team?period=m:" + month, as(RoleMatrix.ADMIN)));

        assertEquals(month, hr.getJsonArray("missing").getJsonObject(0).getString("month"));

        assertTrue(hr.getBoolean("canUpload"));

        JsonObject periods = ok(api.get("/api/v1/attendance/periods", as(RoleMatrix.ADMIN)));

        assertEquals(6, periods.getJsonArray("months").size());

        assertFalse(periods.getJsonArray("months").getJsonObject(0).getBoolean("uploaded"));
    }

    // ------------------------------------------------------------------ upload, rule h and undo

    @Test
    void onlyHrUploads() throws Exception
    {
        assertEquals(403, check(as(RoleMatrix.MANAGER), AttendanceImportType.REPLACE, "a.csv",
                "employee_email,date,status\npriya@pulse.demo," + LAST.atDay(1) + ",Office\n").statusCode());
    }

    @Test
    void uploadReplacesMergesNotifiesUpdatesRuleHAndUndoes() throws Exception
    {
        List<LocalDate> days = plainDays(LAST);

        assertTrue(days.size() >= 5, "enough working days in " + LAST);

        String ruleHPath = "/api/v1/attendance/people/" + priya + "?period=m:" + LAST;

        boolean sameQuarter = (LAST.getMonthValue() - 1) / 3 == (TODAY.getMonthValue() - 1) / 3;

        JsonObject before = ok(api.get(ruleHPath, as(RoleMatrix.MANAGER)));

        assertNull(before.getJsonObject("stats") == null ? null : before.getJsonObject("stats").getValue("score"));

        long notices = api.count("SELECT count(*) FROM notifications n JOIN users u ON u.id = n.user_id WHERE u.email = 'rahul@pulse.demo' "
                + "AND n.kind = 'attendance.uploaded'");

        // A whole month: one day absent, one casual leave applied the day before (short notice).
        StringBuilder csv = new StringBuilder("employee_email,date,status,leave_type,applied_on,check_in,check_out\n");

        for (int i = 0; i < days.size(); i++)
        {
            LocalDate d = days.get(i);

            csv.append("priya@pulse.demo,").append(d).append(',');

            if (i == 0)
            {
                csv.append("Absent,,,,\n");
            }
            else if (i == 1)
            {
                csv.append("Leave,Casual,").append(d.minusDays(1)).append(",,\n");
            }
            else
            {
                csv.append("Office,,,09:40,18:30\n");
            }
        }

        String importId = upload(AttendanceImportType.REPLACE, "attendance_" + LAST + ".csv", csv.toString());

        JsonObject upload = api.queryOne("SELECT file_name, row_count FROM attendance_uploads WHERE month = '" + LAST + "'");

        assertEquals("attendance_" + LAST + ".csv", upload.getString("file_name"));

        assertEquals(days.size(), upload.getInteger("row_count"));

        assertEquals(notices + 1, api.count("SELECT count(*) FROM notifications n JOIN users u ON u.id = n.user_id "
                + "WHERE u.email = 'rahul@pulse.demo' AND n.kind = 'attendance.uploaded'"));

        JsonObject after = ok(api.get(ruleHPath, as(RoleMatrix.MANAGER)));

        JsonObject stats = after.getJsonObject("stats");

        assertNotNull(stats.getValue("score"), after.encodePrettily());

        assertEquals(1, stats.getInteger("absent"));

        assertEquals(1, stats.getInteger("shortNotice"));

        if (sameQuarter)
        {
            // Spec "Upload updates rule h": the rule h input and the Collaboration presence change ...
            assertEquals(1, after.getJsonObject("culture").getJsonObject("ruleH").getInteger("absent"));

            assertNotNull(after.getJsonObject("culture").getJsonObject("presence").getValue("officePct"));
        }

        // The manager's Action Center item for last month.
        JsonObject item = find(ok(api.get("/api/v1/attendance/reminders", as(RoleMatrix.MANAGER))).getJsonArray("items"),
                "attendance.people", priya);

        assertNotNull(item);

        assertEquals("Medium", item.getString("priority"));

        assertTrue(item.getString("text").contains("1 day absent without leave"), item.encode());

        // ... and revert when the import is undone.
        ok(api.post("/api/v1/imports/history/" + importId + "/undo", as(RoleMatrix.ADMIN), new JsonObject()));

        assertEquals(0, monthRows());

        assertEquals(0, api.count("SELECT count(*) FROM attendance_uploads WHERE month = '" + LAST + "'"));

        JsonObject reverted = ok(api.get(ruleHPath, as(RoleMatrix.MANAGER)));

        assertNull(reverted.getJsonObject("stats") == null ? null : reverted.getJsonObject("stats").getValue("score"));

        if (sameQuarter)
        {
            assertNull(reverted.getJsonObject("culture").getValue("ruleH"));
        }

        assertNull(find(ok(api.get("/api/v1/attendance/reminders", as(RoleMatrix.MANAGER))).getJsonArray("items"), "attendance.people", priya));

        // Merge days: one day changes, the rest stays.
        upload(AttendanceImportType.REPLACE, "attendance_" + LAST + ".csv", csv.toString());

        upload(AttendanceImportType.MERGE, "fix.csv", "employee_email,date,status,leave_type,applied_on\npriya@pulse.demo," + days.get(0)
                + ",Sick,,\n");

        assertEquals(days.size(), monthRows());

        assertEquals(0, ok(api.get(ruleHPath, as(RoleMatrix.MANAGER))).getJsonObject("stats").getInteger("absent"));

        // Replace months: only the file's days remain for Priya in that month; undo brings the month back.
        String replace = upload(AttendanceImportType.REPLACE, "short.csv", "employee_email,date,status,check_in\npriya@pulse.demo,"
                + days.get(2) + ",WFH,09:30\n");

        assertEquals(1, monthRows());

        ok(api.post("/api/v1/imports/history/" + replace + "/undo", as(RoleMatrix.ADMIN), new JsonObject()));

        assertEquals(days.size(), monthRows());

        assertEquals("S", api.queryOne("SELECT status FROM attendance_records WHERE person_id = '" + priya + "' AND day = '" + days.get(0)
                + "'").getString("status"));

        assertEquals("fix.csv", api.queryOne("SELECT file_name FROM attendance_uploads WHERE month = '" + LAST + "'").getString("file_name"));
    }

    private static long monthRows() throws Exception
    {
        return api.count("SELECT count(*) FROM attendance_records WHERE person_id = '" + priya + "' AND day BETWEEN '" + LAST.atDay(1)
                + "' AND '" + LAST.atEndOfMonth() + "'");
    }

    @Test
    void checkFlagsBadRowsLikeThePrototype() throws Exception
    {
        LocalDate day = plainDays(LAST).get(0);

        String csv = "employee_email,date,status,leave_type,applied_on,check_in,check_out\n"
                + "nobody@pulse.demo," + day + ",Office,,,09:00,18:00\n"
                + "priya@pulse.demo," + day + ",Dancing,,,,\n"
                + "arjun@pulse.demo," + day + ",Leave,,,,\n"
                + "neha@pulse.demo," + day + ",Office,,,18:00,09:00\n"
                + "kiran@pulse.demo," + TODAY.plusDays(3) + ",Office,,,,\n"
                + "dev@pulse.demo," + day + ",Leave,Casual,,,\n"
                + "dev@pulse.demo," + day + ",Office,,,,\n"
                + "rahul@pulse.demo," + day + ",Office,,,,\n";

        JsonObject result = ok(check(as(RoleMatrix.ADMIN), AttendanceImportType.MERGE, "bad.csv", csv));

        String body = result.encode();

        assertTrue(body.contains("No user with nobody@pulse.demo"), body);

        assertTrue(body.contains("is not a status"), body);

        assertTrue(body.contains("Leave needs a type"), body);

        assertTrue(body.contains("Check-out is not after check-in"), body);

        assertTrue(body.contains("Date is in the future"), body);

        assertTrue(body.contains("Second row for"), body);

        assertTrue(body.contains("No applied date"), body);

        assertTrue(body.contains("has no performance profile"), body);

        assertTrue(body.contains("use O, W, D, L, S, H, A or HD"), body);

        assertEquals(1, result.getInteger("ready"), body);
    }

    @Test
    void aVisitCannotBePlannedOnLeave() throws Exception
    {
        // Priya's seeded earned leave on 14 Aug 2026 (spec time/customer-visits "Plan or log" overlap with leave).
        String owner = api.queryOne("SELECT id FROM users WHERE email = 'sana@pulse.demo'").getString("id");

        HttpResponse<Buffer> res = api.post("/api/v1/visits/items", as(RoleMatrix.MANAGER), new JsonObject()
                .put("personId", priya).put("customer", "Initech").put("city", "Pune").put("purpose", "Escalation")
                .put("from", "2026-08-13").put("to", "2026-08-14").put("requestedBy", "Sana Mirza").put("ownerId", owner));

        assertEquals(422, res.statusCode(), res.bodyAsString());

        assertEquals("visit_on_leave", TestApi.error(res).getString("code"));
    }

    @Test
    void theTemplateSampleChecksAndCommits() throws Exception
    {
        HttpResponse<Buffer> template = api.get("/api/v1/imports/attendance/template", as(RoleMatrix.ADMIN));

        assertEquals(200, template.statusCode(), template.bodyAsString());

        JsonObject checked = ok(check(as(RoleMatrix.ADMIN), AttendanceImportType.MERGE, "attendance_template.csv", template.bodyAsString()));

        assertEquals(0, checked.getInteger("errors"), checked.encodePrettily());

        assertEquals(1, checked.getInteger("ready"), checked.encodePrettily());

        String importId = ok(api.post("/api/v1/imports/attendance/commit", as(RoleMatrix.ADMIN),
                new JsonObject().put("checkId", checked.getString("checkId")))).getString("importId");

        // Leave the month as other tests expect it.
        ok(api.post("/api/v1/imports/history/" + importId + "/undo", as(RoleMatrix.ADMIN), new JsonObject()));
    }

    // ------------------------------------------------------------------ Action Center

    @Test
    void hrIsRemindedOfAPastMonthWithNoUpload() throws Exception
    {
        // Spec "Past month missing": the seed stops in August, so later finished months are missing.
        JsonArray items = ok(api.get("/api/v1/attendance/reminders", as(RoleMatrix.ADMIN))).getJsonArray("items");

        JsonObject missing = null;

        for (Object o : items)
        {
            JsonObject x = (JsonObject) o;

            if (AttendanceActionProvider.MONTH_MISSING.equals(x.getString("key")))
            {
                missing = x;
            }
        }

        assertNotNull(missing, items.encodePrettily());

        assertEquals("Medium", missing.getString("priority"));

        assertTrue(missing.getString("text").endsWith("attendance not uploaded"));

        // Managers do not upload, so they get no upload items.
        JsonArray manager = ok(api.get("/api/v1/attendance/reminders", as(RoleMatrix.MANAGER))).getJsonArray("items");

        assertFalse(manager.encode().contains(AttendanceActionProvider.MONTH_MISSING));
    }

    @Test
    void routesFollowTheRoleMatrix() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/attendance/periods", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 200,
                RoleMatrix.PEER, 200));

        assertEquals(401, api.get("/api/v1/attendance/team", null).statusCode());
    }

    private static JsonObject find(JsonArray items, String key, String personId)
    {
        for (Object o : items)
        {
            JsonObject x = (JsonObject) o;

            if (key.equals(x.getString("key")) && personId.equals(x.getString("personId")))
            {
                return x;
            }
        }

        return null;
    }

    @Test
    void theMonthEndJobRemindsUploadersOnce() throws Exception
    {
        // This month has no upload (the seed stops in August): every attendance.upload holder is told, once.
        String month = YearMonth.from(TODAY).toString();

        String notices = "SELECT count(*) FROM notifications n JOIN users u ON u.id = n.user_id WHERE n.kind = 'attendance.upload_due' "
                + "AND n.link LIKE '%" + month + "' AND u.email = 'hr@pulse.demo'";

        JsonObject run = ok(api.post("/api/v1/jobs/attendance.month-end-reminder/run", as(RoleMatrix.ADMIN), new JsonObject()))
                .getJsonObject("run");

        assertEquals(1, api.count(notices), run.encode());

        ok(api.post("/api/v1/jobs/attendance.month-end-reminder/run", as(RoleMatrix.ADMIN), new JsonObject()));

        assertEquals(1, api.count(notices));

        // Managers don't upload, so they are not reminded.
        assertEquals(0, api.count("SELECT count(*) FROM notifications n JOIN users u ON u.id = n.user_id WHERE n.kind = 'attendance.upload_due' "
                + "AND u.email = 'rahul@pulse.demo'"));
    }
}
