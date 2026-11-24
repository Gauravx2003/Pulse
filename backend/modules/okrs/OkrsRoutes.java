package com.pulse.modules.okrs;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.work.OkrFormulas;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.Ids;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * OKR endpoints under {@code /api/v1/okrs} (task 14.2, spec growth/okrs), written to
 * {@code docs/backend-guide.md}: permission first, then the body schema, visibility from the policy
 * service ({@code okrs} domain), failures through {@code rc.fail}. Progress, status, KPIs and the OKR
 * pillar come from {@code calc/work} through CalcService; every write drops the cached OKR results.
 *
 * <p>JSON fields are camelCase. Quarters are keys such as {@code 2026-Q3} ({@code Q3 2026} is accepted).
 */
public final class OkrsRoutes
{

    private static final double LIMIT = 1_000_000_000_000d;

    static final BodySchema KEY_RESULT = BodySchema.object()
            .uuid("id")
            .string("description", 300)
            .number("startValue", -LIMIT, LIMIT)
            .number("targetValue", -LIMIT, LIMIT)
            .number("currentValue", -LIMIT, LIMIT)
            .string("unit", 20);

    static final BodySchema CREATE = BodySchema.object()
            .string("title", 300)
            .string("quarter", 10)
            .oneOf("level", "Individual", "Team")
            .string("team", 100)
            .uuid("ownerId")
            .string("epicKey", 50)
            .string("cultureValue", 60)
            .objectList("keyResults", 10, KEY_RESULT);

    static final BodySchema EDIT = BodySchema.object()
            .string("title", 300)
            .string("quarter", 10)
            .string("epicKey", 50)
            .string("cultureValue", 60)
            .objectList("keyResults", 10, KEY_RESULT)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("version");

    static final BodySchema CHECK_IN = BodySchema.object()
            .number("value", -LIMIT, LIMIT)
            .string("note", 500);

    private final ModuleContext ctx;

    private final PolicyService policy;

    private final OkrService okrs;

    OkrsRoutes(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();

        this.okrs = new OkrService(ctx.sql(), (actor, personId) -> policy.scope(actor, PolicyService.DOMAIN_OKRS)
                .map(scope -> scope.allows(personId)));
    }

    void mount(Router router)
    {
        // GET /api/v1/okrs/people/:personId/objectives?quarter=2026-Q3
        router.get("/people/:personId/objectives").handler(policy.requiresAny(OkrService.OKR_OWN, OkrService.OKR_TEAM))
                .handler(this::forPerson);

        // GET /api/v1/okrs/teams/:team/objectives?quarter=2026-Q3
        router.get("/teams/:team/objectives").handler(policy.requiresAny(OkrService.OKR_OWN, OkrService.OKR_TEAM))
                .handler(this::forTeam);

        // GET /api/v1/okrs/objectives/:id
        router.get("/objectives/:id").handler(policy.requiresAny(OkrService.OKR_OWN, OkrService.OKR_TEAM))
                .handler(this::one);

        // POST /api/v1/okrs/objectives
        router.post("/objectives").handler(policy.requiresAny(OkrService.OKR_OWN, OkrService.OKR_TEAM))
                .handler(CREATE.handler()).handler(this::create);

        // PUT /api/v1/okrs/objectives/:id
        router.put("/objectives/:id").handler(policy.requiresAny(OkrService.OKR_OWN, OkrService.OKR_TEAM))
                .handler(EDIT.handler()).handler(this::edit);

        // DELETE /api/v1/okrs/objectives/:id
        router.delete("/objectives/:id").handler(policy.requiresAny(OkrService.OKR_OWN, OkrService.OKR_TEAM))
                .handler(this::delete);

        // POST /api/v1/okrs/key-results/:id/check-ins
        router.post("/key-results/:id/check-ins").handler(policy.requiresAny(OkrService.OKR_OWN, OkrService.OKR_TEAM))
                .handler(CHECK_IN.handler()).handler(this::checkIn);
    }

    // ------------------------------------------------------------------ reads

