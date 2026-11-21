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

    private final PolicyService policy;

    private final OneOnOneService oneOnOnes;

    private final KudosService kudos;

    ReviewsRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();

        this.oneOnOnes = new OneOnOneService(ctx);

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
