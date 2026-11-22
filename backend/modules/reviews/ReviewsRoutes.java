package com.pulse.modules.reviews;

import com.pulse.platform.http.ApiException;
import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.ExpectedVersion;
import com.pulse.platform.http.Ids;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * 1:1s and kudos under {@code /api/v1/reviews} (task 20.2), built from docs/backend-guide.md: every
 * route declares {@code oneonone}, bodies have a {@link BodySchema}, person data goes through the
 * policy service, writes audit in their transaction and edits use optimistic locking.
 */
public final class ReviewsRoutes
{
    /** Free-text limits (spec "Free-text safety"); V330 enforces the same lengths. */
    static final int NOTES_MAX = 4000;

    static final int ACTION_TEXT_MAX = 300;

    static final int KUDOS_MAX = 500;

    static final BodySchema CREATE = BodySchema.object()
            .uuid("personId")
            .date("heldOn")
            .stringList("agenda", 10, 200)
            .required("personId", "heldOn");

    static final BodySchema NOTES = BodySchema.object()
            .string("notes", NOTES_MAX)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("notes");

    static final BodySchema NEW_ACTION = BodySchema.object()
            .string("text", ACTION_TEXT_MAX)
            .uuid("ownerId")
            .date("dueOn")
            .required("text");

    static final BodySchema EDIT_ACTION = BodySchema.object()
            .string("text", ACTION_TEXT_MAX)
            .uuid("ownerId")
            .date("dueOn")
            .bool("done")
            .integer("version", 1, Integer.MAX_VALUE)
            .required("text", "done");

    static final BodySchema KUDOS = BodySchema.object()
            .uuid("personId")
            .oneOf("value", KudosService.CULTURE_VALUES.toArray(new String[0]))
            .string("message", KUDOS_MAX)
            .oneOf("shareTo", "none", "slack", "teams")
            .required("personId", "value", "message");

    static final BodySchema CYCLE = BodySchema.object()
            .string("name", 120)
            .string("quarter", 10)
            .date("dueOn")
            .string("department", 120)
            .integer("internalMin", 0, 20)
            .integer("internalMax", 1, 20)
            .integer("crossMin", 0, 20)
            .integer("crossMax", 1, 20)
            .integer("anonymityThreshold", 2, 10)
            .required("name", "quarter", "dueOn");

    static final BodySchema ASSIGN = BodySchema.object()
            .uuidList("subjectIds", 100)
            .uuidList("reviewerIds", 100)
            .bool("includeSelf")
            .bool("includeManager")
            .required("subjectIds");

    /** Draft or submit: scores 1–5 per rated value (null = Not observed), examples, both comments. */
    static final BodySchema ANSWERS = answersSchema();

    static final BodySchema VERSION_ONLY = BodySchema.object()
            .integer("version", 1, Integer.MAX_VALUE);

    private final PolicyService policy;

    private final Review360Service reviews360;

    private final OneOnOneService oneOnOnes;

    private final KudosService kudos;

    ReviewsRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();

        this.oneOnOnes = new OneOnOneService(ctx);

        this.reviews360 = new Review360Service(ctx);