    private void forPerson(RoutingContext rc)
    {
        JsonObject user = PolicyService.user(rc);

        String personId = rc.pathParam("personId");

        String quarter;

        try
        {
            quarter = quarter(rc);
        }
        catch (ApiException e)
        {
            rc.fail(e);

            return;
        }

        policy.requireVisible(user, personId, PolicyService.DOMAIN_OKRS)
                .compose(scope -> okrs.listForPerson(user, personId, quarter))
                .compose(rows -> ctx.calc().get(OkrFormulas.SUMMARY, new JsonObject()
                                .put("person", personId)
                                .put("period", "q:" + quarter))
                        .map(summary -> listResponse(rows, (JsonObject) summary)))
                .compose(body -> new OkrRepository(ctx.sql()).quartersForOwner(UUID.fromString(personId))
                        .map(quarters -> body.put("quarters", quarters)))
                .onSuccess(rc::json)
                .onFailure(error -> rc.fail(api(error)));
    }

    private void forTeam(RoutingContext rc)
    {
        JsonObject user = PolicyService.user(rc);

        String team = rc.pathParam("team");

        String quarter;

        try
        {
            quarter = quarter(rc);
        }
        catch (ApiException e)
        {
            rc.fail(e);

            return;
        }

        okrs.listForTeam(user, team, quarter)
                .compose(rows -> ctx.calc().get(OkrFormulas.SUMMARY, new JsonObject()
                                .put("team", team)
                                .put("period", "q:" + quarter))
                        .map(summary -> listResponse(rows, (JsonObject) summary)))
                .compose(body -> new OkrRepository(ctx.sql()).quartersForTeam(team).map(quarters -> body.put("quarters", quarters)))
                .onSuccess(rc::json)
                .onFailure(error -> rc.fail(api(error)));
    }

    private void one(RoutingContext rc)
    {
        UUID id = Ids.path(rc, "id");

        okrs.get(PolicyService.user(rc), id.toString())
                .compose(this::withProgress)
                .onSuccess(rc::json)
                .onFailure(error -> rc.fail(api(error)));
    }

    // ------------------------------------------------------------------ writes

    private void create(RoutingContext rc)
    {
        okrs.create(PolicyService.user(rc), snake(BodySchema.body(rc)))
                .compose(this::changed)
                .onSuccess(objective ->
                {
                    rc.response().setStatusCode(201);

                    rc.json(objective);
                })
                .onFailure(error -> rc.fail(api(error)));
    }

    private void edit(RoutingContext rc)
    {
        UUID id = Ids.path(rc, "id");

        okrs.update(PolicyService.user(rc), id.toString(), snake(BodySchema.body(rc)))
                .compose(this::changed)
                .onSuccess(rc::json)
                .onFailure(error -> rc.fail(api(error)));
    }

    private void delete(RoutingContext rc)
    {
        UUID id = Ids.path(rc, "id");

        okrs.delete(PolicyService.user(rc), id.toString())
                .map(result ->
                {
                    invalidate();

                    return camel(result);
                })
                .onSuccess(rc::json)
                .onFailure(error -> rc.fail(api(error)));
    }

    /**
     * Saves the check-in and answers the key result with its objective's new progress (the toast).
     */
    private void checkIn(RoutingContext rc)
    {
        UUID id = Ids.path(rc, "id");

        okrs.checkIn(PolicyService.user(rc), id.toString(), BodySchema.body(rc))
                .compose(kr ->
                {
                    invalidate();

                    return ctx.calc().get(OkrFormulas.OBJECTIVE, new JsonObject().put("objective", kr.getString("objective_id")))
                            .map(progress -> new JsonObject()
                                    .put("keyResult", camel(kr))
                                    .put("objective", progress));
                })
                .onSuccess(rc::json)
                .onFailure(error -> rc.fail(api(error)));
    }

    private Future<JsonObject> changed(JsonObject objective)
    {
        invalidate();

        return withProgress(objective);
    }

    /**
     * Drops the cached OKR results here at once and on every other instance through the event bus.
     */
    private void invalidate()
    {
        ctx.calc().invalidate(OkrFormulas.OKRS);

        CalcService.publish(ctx.vertx(), OkrFormulas.OKRS);
    }

    // ------------------------------------------------------------------ response shape

    private Future<JsonObject> withProgress(JsonObject objective)
    {
        return ctx.calc().get(OkrFormulas.OBJECTIVE, new JsonObject().put("objective", objective.getString("id")))
                .map(detail -> merge(camel(objective), detail instanceof JsonObject d ? d : null));
    }

