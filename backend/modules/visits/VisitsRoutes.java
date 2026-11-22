package com.pulse.modules.visits;

import com.pulse.platform.http.ApiException;
import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.ExpectedVersion;
import com.pulse.platform.http.Ids;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.time.DateTimeException;
import java.time.LocalDate;

/**
 * Customer visits under {@code /api/v1/visits} (task 18.1), following docs/backend-guide.md.
 *
 * <pre>
 * GET    /items?personId=&owned=true&from=&to=   list (default window: the current year)
 * GET    /stats?personId=&period=q:Q3|all       a person's visit stats (calc/people)
 * GET    /items/:id                              detail with status, actions, points and values
 * POST   /items                                  plan (manager) or log (engineer)
 * PUT    /items/:id                              edit before the report
 * DELETE /items/:id                              delete before the report (If-Match: version)
 * POST   /items/:id/report                       the engineer's visit report
 * POST   /items/:id/feedback                     the account owner's customer feedback
 * POST   /items/:id/remind                       the manager reminds the engineer of an overdue report
 * POST   /items/:id/verify | /items/:id/reject   effort.confirm, after report and feedback
 * </pre>
 */
public final class VisitsRoutes
{
    static final String[] PURPOSES = {"Deployment / installation", "Troubleshooting", "Escalation", "Training / workshop",
            "Demo / POC", "Health check"};

    static final BodySchema DETAILS = BodySchema.object()
            .uuid("personId")
            .string("customer", 120)
            .string("city", 80)
            .oneOf("purpose", PURPOSES)
            .date("from")
            .date("to")
            .string("requestedBy", 120)
            .uuid("ownerId")
            .string("reference", 60)
            .string("notes", 1000)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("personId", "customer", "city", "purpose", "from", "to", "requestedBy", "ownerId");

    static final BodySchema EDIT = BodySchema.object()
            .string("customer", 120)
            .string("city", 80)
            .oneOf("purpose", PURPOSES)
            .date("from")
            .date("to")
            .string("requestedBy", 120)
            .uuid("ownerId")
            .string("reference", 60)
            .string("notes", 1000)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("customer", "city", "purpose", "from", "to", "requestedBy", "ownerId");

    static final BodySchema REPORT = BodySchema.object()
            .string("done", 2000)
            .number("onsiteHours", 0, 999)
            .number("travelHours", 0, 999)
            .oneOf("outcome", "Resolved", "Partially resolved", "Follow-up needed", "Deal progressed")
            .string("followUps", 1000)
            .string("contact", 200)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("done", "onsiteHours", "travelHours", "outcome");

    static final BodySchema FEEDBACK = BodySchema.object()
            .number("csat", 1, 5)
            .bool("askAgain")
            .oneOf("source", "Customer call", "Email", "CSAT survey", "Meeting")
            .date("feedbackOn")
            .string("quote", 1000)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("csat", "askAgain", "source", "feedbackOn");

    static final BodySchema DECISION = BodySchema.object()
            .string("note", 500)
            .integer("version", 1, Integer.MAX_VALUE);

    private final PolicyService policy;

    private final VisitService visits;

    VisitsRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();

