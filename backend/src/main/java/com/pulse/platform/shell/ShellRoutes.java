package com.pulse.platform.shell;

import com.pulse.calc.core.CalcService;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.List;
import java.util.UUID;

/**
 * The reads the app shell needs on every screen (task 6.4, spec platform/app-shell-ui):
 * <ul>
 *   <li>{@code GET /auth/options} (public): the demo-account cards of the sign-in screen, only while
 *       {@code PULSE_DEMO_SEED} is on (spec "Sign-in screen")</li>
 *   <li>{@code GET /periods}: the choices of the shared period control, from {@code CalcService}</li>
 *   <li>{@code GET /sync-status}: the Jira sync line of the top bar, for every signed-in user</li>
 *   <li>{@code GET /people}: the people of the top-bar person selector, from the policy scope</li>
 * </ul>
 */
public final class ShellRoutes
{
    /** The tool whose sync the top bar shows (reference section 3, "Jira sync status"). */
    static final String SYNC_TOOL = "jira";

    static final String DEMO_PASSWORD = "demo123";

    /** One sign-in card per built-in role (reference section 2, "Screen detail — Sign in"). */
    static final List<String> DEMO_CARDS = List.of("hr@pulse.demo", "rahul@pulse.demo", "priya@pulse.demo");

    /** The other demo employees, named on one line under the cards. */
    static final List<String> DEMO_OTHERS = List.of("arjun@pulse.demo", "neha@pulse.demo", "kiran@pulse.demo",
            "dev@pulse.demo", "sara@pulse.demo", "rohit@pulse.demo", "anjali@pulse.demo", "sana@pulse.demo",
            "meera@pulse.demo");

    private ShellRoutes()
    {
    }

    /** Mounts the public route; call before the session guard. */
    public static void mountPublic(Router router, String apiPrefix, ShellRepository repo, boolean demoSeed)
    {
        router.get(apiPrefix + "/auth/options").handler(ctx -> authOptions(ctx, repo, demoSeed));
    }

    /** Mounts the session routes; call after the session guard. */
    public static void mount(Router router, String apiPrefix, ShellRepository repo, PolicyService policy,
                             CalcService calc)
    {
        router.get(apiPrefix + "/periods").handler(ctx ->
                calc.getObject("core.periods", new JsonObject())
                        .onSuccess(ctx::json)
                        .onFailure(ctx::fail));

        router.get(apiPrefix + "/sync-status").handler(ctx -> syncStatus(ctx, repo));

        router.get(apiPrefix + "/people").handler(ctx -> people(ctx, repo, policy));
    }

    private static void authOptions(RoutingContext ctx, ShellRepository repo, boolean demoSeed)
    {
        if (!demoSeed)
        {
            ctx.json(new JsonObject().put("demo", (Object) null));

            return;
        }

        Future.all(repo.accounts(DEMO_CARDS), repo.accounts(DEMO_OTHERS))
                .onSuccess(all ->
                {
                    JsonArray others = new JsonArray();

                    ((JsonArray) all.resultAt(1)).forEach(row -> others.add(((JsonObject) row).getString("name")));

                    ctx.json(new JsonObject().put("demo", new JsonObject()
                            .put("password", DEMO_PASSWORD)
                            .put("accounts", all.resultAt(0))
                            .put("others", others)));
                })
                .onFailure(ctx::fail);
    }

    /**
     * {@code {tool, state, lastSyncedAt}}: state {@code synced}, {@code syncing}, {@code failed} or
     * {@code never}; {@code lastSyncedAt} is the end of the newest successful run, or null.
     */
    private static void syncStatus(RoutingContext ctx, ShellRepository repo)
    {
        Future.all(repo.latestSync(SYNC_TOOL), repo.latestSuccess(SYNC_TOOL))
                .onSuccess(all ->
                {
                    JsonObject latest = all.resultAt(0);
                    JsonObject success = all.resultAt(1);

                    ctx.json(new JsonObject()
                            .put("tool", SYNC_TOOL)
                            .put("state", state(latest))
                            .put("lastSyncedAt", success == null ? null : success.getValue("finished_at")));
                })
                .onFailure(ctx::fail);
    }

    static String state(JsonObject latest)
    {
        if (latest == null)
        {
            return "never";
        }

        return switch (latest.getString("outcome"))
        {
            case "running" -> "syncing";
            case "success" -> "synced";
            default -> "failed";
        };
    }

    /** {@code [{id, name, title, department}]}: the people the user may open on person pages. */
    private static void people(RoutingContext ctx, ShellRepository repo, PolicyService policy)
    {
        JsonObject user = PolicyService.user(ctx);

        boolean withDeactivated = PolicyService.permissions(user).contains("users.all");

        policy.scope(user, PolicyService.DOMAIN_PERSON)
                .compose(scope ->
                {
                    if (scope.all())
                    {
                        return repo.allPeople(withDeactivated);
                    }

                    List<UUID> ids = scope.personIds().stream().map(UUID::fromString).toList();

                    return ids.isEmpty() ? Future.succeededFuture(new JsonArray()) : repo.people(ids, withDeactivated);
                })
                .onSuccess(ctx::json)
                .onFailure(ctx::fail);
    }
}
