package com.pulse.modules.training;

import com.pulse.platform.http.ApiException;
import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.ExpectedVersion;
import com.pulse.platform.http.Ids;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.function.BiFunction;

/**
 * Training and mentoring under {@code /api/v1/training} (task 21.1), following docs/backend-guide.md.
 *
 * <pre>
 * GET    /plans                  the plan library (training.manage)
 * POST   /plans                  create
 * GET    /plans/:id              one plan with its versions
 * PUT    /plans/:id              edit: saves a new version
 * POST   /plans/:id/duplicate    copy as a new plan
 * DELETE /plans/:id              hide from the library (If-Match: version); assignments keep running
 *
 * GET    /assignments?personId=                 visible assignments (as manager, mentor or trainee)
 * POST   /assignments                           assign plans to people with a mentor (training.manage)
 * GET    /assignments/:id                       detail: stage, actions, check-in periods, sessions, feedback keys
 * PUT    /assignments/:id                       edit mentor, dates, window, cadence (training.manage)
 * DELETE /assignments/:id                       remove (training.manage)
 * PUT    /assignments/:id/modules/:moduleId     tick or untick a module
 * POST   /assignments/:id/checkins              the trainee's check-in for a period (resend allowed)
 * POST   /checkins/:id/review                   Verified or Needs work (comment required)
 * POST   /assignments/:id/assessment            mentor or manager, after the training period
 * POST   /assignments/:id/final-review          mentor review, or manager review with the decision
 * POST   /assignments/:id/sign-off              the trainee agrees or disagrees and rates
 * POST   /assignments/:id/acknowledgements      the trainee acknowledges one feedback item
 * POST   /assignments/:id/acknowledgements/:key/reply   the mentor replies
 * POST   /assignments/:id/discussed             a manager clears a sign-off disagreement
 *
 * POST   /assignments/:id/sessions              the mentor (or a manager) logs a session
 * POST   /assignments/:id/session-requests      the trainee asks for a session
 * GET    /mentoring/pending                     the mentor's Pending-for-you list and KPIs
 * POST   /assignments/:id/checkpoints/:day      a manager requests the Day N checkpoint (360° form)
 * GET    /mentors                               Mentors table (training.manage)
 * GET    /suggestions/onboarding                joiners under 3 months without a plan (training.manage)
 * GET    /rating?personId=                      the overall training rating
 * </pre>
 *
 * <p>Checkpoint days travel as text ({@code ["14", "30"]}) because the body schema has no list of
 * whole numbers; responses carry them as numbers.
 */
public final class TrainingRoutes
{
    static final BodySchema MODULE = BodySchema.object()
            .string("id", 40)
            .string("title", 120)
            .oneOf("type", PlanService.MODULE_TYPES.toArray(new String[0]))
            .integer("week", 1, 52)
            .number("hours", 0, 200)
            .string("link", 300)
            .required("title", "type", "week", "hours");

    static final BodySchema PLAN = BodySchema.object()
            .string("name", 120)
            .string("audience", 120)
            .integer("weeks", 1, 52)
            .string("description", 1000)
            .stringList("values", 11, 40)
            .stringList("checkpoints", 8, 3)
            .oneOf("cadence", "Daily", "Weekly", "Monthly")
            .string("assessmentType", 60)
            .integer("passMark", 0, 100)
            .objectList("modules", 40, MODULE)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("name", "weeks");

    static final BodySchema ASSIGN = BodySchema.object()
            .uuidList("personIds", 50)
            .uuidList("planIds", 10)
            .uuid("mentorId")
            .date("startOn")
            .date("endOn")
            .integer("assessmentWindowDays", 1, 30)
            .oneOf("cadence", "Daily", "Weekly", "Monthly")
            .string("meetingFrequency", 40)
            .required("personIds", "planIds", "mentorId", "startOn");

