package com.pulse.modules.culture;

import com.pulse.platform.http.BodySchema;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Culture values under {@code /api/v1/culture} (task 19.1), following docs/backend-guide.md. Every
 * signed-in user may read the people their role sees (their own page at least); the override needs
 * {@code reviews.calibrate} and is refused on your own score (422, audited).
 *
 * <pre>
 * GET    /periods                                 the quarter selector {items, default}
 * GET    /rules                                   weights, data share, definitions and parameters of the 11 values
 * GET    /people/:personId?period=                 the 11 values, pillar, team average, Discipline, evidence log
 * GET    /people/:personId/values/:value?period=   one value's calculation, anchors and evidence
 * GET    /people/:personId/discipline              rules a–h, metrics, automatic score and override
 * PUT    /people/:personId/discipline-override     {score, reason} (reviews.calibrate)
 * DELETE /people/:personId/discipline-override     back to the automatic score (reviews.calibrate)
 * </pre>
 */
final class CultureRoutes
{
    static final BodySchema OVERRIDE = BodySchema.object()
            .number("score", 1, 5)
            .string("reason", 1000)
            .required("score", "reason");

    private final PolicyService policy;

    private final CultureService culture;

    CultureRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();

        this.culture = new CultureService(ctx);
    }

    void mount(Router router)
    {
        router.get("/periods").handler(rc -> rc.json(culture.periods()));

        router.get("/rules").handler(rc -> culture.rules().onSuccess(rc::json).onFailure(rc::fail));

        router.get("/people/:personId").handler(rc -> withPerson(rc, person -> culture.person(PolicyService.user(rc), person, period(rc))
                .onSuccess(rc::json).onFailure(rc::fail)));

        router.get("/people/:personId/values/:value").handler(rc -> withPerson(rc, person -> culture.value(PolicyService.user(rc), person,
                rc.pathParam("value"), period(rc)).onSuccess(rc::json).onFailure(rc::fail)));

        router.get("/people/:personId/discipline").handler(rc -> withPerson(rc, person -> culture.discipline(PolicyService.user(rc), person)
                .onSuccess(rc::json).onFailure(rc::fail)));

        router.put("/people/:personId/discipline-override").handler(policy.requires("reviews.calibrate")).handler(OVERRIDE.handler())
                .handler(rc -> withPerson(rc, person ->
                {
                    JsonObject body = BodySchema.body(rc);

                    culture.setOverride(PolicyService.user(rc), person, body.getDouble("score"), body.getString("reason"), policy.request(rc))
                            .onSuccess(rc::json).onFailure(rc::fail);
                }));

        router.delete("/people/:personId/discipline-override").handler(policy.requires("reviews.calibrate"))
                .handler(rc -> withPerson(rc, person -> culture.clearOverride(PolicyService.user(rc), person, policy.request(rc))
                        .onSuccess(rc::json).onFailure(rc::fail)));
    }

    /** The {@code period} query parameter, this quarter when absent. */
    private String period(RoutingContext rc)
    {
        String p = rc.queryParams().get("period");

        return p == null || p.isBlank() ? CultureService.quarterOf(culture.today()) : p;
    }

    /** Runs {@code then} with a well-formed person id, else 404. */
    private static void withPerson(RoutingContext rc, Consumer<String> then)
    {
        String person = rc.pathParam("personId");

        try
        {
            UUID.fromString(person);
        }
        catch (RuntimeException e)
        {
            rc.fail(PolicyService.notFound());

            return;
        }

        then.accept(person);
    }
}
