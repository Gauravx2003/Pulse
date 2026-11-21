package com.pulse.modules.imports.types;

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

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static com.pulse.testsupport.TestApi.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 16.3: the Holiday calendar and OKR import types through the real server with the demo seed,
 * using the prototype's sample files (reference/2-prototype/src/app.js {@code impSample}).
 */
class ImportTypesTest
{
    private static final int PORT = 18_317;

    /** The prototype's holiday sample: one impossible date (2026-13-01). */
    private static final String HOLIDAY_SAMPLE = "date,name\n2026-10-02,Gandhi Jayanti\n2026-11-09,Diwali break\n"
            + "2026-11-10,Diwali break\n2026-12-25,Christmas\n15-08-2026,Independence Day\n2026-13-01,New Year\n";

    /** The prototype's OKR sample: line 11 has quarter Q5, line 12 an unknown owner. */
    private static final String OKR_SAMPLE = """
            quarter,level,owner_email,objective,key_result,start,target,current,unit,value,epic
            Q4 2026,Individual,priya@pulse.demo,Cut alert noise for NOC teams,False-positive alerts,30,10,30,%,Outcome Ownership,PLAT-2301
            Q4 2026,Individual,priya@pulse.demo,Cut alert noise for NOC teams,Noisy rules retired,0,25,0,rules,Outcome Ownership,PLAT-2301
            Q4 2026,Individual,arjun@pulse.demo,Quota service GA,Tenants on quota enforcement,0,100,0,%,Outcome Ownership,PLAT-2201
            Q4 2026,Individual,neha@pulse.demo,Trap filter v2,Filter latency p95,120,40,120,ms,Problem Solving,
            Q4 2026,Team,,"Faster, safer releases",Change failure rate,11,6,11,%,Agility in Execution,
            Q4 2026,Team,,"Faster, safer releases",Release lead time,12,7,12,days,Agility in Execution,
            Q3 2026,Individual,priya@pulse.demo,Make ingestion safe for noisy tenants,Per-tenant rate limiting live for all tenants,0,100,85,%,Outcome Ownership,PLAT-2231
            Q3 2026,Individual,priya@pulse.demo,Make ingestion safe for noisy tenants,Ingestion p95 latency under load,900,400,470,ms,Outcome Ownership,PLAT-2231
            Q4 2026,Individual,kiran@pulse.demo,Own the NetFlow collector,Collector crash-free days,80,99,80,%,Ownership,
            Q5 2026,Individual,dev@pulse.demo,Load-test every release,Releases load-tested,0,4,0,releases,Agility in Execution,
            Q4 2026,Individual,old.contractor@pulse.demo,Hand over legacy agent,Docs written,0,5,0,docs,,
            """;

    private static TestApi api;

