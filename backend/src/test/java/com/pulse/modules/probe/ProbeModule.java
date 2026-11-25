package com.pulse.modules.probe;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.ListQuery;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.ExpectedVersion;
import com.pulse.platform.http.Ids;
import com.pulse.platform.live.StubLiveConnector;
import com.pulse.platform.module.BadgeProvider;
import com.pulse.platform.module.JobSpec;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import com.pulse.platform.policy.PolicyService;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import com.pulse.platform.policy.VisibilityRelation;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.sqlclient.Tuple;

import java.util.List;
import java.util.UUID;

/**
 * Test-only module (tasks 3.5 and 3.6) that uses the policy service and the security baseline the
 * way a real module does, so the platform can be exercised end to end before the domain modules
 * exist. It declares a mentor relation for the training domain over the test table
 * {@code probe_mentors} (created by the tests that need it).
 */
public class ProbeModule implements PulseModule
{
    static final BodySchema ECHO = BodySchema.object()
            .string("title", 10)
            .integer("count", 0, 100)
            .stringList("tags", 3, 5)
            .required("title");

    static final BodySchema NOTE = BodySchema.object()
            .string("text", 100)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("text");

    @Override
    public String id()
    {
        return "probe";
    }

    /** Registers the stub live connector from a module descriptor, as the connectors module does (task 24.3). */
    @Override
    public void start(ModuleContext ctx)
    {
        ctx.connectors().register(new StubLiveConnector());
    }

    /**
     * Badges for the notifications tests (task 5.3): two Work items providers that add up (3 + 2 for a
     * user with a profile), and an Effort provider that fails, whose badge must be left out.
     */
    @Override
    public List<BadgeProvider> badges()
    {
        return List.of(badge("work-items", 3), badge("work-items", 2), badge("effort", -1));
    }

    private static BadgeProvider badge(String screen, int count)
    {
        return new BadgeProvider()
        {
            @Override
            public String screen()
            {
                return screen;
            }

            @Override
            public Future<Integer> countFor(JsonObject user, ModuleContext ctx)
            {
                return count < 0 ? Future.failedFuture(new IllegalStateException("probe badge failure"))
                        : Future.succeededFuture(count);
            }
        };
    }

    /** A job that always fails, for the scheduler's retry and failure tests (task 5.5); never due in practice. */
    static final String FAILING_JOB = "probe.always-fails";

    @Override
    public List<JobSpec> jobs()
    {
        return List.of(JobSpec.of(FAILING_JOB, "0 0 29 2 *", "Fails every attempt (tests only)",
                ctx -> Future.failedFuture(new IllegalStateException("probe failure password=hunter2"))));
    }

    @Override
    public List<VisibilityRelation> visibilityRelations()
    {
        return List.of(new VisibilityRelation(PolicyService.DOMAIN_TRAINING, "mentor",
                "SELECT mentee_id AS person_id FROM probe_mentors WHERE mentor_id = $1"));
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        PolicyService policy = ctx.policy();

        router.get("/people").handler(policy.requiresAny("self.view", "team.view", "all.view")).handler(rc ->
                policy.scope(PolicyService.user(rc), PolicyService.DOMAIN_PERSON)
                        .compose(scope -> new ProbePeople(ctx.sql()).list(scope, rc.queryParams()))
                        .onSuccess(rc::json)
                        .onFailure(rc::fail));

        personRoute(router, policy, ctx.sql(), "overview", PolicyService.DOMAIN_PERSON);
        personRoute(router, policy, ctx.sql(), "training", PolicyService.DOMAIN_TRAINING);
        personRoute(router, policy, ctx.sql(), "okrs", PolicyService.DOMAIN_OKRS);
        personRoute(router, policy, ctx.sql(), "checkin", PolicyService.DOMAIN_CHECKIN);

        router.get("/team").handler(policy.requires("team.view")).handler(rc -> rc.json(new JsonObject().put("ok", true)));

        router.post("/increments/:personId/approve").handler(policy.requires("appraisal.approve")).handler(rc ->
        {
            JsonObject user = PolicyService.user(rc);
            String personId = rc.pathParam("personId");

            policy.requireVisible(user, personId, PolicyService.DOMAIN_SALARY)
                    .compose(scope -> policy.refuseSelf(user, "appraisal.increment.approve", personId, null,
                            policy.request(rc)))
                    .onSuccess(v -> rc.json(new JsonObject().put("approved", personId)))
                    .onFailure(rc::fail);
        });

        // versioned records over the test table probe_notes (task 3.8)
        router.get("/notes/:id").handler(rc ->
                new ProbeNotes(ctx.sql()).get(Ids.path(rc, "id"))
                        .compose(row -> row == null
                                ? Future.failedFuture(com.pulse.platform.http.ApiException.notFound("not_found", "Not found"))
                                : Future.succeededFuture(row))
                        .onSuccess(rc::json)
                        .onFailure(rc::fail));

        router.put("/notes/:id").handler(NOTE.handler()).handler(rc ->
        {
            JsonObject body = BodySchema.body(rc);

            new ProbeNotes(ctx.sql()).update(Ids.path(rc, "id"), body.getString("text"), ExpectedVersion.of(rc, body))
                    .onSuccess(rc::json)
                    .onFailure(rc::fail);
        });

        router.post("/echo").handler(ECHO.handler()).handler(rc -> rc.json(BodySchema.body(rc)));

        router.get("/boom").handler(rc ->
        {
            throw new IllegalStateException("db said: password=hunter2 ctc: 42.5");
        });
    }

    private static void personRoute(Router router, PolicyService policy, SqlExecutor sql, String name, String domain)
    {
        router.get("/people/:personId/" + name).handler(policy.requiresAny("self.view", "team.view", "all.view"))
                .handler(rc -> handlePerson(rc, policy, sql, domain));
    }

    private static void handlePerson(RoutingContext rc, PolicyService policy, SqlExecutor sql, String domain)
    {
        String personId = rc.pathParam("personId");

        policy.requireVisible(PolicyService.user(rc), personId, domain)
                .compose(scope -> new ProbePeople(sql).one(scope, personId))
                .onSuccess(row -> rc.json(new JsonObject().put("domain", domain).put("person", row)))
                .onFailure(rc::fail);
    }

    /** A plain versioned table. */
    static final class ProbeNotes extends BaseRepository
    {
        ProbeNotes(SqlExecutor sql)
        {
            super(sql);
        }

        Future<JsonObject> get(UUID id)
        {
            return queryOne("SELECT id, text, version FROM probe_notes WHERE id = $1", Tuple.of(id));
        }

        Future<JsonObject> update(UUID id, String text, int expectedVersion)
        {
            return updateVersioned("probe_notes", "text = $1", Tuple.of(text), id, expectedVersion);
        }
    }

    /** A personal-data repository: every read takes the policy scope. */
    static final class ProbePeople extends ScopedRepository
    {
        ProbePeople(SqlExecutor sql)
        {
            super(sql);
        }

        Future<JsonObject> one(Scope scope, String personId)
        {
            return scopedOne(scope, "p.id", "SELECT p.id, p.name FROM people p WHERE p.id = $1 AND {scope}",
                    Tuple.of(UUID.fromString(personId)));
        }

        Future<JsonObject> list(Scope scope, MultiMap params)
        {
            return scopedPage(scope, "p.id", ListQuery.from("SELECT p.id, p.name FROM people p")
                    .sort("name", "p.name").defaultSort("name").tieBreaker("p.id"), params);
        }
    }
}