    static final BodySchema EDIT = BodySchema.object()
            .uuid("mentorId")
            .date("startOn")
            .date("endOn")
            .integer("assessmentWindowDays", 1, 30)
            .oneOf("cadence", "Daily", "Weekly", "Monthly")
            .string("meetingFrequency", 40)
            .integer("version", 1, Integer.MAX_VALUE);

    static final BodySchema TICK = BodySchema.object()
            .bool("done")
            .string("note", 300)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("done");

    static final BodySchema CHECK_IN = BodySchema.object()
            .date("periodStart")
            .string("done", 2000)
            .number("hours", 0, 200)
            .integer("confidence", 1, 5)
            .string("blockers", 1000)
            .string("nextPlan", 1000)
            .required("periodStart", "done", "hours");

    static final BodySchema CHECK_IN_REVIEW = BodySchema.object()
            .oneOf("decision", "Verified", "Needs work")
            .string("comment", 1000)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("decision");

    static final BodySchema ASSESSMENT = BodySchema.object()
            .date("date")
            .string("type", 60)
            .integer("score", 0, 100)
            .string("notes", 2000)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("date", "score", "notes");

    static final BodySchema FINAL_REVIEW = BodySchema.object()
            .oneOf("role", "mentor", "manager")
            .number("rating", 1, 5)
            .string("good", 1000)
            .string("improve", 1000)
            .oneOf("decision", AssignmentService.DECISIONS.toArray(new String[0]))
            .string("reason", 1000)
            .date("newEnd")
            .integer("version", 1, Integer.MAX_VALUE)
            .required("role", "rating", "good", "improve");

    static final BodySchema SIGN_OFF = BodySchema.object()
            .oneOf("agree", "Agreed", "Disagreed")
            .string("comment", 1000)
            .integer("planRating", 1, 5)
            .integer("mentorRating", 1, 5)
            .string("improve", 1000)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("agree", "planRating", "mentorRating");

    static final BodySchema ACKNOWLEDGE = BodySchema.object()
            .string("key", 60)
            .oneOf("agree", "Agreed", "Disagreed")
            .string("comment", 1000)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("key", "agree");

    static final BodySchema REPLY = BodySchema.object()
            .string("text", 1000)
            .integer("version", 1, Integer.MAX_VALUE)
            .required("text");

    static final BodySchema SESSION = BodySchema.object()
            .date("heldOn")
            .integer("durationMin", 5, 600)
            .string("mode", 40)
            .string("topics", 1000)
            .string("nextSteps", 1000)
            .bool("blocker")
            .string("blockerNote", 1000)
            .uuid("requestId")
            .required("heldOn", "durationMin", "mode", "topics");

    static final BodySchema SESSION_REQUEST = BodySchema.object()
            .string("note", 500)
            .required("note");

    static final BodySchema VERSION_ONLY = BodySchema.object()
            .integer("version", 1, Integer.MAX_VALUE);

    private final PolicyService policy;

    private final PlanService plans;

    private final AssignmentService assignments;

    private final MentoringService mentoring;

    TrainingRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();

        this.plans = new PlanService(ctx);

        this.assignments = new AssignmentService(ctx);

