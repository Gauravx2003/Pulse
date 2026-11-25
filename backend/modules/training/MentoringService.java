package com.pulse.modules.training;

import com.pulse.calc.core.CoreThresholds;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.notify.NotificationTriggers;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Mentoring tools and managers' views (spec growth/training-mentoring "Mentoring", "Manager views and
 * suggestions"; task 21.1 part D): sessions, "Ask for a session", the mentor's Pending-for-you list
 * and KPIs, checkpoint requests through the 360° form, the Mentors table and onboarding suggestions.
 *
 * <p>Notifications (platform 5.3): session logged → trainee; blocker → the trainee's manager; session
 * asked → mentor; checkpoint requested → each reviewer.
 *
 * <p>Not here yet: the High Do-today item for a blocker (action providers, task 21.2); plans for the
 * three lowest culture values ("Suggested from your reviews") need the culture score (task 4.4).
 */
final class MentoringService
{
    static final String SESSION_LOGGED = "training.session_logged";

    static final String SESSION_REQUESTED = "training.session_requested";

    static final String CHECKPOINT_REQUESTED = "training.checkpoint_requested";

    static final int TOPICS_MIN = 10;

    private final ModuleContext ctx;

    private final AssignmentService assignments;

    MentoringService(ModuleContext ctx, AssignmentService assignments)
    {
        this.ctx = ctx;

        this.assignments = assignments;
    }

    // ------------------------------------------------------------------ sessions

    /** The mentor (or a manager) logs a session, optionally answering a session request. */
    Future<JsonObject> logSession(JsonObject user, UUID id, JsonObject body)
    {
        return assignments.access(user, id).compose(a ->
        {
            JsonArray roles = a.getJsonArray("roles");

            if (!roles.contains("mentor") && !roles.contains("manager"))
            {
                return Future.failedFuture(ApiException.forbidden("no_access", "Only the mentor or a manager logs sessions."));
            }

            LocalDate today = assignments.today();

            if (LocalDate.parse(body.getString("heldOn")).isAfter(today))
            {
                return Future.failedFuture(ApiException.unprocessable("session_in_future", "Pick today or an earlier date.").field("heldOn"));
            }

            if (body.getString("topics").strip().length() < TOPICS_MIN)
            {
                return Future.failedFuture(ApiException.unprocessable("topics_required", "Write what you talked about (10+ characters).")
                        .field("topics"));
            }

            return ctx.sql().inTransaction(tx ->
            {
                MentoringRepository repo = new MentoringRepository(tx);

                Future<Void> answered = body.getString("requestId") == null ? Future.succeededFuture()
                        : repo.handleRequest(UUID.fromString(body.getString("requestId")), id, today).compose(n -> n == 0
                                ? Future.failedFuture(PolicyService.notFound())
                                : Future.<Void>succeededFuture());

                boolean blocker = body.getBoolean("blocker", false);

                // TODO(21.2): a blocker also adds a High Do-today item for 5 days (action provider).
                return answered.compose(v -> repo.insertSession(id, body, UUID.fromString(user.getString("id"))))
                        .compose(row -> AuditService.record(tx, AuditService.entry(user, SESSION_LOGGED, "training_assignment", id.toString(),
                                new JsonObject().put("blocker", blocker))))
                        .compose(v -> assignments.notifyUser(tx, a.getString("personUserId"), uid -> NotificationTriggers.mentorSessionLogged(uid,
                                user.getString("name"), LocalDate.parse(body.getString("heldOn")))))
                        .compose(v -> !blocker ? Future.<Void>succeededFuture()
                                : repo.managerOf(UUID.fromString(a.getString("personId"))).compose(manager -> assignments.notifyUser(tx, manager,
                                        uid -> NotificationTriggers.mentorSessionBlocked(uid, a.getString("personId"), a.getString("personName")))));
            });
        }).compose(v -> assignments.get(user, id));
    }

    /** The trainee asks the mentor for a session. */
    Future<JsonObject> askForSession(JsonObject user, UUID id, String note)
    {
        return assignments.access(user, id).compose(a ->
        {
            if (!a.getJsonArray("roles").contains("trainee"))
            {
                return Future.failedFuture(ApiException.forbidden("no_access", "Only the trainee asks for a session."));
            }

            if (TrainingLifecycle.finished(a.getString("stage")))
            {
                return Future.failedFuture(ApiException.conflict("finished", "This training is finished."));
            }

            return ctx.sql().inTransaction(tx -> new MentoringRepository(tx).insertRequest(id, note, assignments.today())
                    .compose(row -> AuditService.record(tx, AuditService.entry(user, SESSION_REQUESTED, "training_assignment",
                            id.toString(), new JsonObject())))
                    .compose(v -> assignments.notifyUser(tx, a.getString("mentorId"), uid -> NotificationTriggers.mentorSessionRequested(uid,
                            a.getString("personId"), a.getString("personName")))));
        }).compose(v -> assignments.get(user, id));
    }

