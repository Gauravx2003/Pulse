package com.pulse.modules.appraisal;

import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.ExpectedVersion;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Appraisal & increments under {@code /api/v1/appraisal} (task 22.1), following docs/backend-guide.md.
 * Employees get 403 on every route (audited by the policy service).
 *
 * <pre>
 * GET  /proposals                       the table: cycle, rules, KPIs, people (appraisal.manage or .approve; audited)
 * PUT  /proposals/:personId             save a Draft {pct, reason, promotion} (appraisal.manage)
 * POST /proposals/apply                 apply recommendations to Draft and Sent back rows (appraisal.manage)
 * POST /proposals/submit                submit Drafts to HR; 422 unexplained exceptions, 409 over budget (appraisal.manage)
 * POST /proposals/:personId/approve     approve one (appraisal.approve)
 * POST /proposals/:personId/send-back   back to Draft with {reason} (appraisal.approve)
 * POST /proposals/approve-all           approve every Submitted proposal (appraisal.approve)
 * POST /release                         release the letters of every Approved proposal (appraisal.approve)
 * GET  /cycle                           the current cycle {year, effectiveOn, version}
 * PUT  /cycle                           {effectiveOn, version} (appraisal.approve)
 * </pre>
 */
public final class AppraisalRoutes
{
    static final BodySchema DRAFT = BodySchema.object()
            .number("pct", 0, 50)
            .string("reason", 1000)
            .bool("promotion")
            .required("pct");

    static final BodySchema SEND_BACK = BodySchema.object()
            .string("reason", 1000)
            .required("reason");

    static final BodySchema CYCLE = BodySchema.object()
            .date("effectiveOn")
            .integer("version", 1, Integer.MAX_VALUE)
            .required("effectiveOn", "version");

    private static final String[] ROLES = {"appraisal.manage", "appraisal.approve"};

    private final PolicyService policy;

    private final AppraisalService appraisal;

    AppraisalRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();

        this.appraisal = new AppraisalService(ctx);
    }

    void mount(Router router)
    {
        router.get("/proposals").handler(policy.requiresAny(ROLES))
                .handler(rc -> appraisal.table(PolicyService.user(rc), policy.request(rc)).onSuccess(rc::json).onFailure(rc::fail));

        router.post("/proposals/apply").handler(policy.requires("appraisal.manage"))
                .handler(rc -> appraisal.apply(PolicyService.user(rc)).onSuccess(rc::json).onFailure(rc::fail));

        router.post("/proposals/submit").handler(policy.requires("appraisal.manage"))
                .handler(rc -> appraisal.submit(PolicyService.user(rc)).onSuccess(rc::json).onFailure(rc::fail));

        router.post("/proposals/approve-all").handler(policy.requires("appraisal.approve"))
                .handler(rc -> appraisal.approveAll(PolicyService.user(rc)).onSuccess(rc::json).onFailure(rc::fail));

        router.put("/proposals/:personId").handler(policy.requires("appraisal.manage")).handler(DRAFT.handler())
                .handler(rc -> withPerson(rc, person -> appraisal.save(PolicyService.user(rc), person, BodySchema.body(rc), policy.request(rc))
                        .onSuccess(rc::json).onFailure(rc::fail)));

        router.post("/proposals/:personId/approve").handler(policy.requires("appraisal.approve"))
                .handler(rc -> withPerson(rc, person -> appraisal.approve(PolicyService.user(rc), person, policy.request(rc))
                        .onSuccess(rc::json).onFailure(rc::fail)));

        router.post("/proposals/:personId/send-back").handler(policy.requires("appraisal.approve")).handler(SEND_BACK.handler())
                .handler(rc -> withPerson(rc, person -> appraisal.sendBack(PolicyService.user(rc), person, BodySchema.body(rc).getString("reason"),
                        policy.request(rc)).onSuccess(rc::json).onFailure(rc::fail)));

        router.post("/release").handler(policy.requires("appraisal.approve"))
                .handler(rc -> appraisal.release(PolicyService.user(rc)).onSuccess(rc::json).onFailure(rc::fail));

        router.get("/cycle").handler(policy.requiresAny(ROLES))
                .handler(rc -> appraisal.cycle().onSuccess(rc::json).onFailure(rc::fail));

        router.put("/cycle").handler(policy.requires("appraisal.approve")).handler(CYCLE.handler()).handler(rc ->
        {
            JsonObject body = BodySchema.body(rc);

            appraisal.setEffectiveOn(PolicyService.user(rc), LocalDate.parse(body.getString("effectiveOn")), ExpectedVersion.of(rc, body))
                    .onSuccess(rc::json).onFailure(rc::fail);
        });
    }

    /** Runs {@code then} with a well-formed person id, else 404. */
    private static void withPerson(RoutingContext rc, java.util.function.Consumer<String> then)
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