        this.mentoring = new MentoringService(ctx, assignments);
    }

    void mount(Router router)
    {
        router.get("/plans").handler(policy.requires("training.manage"))
                .handler(rc -> plans.library().onSuccess(items -> rc.json(new JsonObject().put("items", items))).onFailure(rc::fail));

        router.post("/plans").handler(policy.requires("training.manage")).handler(PLAN.handler())
                .handler(rc -> plans.create(PolicyService.user(rc), BodySchema.body(rc)).onSuccess(plan -> created(rc, plan)).onFailure(rc::fail));

        router.get("/plans/:id").handler(policy.requires("training.manage"))
                .handler(rc -> plans.get(Ids.path(rc, "id")).onSuccess(rc::json).onFailure(rc::fail));

        router.put("/plans/:id").handler(policy.requires("training.manage")).handler(PLAN.handler())
                .handler(this::update);

        router.post("/plans/:id/duplicate").handler(policy.requires("training.manage"))
                .handler(rc -> plans.duplicate(PolicyService.user(rc), Ids.path(rc, "id")).onSuccess(plan -> created(rc, plan)).onFailure(rc::fail));

        router.delete("/plans/:id").handler(policy.requires("training.manage"))
                .handler(rc -> plans.delete(PolicyService.user(rc), Ids.path(rc, "id"), ExpectedVersion.of(rc, null))
                        .onSuccess(v -> rc.response().setStatusCode(204).end()).onFailure(rc::fail));

        mountAssignments(router);
    }

    /** Assignments, check-ins, assessment, reviews, sign-off and acknowledgements (part B). */
    private void mountAssignments(Router router)
    {
        String[] anyone = {"self.view", "team.view", "all.view"};

        router.get("/assignments").handler(policy.requiresAny(anyone))
                .handler(rc -> assignments.list(PolicyService.user(rc), rc.queryParams().get("personId"))
                        .onSuccess(items -> rc.json(new JsonObject().put("items", items))).onFailure(rc::fail));

        // GET /api/v1/training/rating?personId=: the overall training rating (calc/people)
        router.get("/rating").handler(policy.requiresAny(anyone)).handler(rc ->
        {
            String personId = rc.queryParams().get("personId");

            if (personId == null)
            {
                rc.fail(ApiException.badRequest("invalid_parameter", "Choose the person.").field("personId"));

                return;
            }

            assignments.overall(PolicyService.user(rc), personId).onSuccess(rc::json).onFailure(rc::fail);
        });

        router.post("/assignments").handler(policy.requires("training.manage")).handler(ASSIGN.handler())
                .handler(rc -> assignments.assign(PolicyService.user(rc), BodySchema.body(rc)).onSuccess(rc::json).onFailure(rc::fail));

        router.get("/assignments/:id").handler(policy.requiresAny(anyone))
                .handler(rc -> assignments.get(PolicyService.user(rc), Ids.path(rc, "id")).onSuccess(rc::json).onFailure(rc::fail));

        router.put("/assignments/:id").handler(policy.requires("training.manage")).handler(EDIT.handler())
                .handler(rc -> versioned(rc, (body, version) -> assignments.update(PolicyService.user(rc), Ids.path(rc, "id"), body, version)));

        router.delete("/assignments/:id").handler(policy.requires("training.manage"))
                .handler(rc -> assignments.remove(PolicyService.user(rc), Ids.path(rc, "id"))
                        .onSuccess(v -> rc.response().setStatusCode(204).end()).onFailure(rc::fail));

        router.put("/assignments/:id/modules/:moduleId").handler(policy.requiresAny(anyone)).handler(TICK.handler())
                .handler(rc -> versioned(rc, (body, version) -> assignments.module(PolicyService.user(rc), Ids.path(rc, "id"),
                        rc.pathParam("moduleId"), body.getBoolean("done"), body.getString("note"), version)));

        router.post("/assignments/:id/checkins").handler(policy.requiresAny(anyone)).handler(CHECK_IN.handler())
                .handler(rc -> assignments.submitCheckIn(PolicyService.user(rc), Ids.path(rc, "id"), BodySchema.body(rc))
                        .onSuccess(rc::json).onFailure(rc::fail));

        router.post("/checkins/:id/review").handler(policy.requiresAny(anyone)).handler(CHECK_IN_REVIEW.handler())
                .handler(rc -> versioned(rc, (body, version) -> assignments.reviewCheckIn(PolicyService.user(rc), Ids.path(rc, "id"),
                        body.getString("decision"), body.getString("comment"), version)));

        router.post("/assignments/:id/assessment").handler(policy.requiresAny(anyone)).handler(ASSESSMENT.handler())
                .handler(rc -> versioned(rc, (body, version) -> assignments.assess(PolicyService.user(rc), Ids.path(rc, "id"), body, version)));

        router.post("/assignments/:id/final-review").handler(policy.requiresAny(anyone)).handler(FINAL_REVIEW.handler())
                .handler(rc -> versioned(rc, (body, version) -> assignments.finalReview(PolicyService.user(rc), Ids.path(rc, "id"), body, version)));

        router.post("/assignments/:id/sign-off").handler(policy.requires("self.view")).handler(SIGN_OFF.handler())
                .handler(rc -> versioned(rc, (body, version) -> assignments.signOff(PolicyService.user(rc), Ids.path(rc, "id"), body, version)));

        router.post("/assignments/:id/acknowledgements").handler(policy.requires("self.view")).handler(ACKNOWLEDGE.handler())
                .handler(rc -> versioned(rc, (body, version) -> assignments.acknowledge(PolicyService.user(rc), Ids.path(rc, "id"), body, version)));

        router.post("/assignments/:id/acknowledgements/:key/reply").handler(policy.requiresAny(anyone)).handler(REPLY.handler())
                .handler(rc -> versioned(rc, (body, version) -> assignments.reply(PolicyService.user(rc), Ids.path(rc, "id"),
                        rc.pathParam("key"), body.getString("text"), version)));

        // Mentoring and managers' views (part D)
        router.post("/assignments/:id/sessions").handler(policy.requiresAny(anyone)).handler(SESSION.handler())
                .handler(rc -> mentoring.logSession(PolicyService.user(rc), Ids.path(rc, "id"), BodySchema.body(rc))
                        .onSuccess(rc::json).onFailure(rc::fail));

        router.post("/assignments/:id/session-requests").handler(policy.requires("self.view")).handler(SESSION_REQUEST.handler())
                .handler(rc -> mentoring.askForSession(PolicyService.user(rc), Ids.path(rc, "id"), BodySchema.body(rc).getString("note"))
                        .onSuccess(rc::json).onFailure(rc::fail));

        router.get("/mentoring/pending").handler(policy.requiresAny(anyone))
                .handler(rc -> mentoring.pending(PolicyService.user(rc)).onSuccess(rc::json).onFailure(rc::fail));

        router.post("/assignments/:id/checkpoints/:day").handler(policy.requires("training.manage"))
                .handler(rc -> mentoring.requestCheckpoint(PolicyService.user(rc), Ids.path(rc, "id"), day(rc))
                        .onSuccess(rc::json).onFailure(rc::fail));

        router.get("/mentors").handler(policy.requires("training.manage"))
                .handler(rc -> mentoring.mentors(PolicyService.user(rc))
                        .onSuccess(items -> rc.json(new JsonObject().put("items", items))).onFailure(rc::fail));

        router.get("/suggestions/onboarding").handler(policy.requires("training.manage"))
                .handler(rc -> mentoring.onboardingSuggestions(PolicyService.user(rc))
                        .onSuccess(items -> rc.json(new JsonObject().put("items", items))).onFailure(rc::fail));

        router.post("/assignments/:id/discussed").handler(policy.requires("training.manage")).handler(VERSION_ONLY.handler())
                .handler(rc -> versioned(rc, (body, version) -> assignments.discussed(PolicyService.user(rc), Ids.path(rc, "id"), version)));
    }

    /** The checkpoint day from the path; anything else answers 404 like an unknown route. */
    private static int day(RoutingContext rc)
    {
        try
        {
            return Integer.parseInt(rc.pathParam("day"));
        }
        catch (NumberFormatException e)
        {
            throw ApiException.notFound("not_found", "Not found");
        }
    }

    /** Runs a write that needs the record's version (body {@code version} or If-Match) and answers with the result. */
    private static void versioned(RoutingContext rc, BiFunction<JsonObject, Integer, Future<JsonObject>> write)
    {
        JsonObject body = BodySchema.body(rc);

        write.apply(body, ExpectedVersion.of(rc, body)).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void update(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        plans.update(PolicyService.user(rc), Ids.path(rc, "id"), body, ExpectedVersion.of(rc, body))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private static void created(RoutingContext rc, JsonObject plan)
    {
        rc.response().setStatusCode(201);

        rc.json(plan);
    }
}