    // ------------------------------------------------------------------ the mentor's view

    /**
     * The mentor's Pending-for-you list, oldest first, and KPIs: check-ins to verify, assessments to
     * take, mentor ratings to give, session requests, trainee disagreements to answer and checkpoint
     * ratings to give.
     */
    Future<JsonObject> pending(JsonObject user)
    {
        UUID me = UUID.fromString(user.getString("id"));

        MentoringRepository repo = new MentoringRepository(ctx.sql());

        LocalDate today = assignments.today();

        return Future.all(repo.mentored(me), repo.checkInsToReview(me), repo.openRequests(me), repo.checkpointsToGive(me)).map(all ->
        {
            JsonArray mentored = all.resultAt(0);

            List<JsonObject> items = new ArrayList<>();

            int active = 0;

            int completed = 0;

            double ratingSum = 0;

            int rated = 0;

            for (Object o : mentored)
            {
                JsonObject a = (JsonObject) o;

                String stage = TrainingLifecycle.stage(a, a.getInteger("weeks"), today);

                active += !TrainingLifecycle.finished(stage) && !TrainingLifecycle.NOT_STARTED.equals(stage) ? 1 : 0;

                completed += TrainingLifecycle.COMPLETED.equals(stage) ? 1 : 0;

                JsonObject signOff = a.getJsonObject("signOff");

                if (signOff != null && signOff.getNumber("mentorRating") != null)
                {
                    ratingSum += signOff.getNumber("mentorRating").doubleValue();

                    rated++;
                }

                if (TrainingLifecycle.ASSESSMENT.equals(stage))
                {
                    items.add(item("Assessment to take", a, TrainingLifecycle.end(a, a.getInteger("weeks")).toString(), "Training period ended"));
                }

                if (TrainingLifecycle.FINAL_REVIEW.equals(stage) && a.getJsonObject("finalReviews").getValue("mentor") == null)
                {
                    JsonObject assessment = a.getJsonObject("assessment");

                    items.add(item("Mentor rating to give", a, assessment.getString("at", today.toString()),
                            "Assessment " + assessment.getValue("score") + "% · " + assessment.getString("result")));
                }

                a.getJsonObject("acknowledgements").forEach(entry ->
                {
                    if (entry.getValue() instanceof JsonObject ack && "Disagreed".equals(ack.getString("st")) && ack.getValue("reply") == null)
                    {
                        items.add(item("Trainee disagreed", a, ack.getString("at", today.toString()), "“" + ack.getString("comment") + "”")
                                .put("key", entry.getKey()));
                    }
                });
            }

            for (Object o : all.<JsonArray>resultAt(1))
            {
                JsonObject c = (JsonObject) o;

                items.add(new JsonObject().put("type", "Check-in to verify").put("assignmentId", c.getString("assignmentId"))
                        .put("personName", c.getString("personName")).put("since", c.getString("submittedOn"))
                        .put("what", "Period of " + c.getString("periodStart")).put("checkInId", c.getString("id")));
            }

            for (Object o : all.<JsonArray>resultAt(2))
            {
                JsonObject r = (JsonObject) o;

                items.add(new JsonObject().put("type", "Session requested").put("assignmentId", r.getString("assignmentId"))
                        .put("personName", r.getString("personName")).put("since", r.getString("requestedOn"))
                        .put("what", "“" + r.getString("note") + "”").put("requestId", r.getString("id")));
            }

            for (Object o : all.<JsonArray>resultAt(3))
            {
                JsonObject r = (JsonObject) o;

                items.add(new JsonObject().put("type", "Checkpoint rating to give").put("assignmentId", r.getString("assignmentId"))
                        .put("personName", r.getString("personName")).put("since", r.getString("dueOn"))
                        .put("what", "Training checkpoint · Day " + r.getInteger("checkpointDay") + " · same form as 360°")
                        .put("reviewRequestId", r.getString("id")));
            }

            items.sort(Comparator.comparing(x -> x.getString("since", "")));

            return new JsonObject()
                    .put("items", new JsonArray(items))
                    .put("kpis", new JsonObject()
                            .put("mentees", mentored.size())
                            .put("active", active)
                            .put("completed", completed)
                            .put("pending", items.size())
                            .put("averageMentorRating", rated == 0 ? null : ratingSum / rated));
        });
    }

    private static JsonObject item(String type, JsonObject a, String since, String what)
    {
        return new JsonObject().put("type", type).put("assignmentId", a.getString("id")).put("personName", a.getString("personName"))
                .put("planName", a.getString("planName")).put("since", since).put("what", what);
    }

    // ------------------------------------------------------------------ checkpoints

