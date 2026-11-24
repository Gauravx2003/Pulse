package com.pulse.modules;

import com.pulse.modules.action_center.ActionCenterModule;
import com.pulse.modules.bugs.BugsModule;
import com.pulse.modules.effort.EffortModule;
import com.pulse.modules.okrs.OkrsModule;
import com.pulse.modules.progress.ProgressModule;
import com.pulse.modules.pull_requests.PullRequestsModule;
import com.pulse.modules.roadmap.RoadmapModule;
import com.pulse.modules.utilization.UtilizationModule;
import com.pulse.modules.work.WorkModule;
import com.pulse.platform.auth.PermissionCatalogue;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.ModuleRegistry;
import com.pulse.platform.module.PulseModule;
import com.pulse.platform.policy.PolicyService;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 13.5: the per-role contract tests of every Track 2 endpoint (spec platform/rbac-visibility
 * "Contract test matrix"). Each Track 2 module commits its matrix as {@code backend/modules/<id>/contract.test}:
 *
 * <pre>
 * # comment
 * METHOD /api/v1/&lt;id&gt;/path [json body] =&gt; ADMIN MANAGER EMPLOYEE PEER
 * GET /api/v1/work/people/{person:priya@pulse.demo}/overview =&gt; 200 200 200 403
 * </pre>
 *
 * The four statuses are those of hr@ (Admin), rahul@ (Priya's manager), priya@ (Employee) and arjun@
 * (her peer), asserted with {@link RoleMatrix#assertRoleMatrix} on a real server with the Track 2 demo
 * seed and "today" pinned to 28 Sep 2026: refusals carry no data and look alike. Besides
 * {@code {person:email}} and {@code {user:email}}, paths take {@code {extra:REF}}, {@code {objective:TITLE}},
 * {@code {keyResult:DESCRIPTION}}, {@code {milestone:TITLE}}, {@code {skill:NAME}} (Priya's) and
 * {@code {pr:email}} (that person's first pull request) and {@code {prHidden:email}} (one the peer neither
 * wrote nor reviewed). Writes use bodies that fail validation where
 * the route allows the role, so the matrix asserts who may call it without changing data.
 *
 * <p>{@link #everyTrack2EndpointHasAContractLine()} mounts each module's routes and fails for any
 * endpoint without a line, so a new endpoint cannot skip the matrix.
 */
class Track2ContractTest
{

    private static final int PORT = 18_273;

    private static final List<PulseModule> MODULES = List.of(new WorkModule(), new UtilizationModule(), new EffortModule(), new BugsModule(),
            new PullRequestsModule(), new ActionCenterModule(), new ProgressModule(), new OkrsModule(), new RoadmapModule());

    private static final Pattern LINE = Pattern.compile(
            "^(GET|POST|PUT|DELETE)\\s+((?:\\{[^}]*}|[^\\s{])+)(?:\\s+(\\{(?:\".*)?}))?\\s+=>\\s+(\\d{3})\\s+(\\d{3})\\s+(\\d{3})\\s+(\\d{3})\\s*$");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(extra|objective|keyResult|milestone|skill|pr|prHidden):([^}]+)}");

    private static final Map<String, String> LOOKUPS = Map.of(
            "extra", "SELECT id FROM extra_items WHERE ref = $1 ORDER BY id LIMIT 1",
            "objective", "SELECT id FROM objectives WHERE title = $1 ORDER BY id LIMIT 1",
            "keyResult", "SELECT id FROM key_results WHERE description = $1 ORDER BY id LIMIT 1",
            "milestone", "SELECT id FROM milestones WHERE title = $1 ORDER BY id LIMIT 1",
            "skill", """
                    SELECT s.id FROM skills_matrix s JOIN people p ON p.id = s.person_id
                    WHERE p.source = 'seed' AND p.external_id = 'ps' AND s.skill = $1 LIMIT 1""",
            "pr", """
                    SELECT r.id FROM pull_requests r JOIN people p ON p.id = r.author_id JOIN users u ON u.id = p.user_id
                    WHERE u.email = $1 ORDER BY r.opened_at, r.id LIMIT 1""",
            "prHidden", """
                    SELECT r.id FROM pull_requests r JOIN people p ON p.id = r.author_id JOIN users u ON u.id = p.user_id
                    WHERE u.email = $1 AND NOT EXISTS (
                        SELECT 1 FROM pr_reviews v JOIN people x ON x.id = v.reviewer_id JOIN users xu ON xu.id = x.user_id
                        WHERE v.pull_request_id = r.id AND xu.email = 'arjun@pulse.demo')
                    ORDER BY r.opened_at, r.id LIMIT 1""");

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        // integrations: Nudge needs a connected chat tool
        List<String> modules = new ArrayList<>(MODULES.stream().map(PulseModule::id).toList());

        modules.add("integrations");

        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), modules.toArray(String[]::new));
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
            tests.add(DynamicTest.dynamicTest(c.module() + ": " + c.method() + " " + c.path(), () ->
                    RoleMatrix.assertRoleMatrix(api, c.method() + " " + resolve(c.path()), c.body(), Map.of(
                            RoleMatrix.ADMIN, c.statuses().get(0),
                            RoleMatrix.MANAGER, c.statuses().get(1),
                            RoleMatrix.EMPLOYEE, c.statuses().get(2),
                            RoleMatrix.PEER, c.statuses().get(3)))));
        }

        return tests;
    }

    @Test
    void everyTrack2EndpointHasAContractLine() throws Exception
    {
        List<Contract> contracts = contracts();

        ModuleRegistry registry = ModuleRegistry.discover();

        PolicyService policy = new PolicyService(api.sql(), PermissionCatalogue.of(registry).keys(), registry.visibilityRelations(), false);

        // mounting routes runs no query: a pool that never connects is enough
        PgPool idle = PgPool.pool(api.vertx(), new PgConnectOptions(), new PoolOptions());

        ModuleContext ctx = new ModuleContext(api.vertx(), api.config(), idle, policy, null, registry);

        List<String> missing = new ArrayList<>();

        int endpoints = 0;

        for (PulseModule module : MODULES)
        {
            Router router = Router.router(api.vertx());

            module.routes(router, ctx);

            Map<String, Pattern> routes = new LinkedHashMap<>();

            for (Route route : router.getRoutes())
            {
                if (route.getPath() == null || route.methods() == null)
                {
                    continue;
                }

                String full = "/api/v1/" + module.id() + route.getPath();

                Pattern pattern = Pattern.compile("^" + full.replaceAll(":[A-Za-z]+", "[^/]+") + "$");

                for (HttpMethod method : route.methods())
                {
                    routes.put(method.name() + " " + full, pattern);
                }
            }

            for (Map.Entry<String, Pattern> route : routes.entrySet())
            {
                endpoints++;

                String method = route.getKey().substring(0, route.getKey().indexOf(' '));

                boolean covered = contracts.stream().anyMatch(c -> c.module().equals(module.id()) && c.method().equals(method)
                        && route.getValue().matcher(c.path().replaceAll("\\?.*$", "")).matches());

                if (!covered)
                {
                    missing.add(route.getKey());
                }
            }
        }

        idle.close();

        assertTrue(endpoints >= 40, "found the Track 2 routes: " + endpoints);
        assertEquals(List.of(), missing, "Track 2 endpoints without a line in backend/modules/<id>/contract.test");
    }

    // ------------------------------------------------------------------ helpers

    private record Contract(String module, String method, String path, JsonObject body, List<Integer> statuses)
    {
    }

    private static List<Contract> contracts() throws Exception
    {
        List<Contract> out = new ArrayList<>();

        for (PulseModule module : MODULES)
        {
            Path file = Path.of("modules", module.id(), "contract.test");

            if (!Files.exists(file))
            {
                fail("missing " + file);
            }

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
                    fail(file + ":" + number + ": expected 'METHOD /path [json] => ADMIN MANAGER EMPLOYEE PEER', got: " + text);
                }

                if (!m.group(2).startsWith("/api/v1/" + module.id() + "/"))
                {
                    fail(file + ":" + number + ": the path must start with /api/v1/" + module.id() + "/");
                }

                out.add(new Contract(module.id(), m.group(1), m.group(2), m.group(3) == null ? null : new JsonObject(m.group(3)),
                        List.of(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)),
                                Integer.parseInt(m.group(7)))));
            }
        }

        return out;
    }

    private static String resolve(String path) throws Exception
    {
        Matcher m = PLACEHOLDER.matcher(path);

        StringBuilder out = new StringBuilder();

        while (m.find())
        {
            JsonObject row = api.queryOne(LOOKUPS.get(m.group(1)).replace("$1", "'" + m.group(2).replace("'", "''") + "'"));

            if (row == null)
            {
                fail("no " + m.group(1) + " '" + m.group(2) + "' in the demo seed");
            }

            m.appendReplacement(out, Matcher.quoteReplacement(row.getString("id")));
        }

        m.appendTail(out);

        return out.toString();
    }
}