        this.visits = new VisitService(ctx);
    }

    void mount(Router router)
    {
        // Everyone may see their own visits; managers their team's; account owners the visits they own.
        String[] anyone = {"self.view", "team.view", "all.view"};

        router.get("/items").handler(policy.requiresAny(anyone)).handler(this::list);

        router.post("/items").handler(policy.requiresAny(anyone)).handler(DETAILS.handler()).handler(this::create);

        router.get("/stats").handler(policy.requiresAny(anyone)).handler(this::stats);

        router.get("/items/:id").handler(policy.requiresAny(anyone)).handler(this::get);

        router.put("/items/:id").handler(policy.requiresAny(anyone)).handler(EDIT.handler()).handler(this::update);

        router.delete("/items/:id").handler(policy.requiresAny(anyone)).handler(this::delete);

        router.post("/items/:id/report").handler(policy.requires("self.view")).handler(REPORT.handler()).handler(this::report);

        router.post("/items/:id/feedback").handler(policy.requiresAny(anyone)).handler(FEEDBACK.handler()).handler(this::feedback);

        router.post("/items/:id/remind").handler(policy.requiresAny("team.view", "all.view"))
                .handler(rc -> visits.remind(PolicyService.user(rc), Ids.path(rc, "id")).onSuccess(rc::json).onFailure(rc::fail));

        router.post("/items/:id/verify").handler(policy.requires("effort.confirm")).handler(DECISION.handler())
                .handler(rc -> decide(rc, "confirmed"));

        router.post("/items/:id/reject").handler(policy.requires("effort.confirm")).handler(DECISION.handler())
                .handler(rc -> decide(rc, "rejected"));
    }

    private void list(RoutingContext rc)
    {
        LocalDate from;

        LocalDate to;

        try
        {
            from = date(rc, "from", LocalDate.of(visits.today().getYear(), 1, 1));

            to = date(rc, "to", LocalDate.of(visits.today().getYear(), 12, 31));
        }
        catch (ApiException e)
        {
            rc.fail(e);

            return;
        }

        String personId = rc.queryParams().get("personId");

        boolean owned = "true".equals(rc.queryParams().get("owned"));

        visits.list(PolicyService.user(rc), personId, owned, from, to)
                .onSuccess(items -> rc.json(new JsonObject().put("items", items)))
                .onFailure(rc::fail);
    }

    /** GET /stats?personId=&period=q:Q3 (default) or all: the person's visit stats from calc/people. */
    private void stats(RoutingContext rc)
    {
        String personId = rc.queryParams().get("personId");

        if (personId == null || personId.isBlank())
        {
            rc.fail(ApiException.badRequest("invalid_parameter", "Choose the person.").field("personId"));

            return;
        }

        String period = rc.queryParams().get("period") == null ? "q:Q" + ((visits.today().getMonthValue() - 1) / 3 + 1)
                : rc.queryParams().get("period");

        visits.stats(PolicyService.user(rc), personId, period).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void get(RoutingContext rc)
    {
        visits.get(PolicyService.user(rc), Ids.path(rc, "id")).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void create(RoutingContext rc)
    {
        visits.create(PolicyService.user(rc), BodySchema.body(rc))
                .onSuccess(row ->
                {
                    rc.response().setStatusCode(201);

                    rc.json(row);
                })
                .onFailure(rc::fail);
    }

    private void update(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        visits.update(PolicyService.user(rc), Ids.path(rc, "id"), body, ExpectedVersion.of(rc, body))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void delete(RoutingContext rc)
    {
        visits.delete(PolicyService.user(rc), Ids.path(rc, "id"), ExpectedVersion.of(rc, null))
                .onSuccess(v -> rc.response().setStatusCode(204).end())
                .onFailure(rc::fail);
    }

    private void report(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        if (body.getString("done").isBlank())
        {
            rc.fail(ApiException.badRequest("invalid_field", "Say what was done on site.").field("done"));

            return;
        }

        visits.report(PolicyService.user(rc), Ids.path(rc, "id"), body, ExpectedVersion.of(rc, body))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void feedback(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        visits.feedback(PolicyService.user(rc), Ids.path(rc, "id"), body, ExpectedVersion.of(rc, body))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void decide(RoutingContext rc, String decision)
    {
        JsonObject body = BodySchema.body(rc);

        visits.decide(PolicyService.user(rc), Ids.path(rc, "id"), decision, body.getString("note"),
                        ExpectedVersion.of(rc, body), policy.request(rc))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private static LocalDate date(RoutingContext rc, String name, LocalDate fallback)
    {
        String value = rc.queryParams().get(name);

        if (value == null || value.isBlank())
        {
            return fallback;
        }

        try
        {
            return LocalDate.parse(value);
        }
        catch (DateTimeException e)
        {
            throw ApiException.badRequest("invalid_parameter", "Use YYYY-MM-DD for " + name + ".").field(name);
        }
    }
}