    /**
     * A manager requests a plan checkpoint (Day N): a training cycle with self, manager and mentor
     * requests on the 360° form; it shows in their To give list tagged with the day.
     */
    Future<JsonObject> requestCheckpoint(JsonObject user, UUID id, int day)
    {
        return assignments.access(user, id).compose(a ->
        {
            if (!a.getJsonArray("roles").contains("manager"))
            {
                return Future.failedFuture(PolicyService.noAccess());
            }

            if (!a.getJsonArray("checkpoints").contains(day))
            {
                return Future.failedFuture(ApiException.unprocessable("unknown_checkpoint",
                        "Day " + day + " is not a checkpoint of this plan.").field("day"));
            }

            UUID person = UUID.fromString(a.getString("personId"));

            LocalDate due = LocalDate.parse(a.getString("startOn")).plusDays(day);

            String quarter = due.getYear() + "-Q" + ((due.getMonthValue() - 1) / 3 + 1);

            String name = a.getString("personName").split(" ")[0] + " · " + a.getString("planName") + " · Day " + day;

            UUID by = UUID.fromString(user.getString("id"));

            return ctx.sql().inTransaction(tx ->
            {
                MentoringRepository repo = new MentoringRepository(tx);

                return Future.all(repo.userOf(person), repo.managerOf(person)).compose(people -> repo
                        .insertCheckpointCycle(name, quarter, due, id, day, by)
                        .compose(cycleId ->
                        {
                            if (cycleId == null)
                            {
                                return Future.failedFuture(ApiException.conflict("already_requested", "Day " + day + " was already requested."));
                            }

                            UUID cycle = UUID.fromString(cycleId);

                            Future<Void> chain = Future.succeededFuture();

                            String trainee = people.resultAt(0);

                            String manager = people.resultAt(1);

                            List<String[]> reviewers = new ArrayList<>();

                            if (trainee != null)
                            {
                                reviewers.add(new String[] {trainee, "self"});
                            }

                            if (manager != null)
                            {
                                reviewers.add(new String[] {manager, "manager"});
                            }

                            if (a.getString("mentorId") != null)
                            {
                                reviewers.add(new String[] {a.getString("mentorId"), "internal"});
                            }

                            for (String[] reviewer : reviewers)
                            {
                                chain = chain.compose(v -> repo.insertCheckpointRequest(cycle, person, UUID.fromString(reviewer[0]), reviewer[1], by)
                                        .compose(requestId -> requestId == null ? Future.<Void>succeededFuture()
                                                : assignments.notifyUser(tx, reviewer[0], uid -> NotificationTriggers.checkpointRequested(uid,
                                                        a.getString("personName"), a.getString("planName"), day, due, requestId))));
                            }

                            return chain.compose(v -> AuditService.record(tx, AuditService.entry(user, CHECKPOINT_REQUESTED,
                                    "training_assignment", id.toString(), new JsonObject().put("day", day))));
                        }));
            });
        }).compose(v -> assignments.get(user, id));
    }

    // ------------------------------------------------------------------ managers' views

    Future<JsonArray> mentors(JsonObject user)
    {
        return ctx.policy().scope(user, PolicyService.DOMAIN_TRAINING).compose(scope -> new MentoringRepository(ctx.sql()).mentors(scope));
    }

    /**
     * Joiners under {@code core.thresholds} {@code newJoinerMonths} (3) without a plan, with the onboarding
     * plan to assign (spec "New joiner suggestion").
     */
    Future<JsonArray> onboardingSuggestions(JsonObject user)
    {
        Future<JsonObject> thresholds = ctx.calc() == null ? Future.succeededFuture(CoreThresholds.json())
                : ctx.calc().getObject("core.thresholds", new JsonObject());

        return thresholds.compose(t -> onboardingSuggestions(user, assignments.today().minusMonths(t.getInteger("newJoinerMonths"))));
    }

    private Future<JsonArray> onboardingSuggestions(JsonObject user, LocalDate since)
    {
        MentoringRepository repo = new MentoringRepository(ctx.sql());

        return ctx.policy().scope(user, PolicyService.DOMAIN_TRAINING)
                .compose(scope -> Future.all(repo.joinersWithoutPlan(scope, since), repo.onboardingPlans()))
                .map(all ->
                {
                    JsonArray plans = all.resultAt(1);

                    JsonObject plan = plans.isEmpty() ? null : plans.getJsonObject(0);

                    JsonArray out = new JsonArray();

                    for (Object o : all.<JsonArray>resultAt(0))
                    {
                        out.add(((JsonObject) o).copy().put("suggestedPlan", plan).put("action", "Assign"));
                    }

                    return out;
                });
    }

    /** Mentor choices for the assign form: the people the manager sees, plus the manager when they have a profile. */
    Future<JsonArray> mentorOptions(JsonObject user)
    {
        return ctx.policy().scope(user, PolicyService.DOMAIN_PERSON).compose(scope -> new MentoringRepository(ctx.sql()).mentorOptions(scope));
    }
}