    private static WebClient client;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null);

        // TestApi applies the platform scripts only; add the imports tables and the OKR schema and demo OKRs.
        for (String script : new String[] {"modules/imports/migrations/V300__imports_base.sql",
                "modules/okrs/migrations/V210__okrs.sql", "modules/okrs/seed/S210__demo_okrs.sql"})
        {
            try (InputStream in = ImportTypesTest.class.getClassLoader().getResourceAsStream(script))
            {
                api.exec(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }

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

    private static JsonObject check(TestApi.Session session, String type, String query, String csv, int expectedStatus) throws Exception
    {
        HttpResponse<Buffer> res = await(client.post(PORT, "localhost", "/api/v1/imports/" + type + "/check" + query)
                .putHeader("X-Real-IP", TestApi.newIp())
                .putHeader("Cookie", session.cookie())
                .putHeader(SessionHandler.CSRF_HEADER, session.csrf())
                .putHeader("Content-Type", "text/csv")
                .sendBuffer(Buffer.buffer(csv)));

        assertEquals(expectedStatus, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static JsonObject commit(TestApi.Session session, String type, JsonObject checked) throws Exception
    {
        HttpResponse<Buffer> res = api.post("/api/v1/imports/" + type + "/commit", session,
                new JsonObject().put("checkId", checked.getString("checkId")));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static JsonObject undo(TestApi.Session session, JsonObject committed) throws Exception
    {
        HttpResponse<Buffer> res = api.post("/api/v1/imports/history/" + committed.getString("importId") + "/undo", session,
                new JsonObject());

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    private static boolean hasMessage(JsonArray messages, int line, String column, String fragment)
    {
        for (Object item : messages)
        {
            JsonObject m = (JsonObject) item;

            if (m.getInteger("line") == line && column.equals(m.getString("column")) && m.getString("message").contains(fragment))
            {
                return true;
            }
        }

        return false;
    }

    // ------------------------------------------------------------------ holidays

    @Test
    void holidayImportIsForAttendanceUploadOrRulesManage() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/imports/holidays/template", Map.of(
                RoleMatrix.ADMIN, 200,       // hr@: attendance.upload, rules.manage
                RoleMatrix.MANAGER, 403,
                RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403));
    }

    @Test
    void holidaySampleChecksCommitsAndUndoes() throws Exception
    {
        TestApi.Session hr = RoleMatrix.session(api, RoleMatrix.ADMIN);

        long before = api.count("SELECT count(*) FROM holidays");

        JsonObject checked = check(hr, "holidays", "?fileName=holidays.csv", HOLIDAY_SAMPLE, 200);

        assertEquals(6, checked.getInteger("rowsRead"));

        assertEquals(1, checked.getInteger("errors"));

        assertTrue(hasMessage(checked.getJsonArray("errorList"), 7, "date", "not a valid date"));

        int ready = checked.getInteger("ready");

        assertEquals(5, ready + checked.getInteger("skipped"));

        JsonObject committed = commit(hr, "holidays", checked);

        assertEquals(ready, committed.getInteger("imported"));

        assertEquals("Christmas", api.queryOne("SELECT name FROM holidays WHERE holiday_on = '2026-12-25' AND location IS NULL")
                .getString("name"));

        undo(hr, committed);

        assertEquals(before, api.count("SELECT count(*) FROM holidays"));
    }

    @Test
    void holidayRulesDuplicateWeekendPastRenameAndSkip() throws Exception
    {
        TestApi.Session hr = RoleMatrix.session(api, RoleMatrix.ADMIN);

        api.exec("INSERT INTO holidays (holiday_on, name, external_id) VALUES ('2027-01-26', 'Republic Day', '2027-01-26'), "
                + "('2027-03-04', 'Holi', '2027-03-04')");

        String csv = "date,name\n2027-01-26,Republic Day\n2027-03-04,Holi (Dhulandi)\n2027-01-02,Weekend party\n"
                + "2027-05-01,Labour Day\n01-05-2027,Labour Day again\n2020-06-01,Old day\n";

        JsonObject checked = check(hr, "holidays", "", csv, 200);

        assertEquals(1, checked.getInteger("skipped"));

        assertEquals(3, checked.getInteger("new"));

        assertEquals(1, checked.getInteger("update"));

        assertTrue(hasMessage(checked.getJsonArray("errorList"), 6, "date", "Same date as line 5"));

        assertTrue(hasMessage(checked.getJsonArray("warningList"), 4, "date", "is a Saturday"));

        assertTrue(hasMessage(checked.getJsonArray("warningList"), 7, "date", "Past date"));

        JsonObject committed = commit(hr, "holidays", checked);

        assertEquals("Holi (Dhulandi)", api.queryOne("SELECT name FROM holidays WHERE holiday_on = '2027-03-04'").getString("name"));

        undo(hr, committed);

        assertEquals("Holi", api.queryOne("SELECT name FROM holidays WHERE holiday_on = '2027-03-04'").getString("name"));

        assertEquals(0, api.count("SELECT count(*) FROM holidays WHERE holiday_on = '2027-05-01'"));

        HttpResponse<Buffer> exported = api.get("/api/v1/imports/holidays/export", hr);

        assertTrue(exported.bodyAsString().contains("date,name\r\n"), exported.bodyAsString());

        assertTrue(exported.bodyAsString().contains("2027-03-04,Holi\r\n"), exported.bodyAsString());
    }

    // ------------------------------------------------------------------ OKRs

    @Test
    void okrImportIsForOkrOwnOrOkrTeam() throws Exception
    {
        RoleMatrix.assertRoleMatrix(api, "GET /api/v1/imports/okrs/template", Map.of(
                RoleMatrix.ADMIN, 200,
                RoleMatrix.MANAGER, 200,
                RoleMatrix.EMPLOYEE, 200,    // okr.own: own rows only
                RoleMatrix.PEER, 200));
    }

    @Test
    void okrSampleChecksCommitsAndUndoesAsTheManager() throws Exception
    {
        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        long objectives = api.count("SELECT count(*) FROM objectives");

        long checkIns = api.count("SELECT count(*) FROM key_result_checkins");

        JsonObject checked = check(rahul, "okrs", "?fileName=okrs.csv", OKR_SAMPLE, 200);

        assertEquals(11, checked.getInteger("rowsRead"));

        assertEquals(2, checked.getInteger("errors"), checked.getJsonArray("errorList").encodePrettily());

        assertTrue(hasMessage(checked.getJsonArray("errorList"), 11, "quarter", "is not a quarter in Pulse"));

        assertTrue(hasMessage(checked.getJsonArray("errorList"), 12, "owner_email", "No user with old.contractor@pulse.demo"));

        assertTrue(hasMessage(checked.getJsonArray("warningList"), 10, "value", "not one of the 11 values"));

        assertEquals(7, checked.getInteger("new"));

        assertEquals(2, checked.getInteger("update"));

        JsonObject committed = commit(rahul, "okrs", checked);

        assertEquals(9, committed.getInteger("imported"));

        // Five new objectives: Priya, Arjun, Neha and Kiran in Q4, plus the Platform team objective.
        assertEquals(objectives + 5, api.count("SELECT count(*) FROM objectives"));

        assertEquals(1, api.count("SELECT count(*) FROM objectives WHERE level = 'Team' AND team = 'Platform' "
                + "AND title = 'Faster, safer releases'"));

        assertTrue(api.count("SELECT count(*) FROM key_result_checkins") > checkIns, "a new current value is a bulk check-in");

        undo(rahul, committed);

        assertEquals(objectives, api.count("SELECT count(*) FROM objectives"));

        assertEquals(checkIns, api.count("SELECT count(*) FROM key_result_checkins"));
    }

    @Test
    void anEmployeeMayImportOnlyTheirOwnRows() throws Exception
    {
        TestApi.Session priya = RoleMatrix.session(api, RoleMatrix.EMPLOYEE);

        String csv = "quarter,owner_email,objective,key_result,start,target\n"
                + "Q4 2026,priya@pulse.demo,Learn Rust,Rust katas done,0,10\n"
                + "Q4 2026,arjun@pulse.demo,Arjun's goal,Something,0,10\n"
                + "Q4 2026,priya@pulse.demo,Learn Rust,Rust katas done,0,10\n"
                + "Q4 2026,priya@pulse.demo,Flat goal,Nothing moves,5,5\n";

        JsonObject checked = check(priya, "okrs", "", csv, 200);

        assertTrue(hasMessage(checked.getJsonArray("errorList"), 3, "owner_email", "can't manage OKRs for"),
                checked.getJsonArray("errorList").encodePrettily());

        assertTrue(hasMessage(checked.getJsonArray("errorList"), 4, "key_result", "Same key result twice"));

        assertTrue(hasMessage(checked.getJsonArray("errorList"), 5, "target", "Target must differ from start"));

        assertEquals(1, checked.getInteger("ready"));
    }

    @Test
    void okrExportNeedsAQuarterAndRoundTrips() throws Exception
    {
        TestApi.Session rahul = RoleMatrix.session(api, RoleMatrix.MANAGER);

        HttpResponse<Buffer> noQuarter = api.get("/api/v1/imports/okrs/export", rahul);

        assertEquals(400, noQuarter.statusCode());

        HttpResponse<Buffer> exported = api.get("/api/v1/imports/okrs/export?quarter="
                + URLEncoder.encode("Q3 2026", StandardCharsets.UTF_8), rahul);

        assertEquals(200, exported.statusCode(), exported.bodyAsString());

        String csv = exported.bodyAsString();

        assertTrue(csv.contains("Make ingestion safe for noisy tenants"), csv);

        // Spec "Round trip": the unchanged export checks back with no errors and nothing new.
        JsonObject checked = check(rahul, "okrs", "", csv, 200);

        assertEquals(0, checked.getInteger("errors"), checked.getJsonArray("errorList").encodePrettily());

        assertEquals(0, checked.getInteger("new"));
    }
}