    private static JsonObject listResponse(JsonArray rows, JsonObject summary)
    {
        Map<String, JsonObject> details = new HashMap<>();

        for (Object item : summary.getJsonArray("items", new JsonArray()))
        {
            JsonObject detail = (JsonObject) item;

            details.put(detail.getString("id"), detail);
        }

        JsonArray items = new JsonArray();

        for (Object row : rows)
        {
            JsonObject objective = (JsonObject) row;

            items.add(merge(camel(objective), details.get(objective.getString("id"))));
        }

        JsonObject kpis = summary.copy();

        kpis.remove("items");

        return new JsonObject().put("items", items).put("summary", kpis);
    }

    /**
     * Adds an objective's {@code progress} and {@code status}, and each key result's
     * {@code progress}, {@code status} and {@code achieved}, from its calc detail.
     */
    private static JsonObject merge(JsonObject objective, JsonObject detail)
    {
        if (detail == null)
        {
            return objective;
        }

        objective.put("progress", detail.getValue("progress")).put("status", detail.getValue("status"));

        Map<String, JsonObject> byId = new HashMap<>();

        for (Object item : detail.getJsonArray("keyResults", new JsonArray()))
        {
            JsonObject kr = (JsonObject) item;

            byId.put(kr.getString("id"), kr);
        }

        for (Object item : objective.getJsonArray("keyResults", new JsonArray()))
        {
            JsonObject kr = (JsonObject) item;

            JsonObject calc = byId.get(kr.getString("id"));

            if (calc != null)
            {
                kr.put("progress", calc.getValue("progress"))
                        .put("status", calc.getValue("status"))
                        .put("achieved", calc.getValue("achieved"));
            }
        }

        return objective;
    }

    // ------------------------------------------------------------------ helpers

    private static String quarter(RoutingContext rc)
    {
        String quarter = OkrImportService.quarterKey(rc.queryParams().get("quarter"));

        if (quarter == null)
        {
            throw ApiException.badRequest("invalid_parameter", "Select a quarter.").field("quarter");
        }

        return quarter;
    }

    /**
     * The error contract for a failure: OKR refusals keep their status and code.
     */
    private static Throwable api(Throwable error)
    {
        if (error instanceof OkrException okr)
        {
            ApiException api = new ApiException(okr.status(), okr.code(), okr.getMessage());

            if (okr.field() != null)
            {
                api.field(camelKey(okr.field()));
            }

            if (okr.currentVersion() != null)
            {
                api.currentVersion(okr.currentVersion());
            }

            return api;
        }

        return error;
    }

    /**
     * camelCase request keys to the service's snake_case, recursively.
     */
    static JsonObject snake(JsonObject json)
    {
        JsonObject out = new JsonObject();

        json.forEach(e -> out.put(e.getKey().replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(), snakeValue(e.getValue())));

        return out;
    }

    /**
     * snake_case rows to camelCase response keys, recursively.
     */
    static JsonObject camel(JsonObject json)
    {
        JsonObject out = new JsonObject();

        json.forEach(e -> out.put(camelKey(e.getKey()), camelValue(e.getValue())));

        return out;
    }

    private static Object snakeValue(Object value)
    {
        if (value instanceof JsonObject o)
        {
            return snake(o);
        }

        if (value instanceof JsonArray a)
        {
            JsonArray out = new JsonArray();

            a.forEach(item -> out.add(snakeValue(item)));

            return out;
        }

        return value;
    }

    private static Object camelValue(Object value)
    {
        if (value instanceof JsonObject o)
        {
            return camel(o);
        }

        if (value instanceof JsonArray a)
        {
            JsonArray out = new JsonArray();

            a.forEach(item -> out.add(camelValue(item)));

            return out;
        }

        return value;
    }

    private static String camelKey(String key)
    {
        StringBuilder out = new StringBuilder();

        boolean upper = false;

        for (char c : key.toCharArray())
        {
            if (c == '_')
            {
                upper = true;
            }
            else
            {
                out.append(upper ? Character.toUpperCase(c) : c);

                upper = false;
            }
        }

        return out.toString();
    }
}
