package com.pulse.modules.training;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.TrainingRating;
import com.pulse.calc.people.TrainingFormulas;
import com.pulse.calc.core.WorkingDays;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import com.pulse.platform.policy.Scope;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Training assignments and their lifecycle (spec growth/training-mentoring "Assignment", "Lifecycle
 * order", "Check-ins", "Assessment and reviews", "Sign-off and acknowledgement"; task 21.1 part B).
 *
 * <p>Who may do what on an assignment: the <b>trainee</b> (the person on it) ticks modules, submits
 * check-ins, signs off and acknowledges feedback; the <b>mentor</b> reviews check-ins, records the
 * assessment and writes the mentor review; a <b>manager</b> (training.manage, sees the person, not the
 * trainee) does what the mentor does plus assigning, editing, removing and the manager review with the
 * decision. Others who may see the person only read.
 *
 * <p>Not here yet: notifications (5.3) — assignment, assessment result, reviews ready, a disagreement
 * flagging the manager — are recorded in state only.
 */
final class AssignmentService
{
    static final String ASSIGNED = "training.assigned";

    static final String UPDATED = "training.assignment_updated";

    static final String REMOVED = "training.assignment_removed";

    static final String CHECKIN_REVIEWED = "training.checkin_reviewed";

    static final String ASSESSED = "training.assessment_recorded";

    static final String FINAL_REVIEW = "training.final_review";

    static final String SIGNED_OFF = "training.signed_off";

    static final List<String> DECISIONS = List.of("Completed", "Extend", "Not completed");

    static final int TEXT_MIN = 15;

    static final int REASON_MIN = 10;

    private final ModuleContext ctx;

    private final PolicyService policy;

    AssignmentService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();
    }

    LocalDate today()
    {
        return ctx.calc() == null ? LocalDate.now(PolicyService.ZONE) : ctx.calc().clock().today();
    }

    // ------------------------------------------------------------------ reads

    Future<JsonArray> list(JsonObject user, String personId)
    {
        LocalDate today = today();

        return policy.scope(user, PolicyService.DOMAIN_TRAINING)
                .compose(scope -> new AssignmentRepository(ctx.sql()).list(scope, personId == null ? null : UUID.fromString(personId))
                        .map(rows ->
                        {
                            rows.forEach(row -> decorate((JsonObject) row, user, scope, today));

                            return rows;
                        }));
    }

    /** One assignment with its stage, actions, check-in periods, sessions, requests and feedback keys. */
    Future<JsonObject> get(JsonObject user, UUID id)
    {
        AssignmentRepository repo = new AssignmentRepository(ctx.sql());

        return access(user, id).compose(a -> Future.all(repo.checkIns(id), repo.sessions(id), repo.requests(id), repo.holidays(),
                        ctx.calc().getObject("people.trainingRating", new JsonObject().put("assignment", id.toString())))
                .map(all ->
                {
                    JsonArray checkIns = all.resultAt(0);

                    Set<LocalDate> holidays = WorkingDays.holidays(all.resultAt(3), null);

                    return a.put("checkIns", checkIns)
                            .put("periods", periods(a, checkIns, holidays, today()).getJsonArray("periods"))
                            .put("sessions", all.resultAt(1))
                            .put("requests", all.resultAt(2))
                            .put("feedbackKeys", TrainingLifecycle.feedbackKeys(a, checkIns))
                            .put("rating", (JsonObject) all.resultAt(4));
                }));
    }

    // ------------------------------------------------------------------ assignment

    /**
     * Assigns plans to people with a mentor (spec "Assignment"): people already on a plan are skipped,
     * a weekly load above about 16 hours is a warning, a person never mentors themself.
     */
    Future<JsonObject> assign(JsonObject user, JsonObject body)
    {
        AssignmentRepository repo = new AssignmentRepository(ctx.sql());

        List<String> personIds = strings(body.getJsonArray("personIds"));

        List<String> planIds = strings(body.getJsonArray("planIds"));

        UUID mentorId = UUID.fromString(body.getString("mentorId"));

        LocalDate start = LocalDate.parse(body.getString("startOn"));

        if (body.getString("endOn") != null && planIds.size() > 1)
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field",
                    "Set the end of the training period for one plan at a time; with several plans each uses its own length.").field("endOn"));
        }

        if (body.getString("endOn") != null && LocalDate.parse(body.getString("endOn")).isBefore(start))
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "The end must be on or after the start.").field("endOn"));
        }

        Future<Void> visible = Future.succeededFuture();

        for (String personId : personIds)
        {
            visible = visible.compose(v -> policy.requireVisible(user, personId, PolicyService.DOMAIN_TRAINING).mapEmpty());
        }

        return visible
                .compose(v -> Future.all(repo.mentor(mentorId), repo.plans(uuids(planIds)), repo.people(uuids(personIds)),
                        repo.openFor(uuids(personIds))))
                .compose(all ->
                {
                    JsonObject mentor = all.resultAt(0);

                    JsonArray plans = all.resultAt(1);

                    if (mentor == null)
                    {
                        return Future.failedFuture(ApiException.badRequest("invalid_field",
                                "Choose an active colleague with a performance profile as mentor.").field("mentorId"));
                    }

                    if (plans.size() != planIds.size())
                    {
                        return Future.failedFuture(PolicyService.notFound());
                    }

                    return save(user, body, mentor, plans, all.resultAt(2), all.resultAt(3));
                });
    }

    private Future<JsonObject> save(JsonObject user, JsonObject body, JsonObject mentor, JsonArray plans, JsonArray people,
                                    JsonArray open)
    {
        JsonArray assigned = new JsonArray();

        JsonArray skipped = new JsonArray();

        JsonArray warnings = new JsonArray();

        List<JsonObject> rows = new ArrayList<>();

        Map<String, Double> load = new HashMap<>();

        Set<String> onPlan = new LinkedHashSet<>();

        for (Object item : open)
        {
            JsonObject o = (JsonObject) item;

            onPlan.add(o.getString("personId") + "|" + o.getString("planId"));

            load.merge(o.getString("personId"), TrainingLifecycle.weeklyHours(o), Double::sum);
        }

        for (Object p : people)
        {
            JsonObject person = (JsonObject) p;

            String personId = person.getString("id");

            if (mentor.getString("id").equals(person.getString("userId")))
            {
                skipped.add(new JsonObject().put("personName", person.getString("name")).put("reason", "A person can't mentor themselves."));

                continue;
            }

            for (Object pl : plans)
            {
                JsonObject plan = (JsonObject) pl;

                if (!onPlan.add(personId + "|" + plan.getString("id")))
                {
                    skipped.add(new JsonObject().put("personName", person.getString("name")).put("planName", plan.getString("name"))
                            .put("reason", "Already on this plan."));

                    continue;
                }

                double hours = load.merge(personId, TrainingLifecycle.weeklyHours(plan), Double::sum);

                rows.add(new JsonObject()
                        .put("personId", personId)
                        .put("personName", person.getString("name"))
                        .put("planId", plan.getString("id"))
                        .put("planName", plan.getString("name"))
                        .put("planVersion", plan.getInteger("planVersion"))
                        .put("mentorId", mentor.getString("id"))
                        .put("meetingFrequency", body.getString("meetingFrequency", "Weekly"))
                        .put("cadence", body.getString("cadence", plan.getString("cadence")))
                        .put("startOn", body.getString("startOn"))
                        .put("endOn", body.getString("endOn"))
                        .put("assessmentWindowDays", body.getInteger("assessmentWindowDays", 3)));

                if (hours > TrainingLifecycle.WEEKLY_LOAD_WARNING)
                {
                    warnings.add(new JsonObject().put("personName", person.getString("name"))
                            .put("message", person.getString("name") + " would have about " + Math.round(hours)
                                    + " hours of training a week (more than 16)."));
                }
            }
        }

        UUID by = UUID.fromString(user.getString("id"));

        LocalDate today = today();

        return ctx.sql().inTransaction(tx ->
        {
            AssignmentRepository repo = new AssignmentRepository(tx);

            Future<Void> chain = Future.succeededFuture();

            for (JsonObject row : rows)
            {
                chain = chain.compose(v -> repo.insert(row, by, today).compose(id ->
                {
                    assigned.add(new JsonObject().put("id", id).put("personName", row.getString("personName"))
                            .put("planName", row.getString("planName")));

                    return AuditService.record(tx, AuditService.entry(user, ASSIGNED, "training_assignment", id,
                            new JsonObject().put("personId", row.getString("personId")).put("planId", row.getString("planId"))));
                }));
            }

            // TODO(5.3): notify the trainee and the mentor.
            return chain.map(v -> new JsonObject().put("assigned", assigned).put("skipped", skipped).put("warnings", warnings));
        }).onSuccess(v -> changed());
    }

    /** Edits mentor, dates, assessment window, cadence or meetings of an open assignment (managers). */
    Future<JsonObject> update(JsonObject user, UUID id, JsonObject body, int expectedVersion)
    {
        return managed(user, id).compose(a ->
        {
            JsonObject merged = new JsonObject()
                    .put("mentorId", body.getString("mentorId", a.getString("mentorId")))
                    .put("meetingFrequency", body.getString("meetingFrequency", a.getString("meetingFrequency")))
                    .put("cadence", body.getString("cadence", a.getString("cadence")))
                    .put("startOn", body.getString("startOn", a.getString("startOn")))
                    .put("endOn", body.containsKey("endOn") ? body.getString("endOn") : a.getString("endOn"))
                    .put("assessmentWindowDays", body.getInteger("assessmentWindowDays", a.getInteger("assessmentWindowDays")));

            if (merged.getString("endOn") != null && LocalDate.parse(merged.getString("endOn")).isBefore(LocalDate.parse(merged.getString("startOn"))))
            {
                return Future.failedFuture(ApiException.badRequest("invalid_field", "The end must be on or after the start.").field("endOn"));
            }

            if (merged.getString("mentorId").equals(a.getString("personUserId")))
            {
                return Future.failedFuture(ApiException.badRequest("invalid_field", "A person can't mentor themselves.").field("mentorId"));
            }

            return new AssignmentRepository(ctx.sql()).mentor(UUID.fromString(merged.getString("mentorId"))).compose(mentor ->
                    mentor == null
                            ? Future.failedFuture(ApiException.badRequest("invalid_field", "Choose an active colleague as mentor.").field("mentorId"))
                            : ctx.sql().inTransaction(tx -> new AssignmentRepository(tx).updateDetails(id, merged, expectedVersion)
                                    .compose(row -> AuditService.record(tx, AuditService.entry(user, UPDATED, "training_assignment",
                                            id.toString(), new JsonObject().put("personId", a.getString("personId")))))));
        }).compose(v -> afterWrite(user, id));
    }

    Future<Void> remove(JsonObject user, UUID id)
    {
        return managed(user, id).compose(a -> ctx.sql().inTransaction(tx -> new AssignmentRepository(tx).delete(id)
                .compose(n -> AuditService.record(tx, AuditService.entry(user, REMOVED, "training_assignment", id.toString(),
                        new JsonObject().put("personId", a.getString("personId"))))))).onSuccess(v -> changed());
    }

    /** Ticks or unticks a module (trainee, mentor or manager) while the assignment is open. */
    Future<JsonObject> module(JsonObject user, UUID id, String moduleId, boolean done, String note, int expectedVersion)
    {
        return access(user, id).compose(a ->
        {
            if (!roles(a).contains("trainee") && !roles(a).contains("mentor") && !roles(a).contains("manager"))
            {
                return Future.failedFuture(PolicyService.noAccess());
            }

            refuseFinished(a);

            boolean known = false;

            for (Object m : a.getJsonArray("modules"))
            {
                known = known || moduleId.equals(((JsonObject) m).getString("id"));
            }

            if (!known)
            {
                return Future.failedFuture(PolicyService.notFound());
            }

            JsonObject progress = a.getJsonObject("moduleProgress").copy();

            if (done)
            {
                progress.put(moduleId, new JsonObject().put("at", today().toString()).put("note", note == null ? "" : note));
            }
            else
            {
                progress.remove(moduleId);
            }

            return new AssignmentRepository(ctx.sql()).setJson(id, "module_progress", progress, expectedVersion);
        }).compose(v -> afterWrite(user, id));
    }

    // ------------------------------------------------------------------ check-ins

    /** The trainee submits (or resends) a period's check-in; late submission is allowed. */
    Future<JsonObject> submitCheckIn(JsonObject user, UUID id, JsonObject body)
    {
        AssignmentRepository repo = new AssignmentRepository(ctx.sql());

        return access(user, id).compose(a ->
        {
            if (!roles(a).contains("trainee"))
            {
                return Future.failedFuture(ApiException.forbidden("no_access", "Only the trainee submits check-ins."));
            }

            refuseFinished(a);

            if (body.getString("done").isBlank())
            {
                return Future.failedFuture(ApiException.badRequest("invalid_field", "Say what you completed.").field("done"));
            }

            return Future.all(repo.checkIns(id), repo.holidays()).compose(all ->
            {
                JsonArray periods = periods(a, all.resultAt(0), WorkingDays.holidays(all.resultAt(1), null), today()).getJsonArray("periods");

                boolean known = false;

                for (Object p : periods)
                {
                    known = known || body.getString("periodStart").equals(((JsonObject) p).getString("key"));
                }

                if (!known)
                {
                    return Future.failedFuture(ApiException.unprocessable("unknown_period",
                            "That is not a check-in period of this plan up to today."));
                }

                return repo.submitCheckIn(id, body, today()).compose(row -> row == null
                        ? Future.failedFuture(ApiException.conflict("already_verified", "This period's check-in is already verified."))
                        : Future.succeededFuture());
            });
        }).compose(v -> afterWrite(user, id));
    }

    /** The mentor or a manager verifies a check-in or marks it Needs work (comment required). */
    Future<JsonObject> reviewCheckIn(JsonObject user, UUID checkInId, String decision, String comment, int expectedVersion)
    {
        AssignmentRepository repo = new AssignmentRepository(ctx.sql());

        return repo.checkIn(checkInId).compose(c ->
        {
            if (c == null)
            {
                return Future.failedFuture(PolicyService.notFound());
            }

            UUID assignmentId = UUID.fromString(c.getString("assignmentId"));

            return access(user, assignmentId).compose(a ->
            {
                String role = reviewerRole(a);

                if (role == null)
                {
                    return Future.failedFuture(ApiException.forbidden("no_access", "Only the mentor or a manager reviews check-ins."));
                }

                if ("Needs work".equals(decision) && (comment == null || comment.isBlank()))
                {
                    return Future.failedFuture(ApiException.unprocessable("comment_required", "Say what needs work.").field("comment"));
                }

                if (!"Submitted".equals(c.getString("status")))
                {
                    return Future.failedFuture(ApiException.conflict("already_reviewed", "This check-in is already reviewed."));
                }

                return ctx.sql().inTransaction(tx -> new AssignmentRepository(tx)
                        .reviewCheckIn(checkInId, decision, UUID.fromString(user.getString("id")), role, comment == null ? "" : comment.strip(),
                                today(), expectedVersion)
                        .compose(row -> AuditService.record(tx, AuditService.entry(user, CHECKIN_REVIEWED, "training_checkin",
                                checkInId.toString(), new JsonObject().put("decision", decision)))))
                        .compose(v -> afterWrite(user, assignmentId));
            });
        });
    }

    // ------------------------------------------------------------------ assessment, reviews, sign-off

    /** The mentor or a manager records the assessment once the training period is over. */
    Future<JsonObject> assess(JsonObject user, UUID id, JsonObject body, int expectedVersion)
    {
        return access(user, id).compose(a ->
        {
            String role = reviewerRole(a);

            if (role == null)
            {
                return Future.failedFuture(ApiException.forbidden("no_access", "Only the mentor or a manager records the assessment."));
            }

            requireStage(a, TrainingLifecycle.ASSESSMENT, "The assessment comes after the training period.");

            String notes = body.getString("notes", "").strip();

            if (notes.length() < TEXT_MIN)
            {
                return Future.failedFuture(ApiException.unprocessable("notes_required",
                        "Describe what they showed and what is missing (15+ characters).").field("notes"));
            }

            LocalDate date = LocalDate.parse(body.getString("date"));

            LocalDate end = TrainingLifecycle.end(a, a.getInteger("weeks"));

            if (date.isBefore(end))
            {
                return Future.failedFuture(ApiException.unprocessable("assessment_too_early",
                        "The assessment date must be after the training period (" + end + ").").field("date"));
            }

            int score = body.getInteger("score");

            int pass = a.getInteger("passMark");

            JsonObject assessment = new JsonObject()
                    .put("date", date.toString())
                    .put("type", body.getString("type", a.getString("assessmentType")))
                    .put("score", score)
                    .put("pass", pass)
                    .put("result", score >= pass ? "Pass" : "Fail")
                    .put("notes", notes)
                    .put("by", user.getString("name"))
                    .put("byRole", role)
                    .put("at", today().toString());

            return ctx.sql().inTransaction(tx -> new AssignmentRepository(tx).setJson(id, "assessment", assessment, expectedVersion)
                    .compose(row -> AuditService.record(tx, AuditService.entry(user, ASSESSED, "training_assignment", id.toString(),
                            new JsonObject().put("score", score).put("result", assessment.getString("result"))))));
        }).compose(v -> afterWrite(user, id));
    }

    /**
     * The mentor's or the manager's final review. The manager decides Completed, Extend or Not completed
     * (reason required for the last two); a failed assessment can't be Completed (422); Extend keeps the
     * attempt in history and restarts with a new end date.
     */
    Future<JsonObject> finalReview(JsonObject user, UUID id, JsonObject body, int expectedVersion)
    {
        return access(user, id).compose(a ->
        {
            String role = body.getString("role");

            boolean asMentor = "mentor".equals(role) && roles(a).contains("mentor");

            boolean asManager = "manager".equals(role) && roles(a).contains("manager");

            if (!asMentor && !asManager)
            {
                return Future.failedFuture(ApiException.forbidden("no_access", "mentor".equals(role)
                        ? "Only the mentor writes the mentor review." : "Only a manager of the trainee writes the manager review."));
            }

            requireStage(a, TrainingLifecycle.FINAL_REVIEW, "Final reviews come after the assessment.");

            JsonObject finals = a.getJsonObject("finalReviews").copy();

            if (finals.getValue(role) != null)
            {
                return Future.failedFuture(ApiException.conflict("already_reviewed", "This review is already written."));
            }

            String good = body.getString("good", "").strip();

            String improve = body.getString("improve", "").strip();

            if (good.length() < TEXT_MIN || improve.length() < TEXT_MIN)
            {
                return Future.failedFuture(ApiException.unprocessable("review_incomplete",
                        "Write strengths and what to improve (15+ characters each)."));
            }

            JsonObject review = new JsonObject()
                    .put("rating", body.getNumber("rating"))
                    .put("good", good)
                    .put("improve", improve)
                    .put("by", user.getString("name"))
                    .put("at", today().toString());

            if (asMentor)
            {
                finals.put("mentor", review);

                return saveFinals(user, id, a, finals, expectedVersion);
            }

            String decision = body.getString("decision");

            String reason = body.getString("reason", "").strip();

            if (decision == null)
            {
                return Future.failedFuture(ApiException.unprocessable("decision_required", "Choose Completed, Extend or Not completed.")
                        .field("decision"));
            }

            if ("Completed".equals(decision) && "Fail".equals(a.getJsonObject("assessment").getString("result")))
            {
                return Future.failedFuture(ApiException.unprocessable("failed_assessment",
                        "A failed assessment can't be marked Completed. Choose Extend or Not completed."));
            }

            if (!"Completed".equals(decision) && reason.length() < REASON_MIN)
            {
                return Future.failedFuture(ApiException.unprocessable("reason_required",
                        "Give a reason for " + decision.toLowerCase() + " (10+ characters).").field("reason"));
            }

            review.put("decision", decision).put("reason", reason);

            if ("Extend".equals(decision))
            {
                LocalDate newEnd = body.getString("newEnd") == null ? null : LocalDate.parse(body.getString("newEnd"));

                if (newEnd == null || !newEnd.isAfter(today()))
                {
                    return Future.failedFuture(ApiException.unprocessable("new_end_required", "The new end date must be in the future.")
                            .field("newEnd"));
                }

                review.put("newEnd", newEnd.toString());

                finals.put("manager", review);

                JsonArray history = a.getJsonArray("history").copy().add(new JsonObject()
                        .put("attempt", a.getInteger("attempt"))
                        .put("assess", a.getJsonObject("assessment"))
                        .put("final", finals)
                        .put("end", TrainingLifecycle.end(a, a.getInteger("weeks")).toString()));

                return ctx.sql().inTransaction(tx -> new AssignmentRepository(tx).extend(id, history, newEnd, expectedVersion)
                        .compose(row -> AuditService.record(tx, AuditService.entry(user, FINAL_REVIEW, "training_assignment", id.toString(),
                                new JsonObject().put("role", "manager").put("decision", decision)))));
            }

            finals.put("manager", review);

            return saveFinals(user, id, a, finals, expectedVersion);
        }).compose(v -> afterWrite(user, id));
    }

    private Future<Void> saveFinals(JsonObject user, UUID id, JsonObject a, JsonObject finals, int expectedVersion)
    {
        return ctx.sql().inTransaction(tx -> new AssignmentRepository(tx).setJson(id, "final_reviews", finals, expectedVersion)
                .compose(row -> AuditService.record(tx, AuditService.entry(user, FINAL_REVIEW, "training_assignment", id.toString(),
                        new JsonObject().put("role", finals.getValue("manager") != null && a.getJsonObject("finalReviews").getValue("manager") == null
                                ? "manager" : "mentor")))));
    }

    /**
     * The trainee agrees or disagrees with the final feedback (comment required to disagree), rates the
     * training and the mentor, and the assignment ends as the manager decided. A disagreement stays
     * flagged for the manager until marked discussed.
     */
    Future<JsonObject> signOff(JsonObject user, UUID id, JsonObject body, int expectedVersion)
    {
        return access(user, id).compose(a ->
        {
            if (!roles(a).contains("trainee"))
            {
                return Future.failedFuture(ApiException.forbidden("no_access", "Only the trainee signs off."));
            }

            requireStage(a, TrainingLifecycle.SIGN_OFF, "Sign-off comes after both final reviews.");

            String agree = body.getString("agree");

            String comment = body.getString("comment", "").strip();

            if ("Disagreed".equals(agree) && comment.length() < REASON_MIN)
            {
                return Future.failedFuture(ApiException.unprocessable("comment_required", "Say what you disagree with (10+ characters).")
                        .field("comment"));
            }

            String decision = a.getJsonObject("finalReviews").getJsonObject("manager").getString("decision");

            JsonObject signOff = new JsonObject()
                    .put("st", agree)
                    .put("comment", comment)
                    .put("planRating", body.getNumber("planRating"))
                    .put("mentorRating", body.getNumber("mentorRating"))
                    .put("improve", body.getString("improve", ""))
                    .put("at", today().toString());

            JsonObject acks = a.getJsonObject("acknowledgements").copy();

            int attempt = a.getInteger("attempt");

            for (String prefix : List.of("as:", "fm:", "fg:"))
            {
                acks.put(prefix + attempt, new JsonObject().put("st", agree).put("comment", comment).put("at", today().toString()));
            }

            String outcome = "Completed".equals(decision) ? "completed" : "not_completed";

            // TODO(5.3): notify the manager and the mentor; a disagreement flags the manager.
            return ctx.sql().inTransaction(tx -> new AssignmentRepository(tx).signOff(id, outcome, signOff, acks, expectedVersion)
                    .compose(row -> AuditService.record(tx, AuditService.entry(user, SIGNED_OFF, "training_assignment", id.toString(),
                            new JsonObject().put("agree", agree).put("outcome", outcome)))));
        }).compose(v -> afterWrite(user, id));
    }

    /** The trainee acknowledges one feedback item, or disagrees with a comment. */
    Future<JsonObject> acknowledge(JsonObject user, UUID id, JsonObject body, int expectedVersion)
    {
        AssignmentRepository repo = new AssignmentRepository(ctx.sql());

        return access(user, id).compose(a ->
        {
            if (!roles(a).contains("trainee"))
            {
                return Future.failedFuture(ApiException.forbidden("no_access", "Only the trainee acknowledges feedback."));
            }

            String key = body.getString("key");

            String agree = body.getString("agree");

            String comment = body.getString("comment", "").strip();

            if ("Disagreed".equals(agree) && comment.length() < REASON_MIN)
            {
                return Future.failedFuture(ApiException.unprocessable("comment_required", "Say what you disagree with (10+ characters).")
                        .field("comment"));
            }

            return repo.checkIns(id).compose(checkIns ->
            {
                if (!TrainingLifecycle.feedbackKeys(a, checkIns).contains(key))
                {
                    return Future.failedFuture(PolicyService.notFound());
                }

                JsonObject acks = a.getJsonObject("acknowledgements").copy()
                        .put(key, new JsonObject().put("st", agree).put("comment", comment).put("at", today().toString()));

                return repo.setJson(id, "acknowledgements", acks, expectedVersion);
            });
        }).compose(v -> afterWrite(user, id));
    }

    /** The mentor replies to an acknowledgement. */
    Future<JsonObject> reply(JsonObject user, UUID id, String key, String text, int expectedVersion)
    {
        return access(user, id).compose(a ->
        {
            if (!roles(a).contains("mentor"))
            {
                return Future.failedFuture(ApiException.forbidden("no_access", "Only the mentor replies."));
            }

            JsonObject ack = a.getJsonObject("acknowledgements").getJsonObject(key);

            if (ack == null)
            {
                return Future.failedFuture(PolicyService.notFound());
            }

            JsonObject acks = a.getJsonObject("acknowledgements").copy();

            acks.put(key, ack.copy().put("reply", new JsonObject().put("text", text.strip()).put("by", user.getString("name"))
                    .put("at", today().toString())));

            return new AssignmentRepository(ctx.sql()).setJson(id, "acknowledgements", acks, expectedVersion);
        }).compose(v -> afterWrite(user, id));
    }

    /** A manager marks a sign-off disagreement as discussed, which clears the flag. */
    Future<JsonObject> discussed(JsonObject user, UUID id, int expectedVersion)
    {
        return managed(user, id).compose(a ->
        {
            JsonObject signOff = a.getJsonObject("signOff");

            if (signOff == null || !"Disagreed".equals(signOff.getString("st")))
            {
                return Future.failedFuture(ApiException.conflict("nothing_to_discuss", "There is no disagreement to mark as discussed."));
            }

            return new AssignmentRepository(ctx.sql()).setJson(id, "sign_off", signOff.copy().put("discussed", today().toString()),
                    expectedVersion);
        }).compose(v -> afterWrite(user, id));
    }

    // ------------------------------------------------------------------ access and helpers

    /** A person's overall training rating (people.trainingOverall), for those who may see their training. */
    Future<JsonObject> overall(JsonObject user, String personId)
    {
        return policy.requireVisible(user, personId, PolicyService.DOMAIN_TRAINING)
                .compose(scope -> ctx.calc().getObject("people.trainingOverall", new JsonObject().put("person", personId)));
    }

    /** After a write: cached training ratings are dropped, then the fresh assignment is returned. */
    private Future<JsonObject> afterWrite(JsonObject user, UUID id)
    {
        changed();

        return get(user, id);
    }

    private void changed()
    {
        CalcService.publish(ctx.vertx(), TrainingFormulas.TRAINING);
    }

    /** The assignment when the user may see it (policy scope or as its mentor), with stage and roles. */
    Future<JsonObject> access(JsonObject user, UUID id)
    {
        LocalDate today = today();

        return policy.scope(user, PolicyService.DOMAIN_TRAINING)
                .compose(scope -> new AssignmentRepository(ctx.sql()).byId(scope, id).compose(a -> a == null
                        ? Future.failedFuture(PolicyService.notFound())
                        : Future.succeededFuture(decorate(a, user, scope, today))));
    }

    /** The assignment when the user manages its trainee (training.manage, sees them, not themself). */
    private Future<JsonObject> managed(JsonObject user, UUID id)
    {
        return access(user, id).compose(a -> roles(a).contains("manager")
                ? Future.succeededFuture(a)
                : Future.failedFuture(PolicyService.noAccess()));
    }

    /** Adds stage, end, roles and the actions this user may take next. */
    static JsonObject decorate(JsonObject a, JsonObject user, Scope scope, LocalDate today)
    {
        int weeks = a.getInteger("weeks");

        String stage = TrainingLifecycle.stage(a, weeks, today);

        String userId = user.getString("id");

        JsonArray roles = new JsonArray();

        boolean trainee = userId.equals(a.getString("personUserId"));

        if (trainee)
        {
            roles.add("trainee");
        }

        if (userId.equals(a.getString("mentorId")))
        {
            roles.add("mentor");
        }

        if (!trainee && PolicyService.permissions(user).contains("training.manage") && scope.allows(a.getString("personId")))
        {
            roles.add("manager");
        }

        JsonObject finals = a.getJsonObject("finalReviews", new JsonObject());

        JsonArray actions = new JsonArray();

        boolean reviewer = roles.contains("mentor") || roles.contains("manager");

        if (TrainingLifecycle.ASSESSMENT.equals(stage) && reviewer)
        {
            actions.add("assess");
        }

        if (TrainingLifecycle.FINAL_REVIEW.equals(stage) && roles.contains("mentor") && finals.getValue("mentor") == null)
        {
            actions.add("mentorReview");
        }

        if (TrainingLifecycle.FINAL_REVIEW.equals(stage) && roles.contains("manager") && finals.getValue("manager") == null)
        {
            actions.add("managerReview");
        }

        if (TrainingLifecycle.SIGN_OFF.equals(stage) && trainee)
        {
            actions.add("signOff");
        }

        JsonObject signOff = a.getJsonObject("signOff");

        boolean flagged = signOff != null && "Disagreed".equals(signOff.getString("st")) && signOff.getValue("discussed") == null;

        if (flagged && roles.contains("manager"))
        {
            actions.add("markDiscussed");
        }

        a.remove("personUserId");

        return a.put("stage", stage)
                .put("end", TrainingLifecycle.end(a, weeks).toString())
                .put("roles", roles)
                .put("actions", actions)
                .put("disagreementFlagged", flagged);
    }

    /** Check-in periods and their states (Gaurav's TrainingRating.checkIns: Due, Missed, Submitted, ...). */
    static JsonObject periods(JsonObject a, JsonArray checkIns, Set<LocalDate> holidays, LocalDate today)
    {
        JsonArray updates = new JsonArray();

        for (Object c : checkIns)
        {
            updates.add(new JsonObject().put("period", ((JsonObject) c).getString("periodStart")).put("status", ((JsonObject) c).getString("status")));
        }

        JsonObject input = new JsonObject()
                .put("start", a.getString("startOn"))
                .put("end", a.getString("endOn"))
                .put("weeks", a.getInteger("weeks"))
                .put("cadence", a.getString("cadence"))
                .put("updates", updates);

        return TrainingRating.checkIns(input, holidays, today);
    }

    private static JsonArray roles(JsonObject a)
    {
        return a.getJsonArray("roles");
    }

    /** "Mentor" or "Manager" when the user reviews this assignment, else null. */
    private static String reviewerRole(JsonObject a)
    {
        return roles(a).contains("mentor") ? "Mentor" : roles(a).contains("manager") ? "Manager" : null;
    }

    private static void requireStage(JsonObject a, String stage, String message)
    {
        if (!stage.equals(a.getString("stage")))
        {
            throw ApiException.conflict("out_of_order", message + " This assignment is in " + a.getString("stage") + ".");
        }
    }

    private static void refuseFinished(JsonObject a)
    {
        if (TrainingLifecycle.finished(a.getString("stage")))
        {
            throw ApiException.conflict("finished", "This training is " + a.getString("stage").toLowerCase() + ".");
        }
    }

    private static List<String> strings(JsonArray array)
    {
        List<String> out = new ArrayList<>();

        if (array != null)
        {
            array.forEach(x -> out.add(String.valueOf(x)));
        }

        return out;
    }

    private static UUID[] uuids(List<String> ids)
    {
        return ids.stream().map(UUID::fromString).toArray(UUID[]::new);
    }
}
