package com.pulse.modules;

import com.pulse.platform.audit.AuditRoutes;
import com.pulse.platform.auth.AuthRoutes;
import com.pulse.platform.auth.AuthService;
import com.pulse.platform.auth.AuthSettings;
import com.pulse.platform.auth.PasswordService;
import com.pulse.platform.auth.PermissionCatalogue;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.RateLimiter;
import com.pulse.platform.jobs.JobRoutes;
import com.pulse.platform.jobs.JobRunner;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.ModuleRegistry;
import com.pulse.platform.module.PulseModule;
import com.pulse.platform.nav.Navigation;
import com.pulse.platform.notify.NotificationRoutes;
import com.pulse.platform.policy.PolicyService;
import com.pulse.platform.shell.ShellRepository;
import com.pulse.platform.shell.ShellRoutes;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 23.1: the per-role contract matrix of every endpoint of all three tracks and of the platform
 * (spec platform/rbac-visibility "Contract test matrix", "Existence is not leaked" and "Visibility rules").
 * Each module commits its matrix as {@code backend/modules/<id>/contract.test}; the platform routes
 * (auth, me, shell, audit, notifications, jobs) live in {@code src/test/resources/contracts/platform.test}:
 *
 * <pre>
 * # comment
 * METHOD /api/v1/&lt;id&gt;/path [json body] [fresh] =&gt; ADMIN MANAGER EMPLOYEE PEER MENTOR OWNER
 * GET /api/v1/work/people/{person:priya@pulse.demo}/overview =&gt; 200 200 200 403 403 403
 * </pre>
 *
 * The six statuses are those of hr@ (Admin), rahul@ (Platform manager), priya@ (Employee), arjun@ (her
 * peer), sara@ (mentor of Anjali, Neha, Kiran and Rohit, not a manager) and sana@ (account owner of
 * Priya's, Arjun's and Dev's visits, no work profile), asserted with {@link RoleMatrix#assertRoleMatrix} on
 * one real server with every module's demo seed and "today" pinned to 28 Sep 2026; refusals carry no data.
 * {@code fresh} signs each role in again for that line (sign-out ends the session it is sent with).
 *
 * <p>Path and body placeholders: {@code {person:email}}, {@code {user:email}},
 * {@code {id:table.column=value}} (the first {@code id} of that table with that value, URL-decoded so
 * {@code %20} is a space); the Track 2 shortcuts {@code {extra:REF}}, {@code {objective:TITLE}},
 * {@code {keyResult:DESCRIPTION}}, {@code {milestone:TITLE}}, {@code {skill:NAME}} (Priya's),
 * {@code {pr:email}} and {@code {prHidden:email}}; and {@code {request360:email}} (that reviewer's open
 * request in a running cycle), {@code {oneOnOne:email}} and {@code {action:email}} (that person's latest 1:1
 * and first action item), {@code {checkin:EXTERNAL_ID}} (the latest check-in of that seeded training
 * assignment) and {@code {notification:email}}. Writes send a body that fails validation, or a stale
 * version, where the route lets the role in, so the matrix asserts who may call it without changing data;
 * the few writes that take no body say so in their file.
 *
 * <p>Existence is not leaked: for every role refused on a line, the same request with each placeholder id
 * swapped for an id that addresses nothing must get the identical response. A starred refusal
 * ({@code 403*}) is an action refused on a record that role may see (a manager filing the engineer's visit
 * report); it may give a specific reason, and must then differ from the answer for a missing record.
 * {@link #listsAnsweringEveryRoleShowOnlyVisiblePeople()} covers lists that answer 200 to every role.
 *
 * <p>{@link #everyEndpointHasAContractLine()} mounts every module's and the platform's routes and fails
 * for any endpoint without a line, so a new endpoint cannot skip the matrix.
 */
class ContractMatrixTest
{

    private static final int PORT = 18_274;

    private static final String PLATFORM_FILE = "src/test/resources/contracts/platform.test";

    private static final List<String> ROLES = List.of(RoleMatrix.ADMIN, RoleMatrix.MANAGER, RoleMatrix.EMPLOYEE, RoleMatrix.PEER,
            RoleMatrix.MENTOR, RoleMatrix.OWNER);

    private static final Pattern LINE = Pattern.compile(
            "^(GET|POST|PUT|DELETE)\\s+((?:\\{[^}]*}|[^\\s{])+)(?:\\s+(\\{(?:\".*)?}))?(\\s+fresh)?\\s+=>"
                    + "\\s+(\\d{3}\\*?)\\s+(\\d{3}\\*?)\\s+(\\d{3}\\*?)\\s+(\\d{3}\\*?)\\s+(\\d{3}\\*?)\\s+(\\d{3}\\*?)\\s*$");

    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\{(person|user|id|extra|objective|keyResult|milestone|skill|pr|prHidden|request360|oneOnOne|action|checkin|notification):([^}]+)}");

    private static final Pattern TABLE_LOOKUP = Pattern.compile("^([a-z_]+)\\.([a-z_]+)=(.+)$");

    private static final Map<String, String> LOOKUPS = Map.ofEntries(
            Map.entry("person", "SELECT p.id FROM people p JOIN users u ON u.id = p.user_id WHERE lower(u.email) = lower($1)"),
            Map.entry("user", "SELECT id FROM users WHERE lower(email) = lower($1)"),
            Map.entry("extra", "SELECT id FROM extra_items WHERE ref = $1 ORDER BY id LIMIT 1"),
            Map.entry("objective", "SELECT id FROM objectives WHERE title = $1 ORDER BY id LIMIT 1"),
            Map.entry("keyResult", "SELECT id FROM key_results WHERE description = $1 ORDER BY id LIMIT 1"),
            Map.entry("milestone", "SELECT id FROM milestones WHERE title = $1 ORDER BY id LIMIT 1"),
            // that reviewer's open 360° request in a running cycle
            Map.entry("request360", """
                    SELECT r.id FROM review_requests r JOIN review_cycles c ON c.id = r.cycle_id
                    JOIN review_reviewer_keys k ON k.pseudonym = r.reviewer_key JOIN users u ON u.id = k.user_id
                    WHERE lower(u.email) = lower($1) AND c.status = 'running' AND r.status <> 'submitted'
                    ORDER BY r.id LIMIT 1"""),
            // that person's latest 1:1 and its first action item
            Map.entry("oneOnOne", """
                    SELECT o.id FROM one_on_ones o JOIN people p ON p.id = o.person_id JOIN users u ON u.id = p.user_id
                    WHERE lower(u.email) = lower($1) ORDER BY o.held_on DESC, o.id LIMIT 1"""),
            Map.entry("action", """
                    SELECT a.id FROM one_on_one_actions a JOIN people p ON p.id = a.person_id JOIN users u ON u.id = p.user_id
                    WHERE lower(u.email) = lower($1) ORDER BY a.id LIMIT 1"""),
            // the latest check-in of the training assignment with that seed external id
            Map.entry("checkin", """
                    SELECT c.id FROM training_checkins c JOIN training_assignments a ON a.id = c.assignment_id
                    WHERE a.external_id = $1 ORDER BY c.period_start DESC, c.id LIMIT 1"""),
            Map.entry("notification", """
                    SELECT n.id FROM notifications n JOIN users u ON u.id = n.user_id
                    WHERE lower(u.email) = lower($1) ORDER BY n.created_at DESC, n.id LIMIT 1"""),
            Map.entry("skill", """
                    SELECT s.id FROM skills_matrix s JOIN people p ON p.id = s.person_id
                    WHERE p.source = 'seed' AND p.external_id = 'ps' AND s.skill = $1 LIMIT 1"""),
            Map.entry("pr", """
                    SELECT r.id FROM pull_requests r JOIN people p ON p.id = r.author_id JOIN users u ON u.id = p.user_id
                    WHERE u.email = $1 ORDER BY r.opened_at, r.id LIMIT 1"""),
            Map.entry("prHidden", """
                    SELECT r.id FROM pull_requests r JOIN people p ON p.id = r.author_id JOIN users u ON u.id = p.user_id
                    WHERE u.email = $1 AND NOT EXISTS (
                        SELECT 1 FROM pr_reviews v JOIN people x ON x.id = v.reviewer_id JOIN users xu ON xu.id = x.user_id
                        WHERE v.pull_request_id = r.id AND xu.email = 'arjun@pulse.demo')
                    ORDER BY r.opened_at, r.id LIMIT 1"""));

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        // every module, the way the demo runs; the per-user limit is lifted for ~2,000 matrix calls
        JsonObject config = new JsonObject()
                .put("today", "2026-09-28")
                .put("http.userPerMinute", 100_000)
                .put("calc.closePeriods", false);

        api = TestApi.start(PORT, config, productModules().stream().map(PulseModule::id).toArray(String[]::new));

        // the test-only probe module (on this classpath, so the server mounts it) widens the training domain
        // through probe_mentors; an empty table keeps that relation inert
        api.exec("CREATE TABLE IF NOT EXISTS probe_mentors (mentor_id uuid, mentee_id uuid)");
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    @TestFactory
    List<DynamicTest> everyContractLinePasses() throws Exception
    {
        List<DynamicTest> tests = new ArrayList<>();

        for (Contract c : contracts())
        {
            tests.add(DynamicTest.dynamicTest(c.source() + ": " + c.method() + " " + c.path(), () -> check(c)));
        }

        return tests;
    }

    @Test
    void everyEndpointHasAContractLine() throws Exception
    {
        List<Contract> contracts = contracts();

        Map<String, Pattern> routes = mountedRoutes();

        List<String> missing = new ArrayList<>();

        for (Map.Entry<String, Pattern> route : routes.entrySet())
        {
            String method = route.getKey().substring(0, route.getKey().indexOf(' '));

            boolean covered = contracts.stream().anyMatch(c -> c.method().equals(method)
                    && route.getValue().matcher(c.path().replaceAll("\\?.*$", "")).matches());

            if (!covered)
            {
                missing.add(route.getKey());
            }
        }

        assertTrue(routes.size() >= 200, "found the routes of all three tracks and the platform: " + routes.size());
        assertEquals(List.of(), missing, "endpoints without a line in backend/modules/<id>/contract.test or " + PLATFORM_FILE);
    }

    /**
     * The list endpoints that answer 200 to every role must filter by visibility: what a role gets lists
     * only people it may see (itself, reports, mentees for training). Statuses alone cannot show a leak here.
     */
    @Test
    void listsAnsweringEveryRoleShowOnlyVisiblePeople() throws Exception
    {
        String anjali = person("anjali@pulse.demo");

        // training: Sara mentors Anjali; Priya, Arjun and Sana neither mentor nor manage her
        assertEquals(List.of(anjali), personIds(RoleMatrix.MENTOR, "/api/v1/training/assignments?personId=" + anjali, "items"));

        for (String role : List.of(RoleMatrix.EMPLOYEE, RoleMatrix.PEER, RoleMatrix.OWNER))
        {
            assertEquals(List.of(), personIds(role, "/api/v1/training/assignments?personId=" + anjali, "items"), role);
        }

        // Sana mentors nobody and has no training plan
        assertEquals(List.of(), personIds(RoleMatrix.OWNER, "/api/v1/training/assignments", "items"));

        // attendance Team tab: an Employee sees only themself, never a peer, a mentee or the visits they own
        for (String role : List.of(RoleMatrix.EMPLOYEE, RoleMatrix.PEER, RoleMatrix.MENTOR))
        {
            assertEquals(List.of(person(role)), personIds(role, "/api/v1/attendance/team?period=m:2026-08", "people"), role);
        }

        // Sana has an account but no work profile: nobody
        assertEquals(List.of(), personIds(RoleMatrix.OWNER, "/api/v1/attendance/team?period=m:2026-08", "people"));

        // visits: an Employee lists their own visits and, as account owner, the visits they own
        for (Object o : ok(RoleMatrix.PEER, "/api/v1/visits/items").getJsonArray("items"))
        {
            assertEquals(person(RoleMatrix.PEER), ((JsonObject) o).getString("personId"), "Arjun lists only his visits");
        }

        for (Object o : ok(RoleMatrix.OWNER, "/api/v1/visits/items?owned=true").getJsonArray("items"))
        {
            assertEquals("Sana Mirza", ((JsonObject) o).getString("ownerName"), "Sana lists only the visits she owns");
        }
    }

    private static String person(String email) throws Exception
    {
        return api.queryOne(LOOKUPS.get("person").replace("$1", "'" + email + "'")).getString("id");
    }

    private static JsonObject ok(String role, String path) throws Exception
    {
        HttpResponse<Buffer> res = api.get(path, RoleMatrix.session(api, role));

        assertEquals(200, res.statusCode(), role + " GET " + path + ": " + res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    /** The distinct {@code personId}s of the rows under {@code field}, in order. */
    private static List<String> personIds(String role, String path, String field) throws Exception
    {
        return ok(role, path).getJsonArray(field).stream()
                .map(o -> ((JsonObject) o).getString("personId"))
                .distinct()
                .toList();
    }

    // ------------------------------------------------------------------ one line

    private static void check(Contract c) throws Exception
    {
        Map<String, Integer> expected = new LinkedHashMap<>();

        for (int i = 0; i < ROLES.size(); i++)
        {
            expected.put(ROLES.get(i), c.statuses().get(i));
        }

        List<String> ids = new ArrayList<>();

        String path = resolve(c.path(), ids);

        JsonObject body = c.body() == null ? null : new JsonObject(resolve(c.body().encode(), new ArrayList<>()));

        RoleMatrix.assertRoleMatrix(api, c.method() + " " + path, body, expected, c.fresh(), false);

        if (ids.isEmpty() || c.fresh())
        {
            return;
        }

        // Existence is not leaked: a refused role gets the same answer for a record that does not exist
        String ghost = path;

        for (String id : ids)
        {
            ghost = ghost.replace(id, UUID.randomUUID().toString());
        }

        for (int i = 0; i < ROLES.size(); i++)
        {
            int status = c.statuses().get(i);

            if (status != 403 && status != 404)
            {
                continue;
            }

            String role = ROLES.get(i);

            TestApi.Session session = RoleMatrix.session(api, role);

            String real = describe(send(c.method(), path, body, session));
            String missing = describe(send(c.method(), ghost, body, session));

            if (c.visible().get(i))
            {
                // 403*: an action refused on a record this role may see, so a specific reason is fine; the
                // star is checked too: the answer must differ from the one for a record that does not exist
                assertNotEquals(real, missing, role + ": " + c.method() + " " + c.path()
                        + " is starred (record visible to this role) but answers like a missing record");
            }
            else
            {
                assertEquals(real, missing,
                        role + ": " + c.method() + " " + c.path() + " must answer a missing record as it answers a forbidden one");
            }
        }
    }

    /** Status and body without the request id, to compare two answers. */
    private static String describe(HttpResponse<Buffer> res)
    {
        JsonObject json = res.bodyAsJsonObject().copy();

        if (json.getJsonObject("error") != null)
        {
            json.getJsonObject("error").remove("requestId");
        }

        return res.statusCode() + " " + json.encode();
    }

    private static HttpResponse<Buffer> send(String method, String path, JsonObject body, TestApi.Session session) throws Exception
    {
        JsonObject payload = body == null ? new JsonObject() : body;

        return switch (method)
        {
            case "GET" -> api.get(path, session);
            case "POST" -> api.post(path, session, payload);
            case "PUT" -> api.put(path, session, payload);
            case "DELETE" -> api.delete(path, session);
            default -> throw new IllegalArgumentException("Unsupported method " + method);
        };
    }

    // ------------------------------------------------------------------ routes

    /** The product modules: those with a directory under {@code backend/modules/} (test probes excluded). */
    private static List<PulseModule> productModules()
    {
        return ModuleRegistry.discover().modules().stream()
                .filter(m -> Files.isDirectory(Path.of("modules", m.id())))
                .toList();
    }

    /** Every {@code METHOD /api/v1/...} the server mounts, mapped to a pattern a contract path must match. */
    private static Map<String, Pattern> mountedRoutes()
    {
        ModuleRegistry registry = ModuleRegistry.of(productModules());

        PermissionCatalogue catalogue = PermissionCatalogue.of(registry);

        // mounting routes runs no query: a pool that never connects is enough
        PgPool idle = PgPool.pool(api.vertx(), new PgConnectOptions(), new PoolOptions());

        SqlExecutor sql = new SqlExecutor(idle);

        PolicyService policy = new PolicyService(sql, catalogue.keys(), registry.visibilityRelations(), false);

        ModuleContext ctx = new ModuleContext(api.vertx(), api.config(), idle, policy, null, registry);

        Navigation navigation = Navigation.of(registry);

        AuthService auth = new AuthService(api.vertx(), sql, AuthSettings.from(api.config()), catalogue.keys());

        Map<String, Pattern> routes = new LinkedHashMap<>();

        // the platform, mounted as HttpServerVerticle does
        Router platform = Router.router(api.vertx());

        ShellRepository shell = new ShellRepository(sql);

        ShellRoutes.mountPublic(platform, "/api/v1", shell, true);
        AuthRoutes.mount(platform, "/api/v1", auth, new PasswordService(api.vertx(), sql, auth), navigation,
                RateLimiter.perUser(api.vertx(), "user", 300, 60));
        AuditRoutes.mount(platform, "/api/v1", policy, sql);
        ShellRoutes.mount(platform, "/api/v1", shell, policy, null);
        NotificationRoutes.mount(platform, "/api/v1", ctx, navigation);
        JobRoutes.mount(platform, "/api/v1", policy, new JobRunner(ctx));

        collect(platform, "", routes);

        for (PulseModule module : registry.modules())
        {
            Router router = Router.router(api.vertx());

            module.routes(router, ctx);

            collect(router, "/api/v1/" + module.id(), routes);
        }

        idle.close();

        return routes;
    }

    private static void collect(Router router, String prefix, Map<String, Pattern> into)
    {
        for (Route route : router.getRoutes())
        {
            if (route.getPath() == null || route.methods() == null || !(prefix + route.getPath()).startsWith("/api/v1/"))
            {
                continue;
            }

            String full = prefix + route.getPath();

            Pattern pattern = Pattern.compile("^" + full.replaceAll(":[A-Za-z]+", "[^/]+") + "$");

            for (HttpMethod method : route.methods())
            {
                into.put(method.name() + " " + full, pattern);
            }
        }
    }

    // ------------------------------------------------------------------ contract files

    /** {@code visible} per role: a starred refusal ({@code 403*}) is on a record that role may see. */
    private record Contract(String source, String method, String path, JsonObject body, boolean fresh, List<Integer> statuses,
                            List<Boolean> visible)
    {
    }

    private static List<Contract> contracts() throws Exception
    {
        List<Contract> out = new ArrayList<>(read(Path.of(PLATFORM_FILE), null));

        for (PulseModule module : productModules())
        {
            Path file = Path.of("modules", module.id(), "contract.test");

            if (!Files.exists(file))
            {
                fail("missing " + file);
            }

            out.addAll(read(file, module.id()));
        }

        return out;
    }

    private static List<Contract> read(Path file, String module) throws Exception
    {
        List<Contract> out = new ArrayList<>();

        int number = 0;

        for (String line : Files.readAllLines(file))
        {
            number++;

            String text = line.strip();

            if (text.isEmpty() || text.startsWith("#"))
            {
                continue;
            }

            Matcher m = LINE.matcher(text);

            if (!m.matches())
            {
                fail(file + ":" + number + ": expected 'METHOD /path [json] [fresh] => ADMIN MANAGER EMPLOYEE PEER MENTOR OWNER', got: "
                        + text);
            }

            if (module != null && !m.group(2).startsWith("/api/v1/" + module + "/"))
            {
                fail(file + ":" + number + ": the path must start with /api/v1/" + module + "/");
            }

            List<Integer> statuses = new ArrayList<>();

            List<Boolean> visible = new ArrayList<>();

            for (int g = 5; g <= 10; g++)
            {
                statuses.add(Integer.parseInt(m.group(g).replace("*", "")));
                visible.add(m.group(g).endsWith("*"));
            }

            out.add(new Contract(module == null ? "platform" : module, m.group(1), m.group(2),
                    m.group(3) == null ? null : new JsonObject(m.group(3)), m.group(4) != null, statuses, visible));
        }

        return out;
    }

    /** Replaces the placeholders of {@code path} with seed ids and adds each id to {@code ids}. */
    private static String resolve(String path, List<String> ids) throws Exception
    {
        Matcher m = PLACEHOLDER.matcher(path);

        StringBuilder out = new StringBuilder();

        while (m.find())
        {
            String kind = m.group(1);
            String key = m.group(2);
            String sql;

            if (kind.equals("id"))
            {
                Matcher t = TABLE_LOOKUP.matcher(key);

                if (!t.matches())
                {
                    fail("{id:" + key + "}: expected {id:table.column=value}");
                }

                sql = "SELECT id FROM " + t.group(1) + " WHERE " + t.group(2) + "::text = $1 ORDER BY id LIMIT 1";
                key = URLDecoder.decode(t.group(3), StandardCharsets.UTF_8);
            }
            else
            {
                sql = LOOKUPS.get(kind);
            }

            JsonObject row = api.queryOne(sql.replace("$1", "'" + key.replace("'", "''") + "'"));

            if (row == null)
            {
                fail("no " + kind + " '" + m.group(2) + "' in the demo seed");
            }

            String id = row.getValue("id").toString();

            ids.add(id);

            m.appendReplacement(out, Matcher.quoteReplacement(id));
        }

        m.appendTail(out);

        return out.toString();
    }
}
