package com.pulse.modules.visits;

import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Track 3's ports read by Track 2's screens, end to end on one server with both tracks' modules and seeds
 * (today pinned to 2026-09-28, sprint S43):
 *
 * <ul>
 *   <li>Task 18.1, spec time/customer-visits "Visit in active sprint": a visit in the active sprint lowers the
 *       person's capacity by its working days and the person view shows the capacity alert
 *       ({@code people.visitDays} into {@code work.liveSprint} / {@code work.liveAlert}).</li>
 *   <li>Task 21.2: a new joiner on an onboarding plan is flagged in team utilization, never offered work by
 *       the rebalance suggestion, gets the On onboarding alert instead of an overload one and no overload
 *       Action Center item ({@code people.onboarding} into {@code work.onboarding}).</li>
 * </ul>
 */
class Track3CrossTrackTest
{
    private static final int PORT = 18_329;

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort", "progress",
                "utilization", "action_center", "visits", "reviews", "training", "attendance");
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
        return api.queryOne("SELECT p.id FROM people p JOIN users u ON u.id = p.user_id WHERE u.email = '" + email + "'").getString("id");
    }

    private static JsonObject overview(String personId) throws Exception
    {
        HttpResponse<Buffer> res = api.get("/api/v1/work/people/" + personId + "/overview", RoleMatrix.session(api, RoleMatrix.MANAGER));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    @Test
    void aVisitInTheActiveSprintLowersCapacityAndShowsTheCapacityAlert() throws Exception
    {
        String priya = personOf(RoleMatrix.EMPLOYEE);

        JsonObject before = overview(priya).getJsonObject("alert");

        int capacity = before.getInteger("capacity");

        // Thursday and Friday of S43, next to her seeded visit on 6–7 Oct: two more working days on site.
        String owner = api.queryOne("SELECT id FROM users WHERE email = 'sana@pulse.demo'").getString("id");

        HttpResponse<Buffer> planned = api.post("/api/v1/visits/items", RoleMatrix.session(api, RoleMatrix.MANAGER), new JsonObject()
                .put("personId", priya).put("customer", "Initech").put("city", "Pune").put("purpose", "Health check")
                .put("from", "2026-10-08").put("to", "2026-10-09").put("requestedBy", "Sana Mirza").put("ownerId", owner));

        assertEquals(201, planned.statusCode(), planned.bodyAsString());

        JsonObject after = overview(priya).getJsonObject("alert");

        assertEquals(capacity - 2, after.getInteger("capacity"), after.encode());

        assertEquals("overloaded", after.getString("kind"), after.encode());

        assertTrue(after.getDouble("daysOver") > before.getDouble("daysOver"), after.encode());
    }

    @Test
    void theOverviewIssueCountOfAMonthIsTheWorkItemsListOfThatMonth() throws Exception
    {
        // Anjali: S41 overlaps September but two of its three issues were resolved in August.
        String anjali = personOf("anjali@pulse.demo");

        HttpResponse<Buffer> overview = api.get("/api/v1/work/people/" + anjali + "/overview?period=m:2026-09",
                RoleMatrix.session(api, RoleMatrix.MANAGER));

        HttpResponse<Buffer> items = api.get("/api/v1/work/people/" + anjali + "/work-items?period=m:2026-09",
                RoleMatrix.session(api, RoleMatrix.MANAGER));

        assertEquals(200, overview.statusCode(), overview.bodyAsString());

        assertEquals(200, items.statusCode(), items.bodyAsString());

        int listed = items.bodyAsJsonObject().getJsonArray("items").size();

        assertEquals(5, listed, items.bodyAsString());

        assertEquals(listed, overview.bodyAsJsonObject().getInteger("issueCount"), overview.bodyAsString());
    }

    @Test
    void anOnboardingJoinerIsNeverJudgedOnUtilizationOrGivenWork() throws Exception
    {
        String rohit = personOf("rohit@pulse.demo");

        JsonObject alert = overview(rohit).getJsonObject("alert");

        assertEquals("onboarding", alert.getString("kind"), alert.encode());

        assertEquals("Software Engineer onboarding", alert.getJsonObject("plan").getString("plan"));

        // Not judged in closed periods either: the month, a closed sprint and the release carry the flag too.
        for (String period : new String[] { "m:2026-09", "s:S42", "r:8.3" })
        {
            HttpResponse<Buffer> closed = api.get("/api/v1/work/people/" + rohit + "/overview?period=" + period,
                    RoleMatrix.session(api, RoleMatrix.MANAGER));

            assertEquals(200, closed.statusCode(), closed.bodyAsString());

            assertTrue(closed.bodyAsJsonObject().getBoolean("onboarding"), period + " " + closed.bodyAsString());
        }

        assertTrue(overview(rohit).getBoolean("onboarding"));

        for (String period : new String[] { "s:S43", "m:2026-09", "r:8.3" })
        {
            HttpResponse<Buffer> items = api.get("/api/v1/work/people/" + rohit + "/work-items?period=" + period,
                    RoleMatrix.session(api, RoleMatrix.MANAGER));

            assertEquals(200, items.statusCode(), items.bodyAsString());

            assertTrue(items.bodyAsJsonObject().getBoolean("onboarding"), period + " " + items.bodyAsString());
        }

        assertFalse(overview(personOf(RoleMatrix.EMPLOYEE)).getBoolean("onboarding"));

        HttpResponse<Buffer> res = api.get("/api/v1/utilization/team", RoleMatrix.session(api, RoleMatrix.MANAGER));

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject team = res.bodyAsJsonObject();

        boolean flagged = false;

        JsonArray onboarding = new JsonArray();

        for (Object o : team.getJsonArray("people"))
        {
            JsonObject p = (JsonObject) o;

            if (p.getBoolean("onboarding", false))
            {
                onboarding.add(p.getString("id"));

                flagged |= rohit.equals(p.getString("id"));
            }
        }

        assertTrue(flagged, team.getJsonArray("people").encode());

        JsonObject suggestion = team.getJsonObject("suggestion");

        if (suggestion != null)
        {
            assertFalse(onboarding.contains(suggestion.getJsonObject("to").getString("id")), suggestion.encode());

            assertNotEquals(rohit, suggestion.getJsonObject("from").getString("id"));
        }

        HttpResponse<Buffer> actions = api.get("/api/v1/action_center/items", RoleMatrix.session(api, RoleMatrix.MANAGER));

        assertEquals(200, actions.statusCode(), actions.bodyAsString());

        for (Object o : actions.bodyAsJsonObject().getJsonArray("items"))
        {
            JsonObject item = (JsonObject) o;

            assertFalse("work.overload".equals(item.getString("key")) && rohit.equals(item.getString("personId")), item.encode());
        }
    }
}