        this.kudos = new KudosService(ctx);
    }

    void mount(Router router)
    {
        // GET /api/v1/reviews/one-on-ones?personId=...
        router.get("/one-on-ones").handler(policy.requires("oneonone")).handler(this::list);

        // POST /api/v1/reviews/one-on-ones
        router.post("/one-on-ones").handler(policy.requires("oneonone")).handler(CREATE.handler()).handler(this::create);

        // PUT /api/v1/reviews/one-on-ones/:id/notes
        router.put("/one-on-ones/:id/notes").handler(policy.requires("oneonone")).handler(NOTES.handler())
                .handler(this::saveNotes);

        // POST /api/v1/reviews/one-on-ones/:id/actions
        router.post("/one-on-ones/:id/actions").handler(policy.requires("oneonone")).handler(NEW_ACTION.handler())
                .handler(this::addAction);

        // PUT /api/v1/reviews/actions/:id
        router.put("/actions/:id").handler(policy.requires("oneonone")).handler(EDIT_ACTION.handler())
                .handler(this::updateAction);

        // POST /api/v1/reviews/kudos
        router.post("/kudos").handler(policy.requires("oneonone")).handler(KUDOS.handler()).handler(this::giveKudos);

        // GET /api/v1/reviews/kudos?personId=&value=&page=&pageSize=&sort=-given
        router.get("/kudos").handler(policy.requires("oneonone")).handler(this::kudosFeed);

        mount360(router);
    }

    /** 360° feedback (task 20.3) under /api/v1/reviews/360. */
    private void mount360(Router router)
    {
        router.get("/360/cycles").handler(policy.requiresAny("review360.give", "review360.manage"))
                .handler(rc -> reviews360.cycles().onSuccess(items -> rc.json(new JsonObject().put("items", items))).onFailure(rc::fail));

        // Admin: users.all and review360.manage (spec "Cycle").
        router.post("/360/cycles").handler(policy.requires("users.all", "review360.manage")).handler(CYCLE.handler())
                .handler(rc -> reviews360.createCycle(PolicyService.user(rc), BodySchema.body(rc)).onSuccess(row ->
                {
                    rc.response().setStatusCode(201);

                    rc.json(row);
                }).onFailure(rc::fail));

        router.post("/360/cycles/:id/assignments").handler(policy.requires("review360.manage")).handler(ASSIGN.handler())
                .handler(rc -> reviews360.assign(PolicyService.user(rc), Ids.path(rc, "id"), BodySchema.body(rc), policy.request(rc))
                        .onSuccess(rc::json).onFailure(rc::fail));

        router.get("/360/cycles/:id/assignments").handler(policy.requires("review360.manage"))
                .handler(rc -> reviews360.assignments(PolicyService.user(rc), Ids.path(rc, "id"))
                        .onSuccess(items -> rc.json(new JsonObject().put("items", items))).onFailure(rc::fail));

        router.get("/360/cycles/:id/progress").handler(policy.requires("review360.give"))
                .handler(rc -> reviews360.myProgress(PolicyService.user(rc), Ids.path(rc, "id")).onSuccess(rc::json).onFailure(rc::fail));

        router.get("/360/cycles/:id/results").handler(policy.requires("review360.give"))
                .handler(rc -> reviews360.myResults(PolicyService.user(rc), Ids.path(rc, "id")).onSuccess(rc::json).onFailure(rc::fail));

        router.get("/360/cycles/:id/results/:personId").handler(policy.requires("review360.manage"))
                .handler(rc -> reviews360.resultsFor(PolicyService.user(rc), Ids.path(rc, "id"), rc.pathParam("personId"))
                        .onSuccess(rc::json).onFailure(rc::fail));

        // Remind everyone who has not submitted, or one reviewer (review360.manage).
        router.post("/360/cycles/:id/remind").handler(policy.requires("review360.manage"))
                .handler(rc -> reviews360.remind(PolicyService.user(rc), Ids.path(rc, "id"), null).onSuccess(rc::json).onFailure(rc::fail));

        router.post("/360/requests/:id/remind").handler(policy.requires("review360.manage"))
                .handler(rc -> reviews360.remindRequest(PolicyService.user(rc), Ids.path(rc, "id")).onSuccess(rc::json).onFailure(rc::fail));

        router.get("/360/to-give").handler(policy.requires("review360.give"))
                .handler(rc -> reviews360.toGive(PolicyService.user(rc))
                        .onSuccess(items -> rc.json(new JsonObject().put("items", items))).onFailure(rc::fail));

        router.get("/360/requests/:id").handler(policy.requires("review360.give"))
                .handler(rc -> reviews360.request(PolicyService.user(rc), Ids.path(rc, "id")).onSuccess(rc::json).onFailure(rc::fail));

        router.put("/360/requests/:id").handler(policy.requires("review360.give")).handler(ANSWERS.handler())
                .handler(rc -> answer(rc, false));

        router.post("/360/requests/:id/submit").handler(policy.requires("review360.give")).handler(ANSWERS.handler())
                .handler(rc -> answer(rc, true));

        router.post("/360/requests/:id/decline").handler(policy.requires("review360.give")).handler(VERSION_ONLY.handler())
                .handler(rc -> reviews360.decline(PolicyService.user(rc), Ids.path(rc, "id"),
                        ExpectedVersion.of(rc, BodySchema.body(rc))).onSuccess(rc::json).onFailure(rc::fail));

        router.delete("/360/requests/:id").handler(policy.requires("review360.manage"))
                .handler(rc -> reviews360.remove(PolicyService.user(rc), Ids.path(rc, "id"))
                        .onSuccess(v -> rc.response().setStatusCode(204).end()).onFailure(rc::fail));
    }

    private void answer(RoutingContext rc, boolean submit)
    {
        JsonObject body = BodySchema.body(rc);

        reviews360.answer(PolicyService.user(rc), Ids.path(rc, "id"), body, submit, ExpectedVersion.of(rc, body))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private static BodySchema answersSchema()
    {
        BodySchema scores = BodySchema.object();

        BodySchema examples = BodySchema.object();

        for (String value : Review360Service.RATED_VALUES)
        {
            scores.integer(value, 1, 5);

            examples.string(value, 500);
        }

        return BodySchema.object()
                .object("scores", scores)
                .object("examples", examples)
                .string("keepDoing", 2000)
                .string("startDoing", 2000)
                .integer("version", 1, Integer.MAX_VALUE);
    }

    private void list(RoutingContext rc)
    {
        String personId = rc.queryParams().get("personId");

        if (personId == null || personId.isBlank())
        {
            rc.fail(ApiException.badRequest("invalid_parameter", "Choose the person whose 1:1s to show.").field("personId"));

            return;
        }

        oneOnOnes.forPerson(PolicyService.user(rc), personId)
                .onSuccess(items -> rc.json(new JsonObject().put("items", items)))
                .onFailure(rc::fail);
    }

    private void create(RoutingContext rc)
    {
        oneOnOnes.create(PolicyService.user(rc), BodySchema.body(rc))
                .onSuccess(row ->
                {
                    rc.response().setStatusCode(201);

                    rc.json(row);
                })
                .onFailure(rc::fail);
    }

    private void saveNotes(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        oneOnOnes.saveNotes(PolicyService.user(rc), Ids.path(rc, "id"), body.getString("notes"), ExpectedVersion.of(rc, body))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void addAction(RoutingContext rc)
    {
        oneOnOnes.addAction(PolicyService.user(rc), Ids.path(rc, "id"), BodySchema.body(rc))
                .onSuccess(row ->
                {
                    rc.response().setStatusCode(201);

                    rc.json(row);
                })
                .onFailure(rc::fail);
    }

    private void updateAction(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        oneOnOnes.updateAction(PolicyService.user(rc), Ids.path(rc, "id"), body, ExpectedVersion.of(rc, body))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void giveKudos(RoutingContext rc)
    {
        kudos.give(PolicyService.user(rc), BodySchema.body(rc))
                .onSuccess(row ->
                {
                    rc.response().setStatusCode(201);

                    rc.json(row);
                })
                .onFailure(rc::fail);
    }

    private void kudosFeed(RoutingContext rc)
    {
        kudos.feed(PolicyService.user(rc), rc.queryParams())
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }
}
