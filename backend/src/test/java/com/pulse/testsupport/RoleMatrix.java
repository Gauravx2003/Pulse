package com.pulse.testsupport;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Per-role contract test for one endpoint (task 3.5, spec rbac-visibility "Contract test matrix").
 * Every module's endpoint gets one call:
 *
 * <pre>{@code
 * RoleMatrix.assertRoleMatrix(api, "GET /api/v1/okrs/people/{person:priya@pulse.demo}", Map.of(
 *         RoleMatrix.ADMIN, 200,
 *         RoleMatrix.MANAGER, 200,      // Priya's direct manager
 *         RoleMatrix.EMPLOYEE, 200,     // Priya herself
 *         RoleMatrix.PEER, 403));       // Arjun, same team
 * }</pre>
 *
 * <p>Keys are demo account emails; Admin, Manager, Employee and peer must all be asserted. Path
 * placeholders {@code {person:<email>}} and {@code {user:<email>}} resolve to that account's person or
 * user id. Beyond the status, every refusal must carry no data (only {@code error}) and all 403s and
 * 404s of one endpoint must look alike, so a refusal never reveals whether the record exists.
 */
public final class RoleMatrix
{
    /** Admin (HR): all permissions. */
    public static final String ADMIN = "hr@pulse.demo";

    /** Manager of the Platform team (Priya, Arjun, Neha, Kiran, Dev, Sara, Rohit, Anjali). */
    public static final String MANAGER = "rahul@pulse.demo";

    /** Employee whose records the matrix usually targets. */
    public static final String EMPLOYEE = "priya@pulse.demo";

    /** Employee in the same team as {@link #EMPLOYEE}: a peer. */
    public static final String PEER = "arjun@pulse.demo";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(person|user):([^}]+)}");

    private static final Map<String, TestApi.Session> SESSIONS = new ConcurrentHashMap<>();

    private RoleMatrix()
    {
    }

    public static void assertRoleMatrix(TestApi api, String endpoint, Map<String, Integer> expectations) throws Exception
    {
        assertRoleMatrix(api, endpoint, null, expectations);
    }

    /**
     * @param endpoint     {@code "<METHOD> <path>"}, e.g. {@code "POST /api/v1/x/{person:priya@pulse.demo}/approve"}
     * @param body         JSON body for writes (null for none)
     * @param expectations demo account email -> expected HTTP status
     */
    public static void assertRoleMatrix(TestApi api, String endpoint, JsonObject body, Map<String, Integer> expectations)
            throws Exception
    {
        for (String role : List.of(ADMIN, MANAGER, EMPLOYEE, PEER))
        {
            if (!expectations.containsKey(role))
            {
                fail("Role matrix for " + endpoint + " must assert Admin, Manager, Employee and peer; missing " + role);
            }
        }

        String[] parts = endpoint.strip().split("\\s+", 2);

        assertEquals(2, parts.length, "endpoint must be '<METHOD> <path>'");

        String method = parts[0].toUpperCase();
        String path = resolve(api, parts[1]);

        Map<String, Integer> actual = new LinkedHashMap<>();
        JsonObject firstRefusal = null;

        for (Map.Entry<String, Integer> expectation : expectations.entrySet())
        {
            TestApi.Session session = session(api, expectation.getKey());

            HttpResponse<Buffer> res = switch (method)
            {
                case "GET" -> api.get(path, session);
                case "POST" -> api.post(path, session, body == null ? new JsonObject() : body);
                case "PUT" -> api.put(path, session, body == null ? new JsonObject() : body);
                case "DELETE" -> api.delete(path, session);
                default -> throw new IllegalArgumentException("Unsupported method " + method);
            };

            actual.put(expectation.getKey(), res.statusCode());

            if (res.statusCode() == 403 || res.statusCode() == 404)
            {
                JsonObject json = res.bodyAsJsonObject();

                assertEquals(Set.of("error"), json.fieldNames(),
                        expectation.getKey() + ": a refusal must carry no data: " + json);

                JsonObject shape = new JsonObject()
                        .put("status", res.statusCode())
                        .put("code", json.getJsonObject("error").getString("code"))
                        .put("message", json.getJsonObject("error").getString("message"));

                if (firstRefusal == null)
                {
                    firstRefusal = shape;
                }
                else
                {
                    assertEquals(firstRefusal, shape, "all refusals of " + endpoint + " must look alike");
                }
            }
        }

        assertEquals(expectations, actual, "role matrix of " + endpoint);
    }

    /** Asserts two responses are indistinguishable apart from the request id. */
    public static void assertSameRefusal(HttpResponse<Buffer> a, HttpResponse<Buffer> b)
    {
        assertEquals(a.statusCode(), b.statusCode());

        JsonObject ea = a.bodyAsJsonObject().copy();
        JsonObject eb = b.bodyAsJsonObject().copy();

        ea.getJsonObject("error").remove("requestId");
        eb.getJsonObject("error").remove("requestId");

        assertEquals(ea, eb);
        assertTrue(a.statusCode() >= 400);
    }

    /** A cached signed-in session for a demo account on this server. */
    public static TestApi.Session session(TestApi api, String email) throws Exception
    {
        String key = System.identityHashCode(api) + "|" + email;

        TestApi.Session session = SESSIONS.get(key);

        if (session == null)
        {
            session = api.signIn(email, TestApi.DEMO_PASSWORD);

            SESSIONS.put(key, session);
        }

        return session;
    }

    private static String resolve(TestApi api, String path) throws Exception
    {
        Matcher m = PLACEHOLDER.matcher(path);
        StringBuilder out = new StringBuilder();

        while (m.find())
        {
            String email = m.group(2).replace("'", "''");

            String sql = m.group(1).equals("person")
                    ? "SELECT p.id FROM people p JOIN users u ON u.id = p.user_id WHERE lower(u.email) = lower('" + email + "')"
                    : "SELECT id FROM users WHERE lower(email) = lower('" + email + "')";

            JsonObject row = api.queryOne(sql);

            if (row == null)
            {
                fail("No " + m.group(1) + " for " + m.group(2));
            }

            m.appendReplacement(out, row.getString("id"));
        }

        m.appendTail(out);

        return out.toString();
    }
}
