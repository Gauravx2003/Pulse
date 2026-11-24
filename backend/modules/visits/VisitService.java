package com.pulse.modules.visits;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.people.VisitFormulas;
import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.notify.NotificationTriggers;
import com.pulse.platform.policy.PolicyService;
import com.pulse.platform.policy.Scope;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Customer visit lifecycle (spec time/customer-visits, task 18.1):
 * Planned → Report due → Feedback pending → Awaiting manager → Verified / Not counted.
 *
 * <ul>
 *   <li>Plan (manager, for a visible report) or log (the engineer, for themselves); at most 14 days;
 *       never overlapping another visit of the same person.</li>
 *   <li>Edit and delete only before the report, by the engineer or a manager who sees them.</li>
 *   <li>Report: the engineer, once the visit has started.</li>
 *   <li>Customer feedback: the account owner, or a holder of effort.confirm who sees the person
 *       (not on their own visit), after the report.</li>
 *   <li>Verify or reject: effort.confirm, never on one's own visit, after report and feedback.</li>
 * </ul>
 *
 * <p>Points, values and stats come from calc/people through CalcService ({@code people.visitPoints},
 * {@code people.visitStats}, {@code people.visitMonth}); every write publishes {@link VisitFormulas#VISITS}.
 *
 * <p>Notifications (platform 5.3): planned by someone else → the engineer; report filed → the account
 * owner; Remind → the engineer of an overdue report.
 *
 * <p>Not here yet (they depend on other tasks): capacity and On duty days (Track 2 utilization).
 */
final class VisitService
{
    static final String CREATED = "visit.created";

    static final String UPDATED = "visit.updated";

    static final String DELETED = "visit.deleted";

    static final String REPORTED = "visit.reported";

    static final String FEEDBACK = "visit.feedback_recorded";

    static final String REJECTED = "visit.rejected";

    static final int MAX_DAYS = 14;

    private static final Set<String> MANAGES = Set.of("team.view", "all.view");

    /** Internal flag on a loaded visit; removed before it is returned. */
    private static final String MANAGER_MARK = "_manager";

    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final ModuleContext ctx;

    private final PolicyService policy;

    VisitService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();
    }

    /** Today for the visits module: the CalcService clock (pinned by PULSE_TODAY outside production). */
    LocalDate today()
    {
        return today(ctx);
    }

    static LocalDate today(ModuleContext ctx)
    {
        return ctx.calc() == null ? LocalDate.now(PolicyService.ZONE) : ctx.calc().clock().today();
    }

    /** After a visit changes: cached points and stats that read visits are dropped. */
    private void changed()
    {
        CalcService.publish(ctx.vertx(), VisitFormulas.VISITS);
    }

    /** The visit's points breakdown and credited values from calc/people (people.visitPoints). */
    private Future<JsonObject> withPoints(JsonObject visit)
    {
        return ctx.calc().get("people.visitPoints", new JsonObject().put("visit", visit.getString("id")))
                .map(result ->
                {
                    if (result instanceof JsonObject points && points.containsKey("points"))
                    {
                        visit.put("points", points.getJsonObject("points")).put("values", points.getJsonArray("values"));
                    }

                    return visit;
                });
    }

    /** A person's visit stats for a period (people.visitStats): {@code q:Q3} or {@code all}. */
    Future<JsonObject> stats(JsonObject user, String personId, String period)
    {
        return policy.requireVisible(user, personId, PolicyService.DOMAIN_VISITS)
                .compose(scope -> ctx.calc().getObject("people.visitStats", new JsonObject().put("person", personId).put("period", period)))
                .recover(error -> error instanceof IllegalArgumentException
                        ? Future.failedFuture(ApiException.badRequest("invalid_parameter", error.getMessage()).field("period"))
                        : Future.failedFuture(error));
    }

    // ------------------------------------------------------------------ reads

    /** Visits starting in [from, to] the user may see; {@code personId} / {@code ownedByMe} narrow it. */
    Future<JsonArray> list(JsonObject user, String personId, boolean ownedByMe, LocalDate from, LocalDate to)
    {
        Future<Scope> scope = personId == null
                ? policy.scope(user, PolicyService.DOMAIN_VISITS)
                : policy.requireVisible(user, personId, PolicyService.DOMAIN_VISITS);

        UUID owner = ownedByMe ? UUID.fromString(user.getString("id")) : null;

        return scope.compose(s -> new VisitRepository(ctx.sql()).list(s, personId == null ? null : UUID.fromString(personId),
                        owner, from, to)
                .map(rows ->
                {
                    LocalDate today = today();

                    rows.forEach(row -> decorate(markManager((JsonObject) row, user, s), user, today));

                    return rows;
                }));
    }

    Future<JsonObject> get(JsonObject user, UUID id)
    {
        return visible(user, id).map(visit -> decorate(visit, user, today())).compose(this::withPoints);
    }

    // ------------------------------------------------------------------ plan, log, edit, delete

    Future<JsonObject> create(JsonObject user, JsonObject body)
    {
        String personId = body.getString("personId");

        return policy.requireVisible(user, personId, PolicyService.DOMAIN_VISITS)
                .compose(scope -> validateDetails(body, null).map(scope))
                .compose(scope -> ctx.sql().inTransaction(tx ->
                {
                    VisitRepository repo = new VisitRepository(tx);

                    return repo.lockPerson(UUID.fromString(personId))
                            .compose(locked -> refuseOverlap(repo, body, null))
                            .compose(clear -> repo.insert(body, UUID.fromString(user.getString("id"))))
                            .compose(row -> AuditService.record(tx, AuditService.entry(user, CREATED, "visit", row.getString("id"),
                                            new JsonObject().put("personId", personId).put("planned", !personId.equals(user.getString("personId")))))
                                    .compose(v -> repo.byId(scope, UUID.fromString(row.getString("id")))))
                            .compose(visit -> notifyPlanned(tx, user, visit))
                            .map(visit -> markManager(visit, user, scope));
                }))
                .map(visit -> decorate(visit, user, today()))
                .onSuccess(visit -> changed());
    }

    Future<JsonObject> update(JsonObject user, UUID id, JsonObject body, int expectedVersion)
    {
        return editable(user, id).compose(visit ->
        {
            JsonObject details = body.copy().put("personId", visit.getString("personId"));

            return validateDetails(details, id).compose(ok -> ctx.sql().inTransaction(tx ->
            {
                VisitRepository repo = new VisitRepository(tx);

                return repo.lockPerson(UUID.fromString(visit.getString("personId")))
                        .compose(locked -> refuseOverlap(repo, details, id))
                        .compose(clear -> repo.update(id, details, expectedVersion))
                        .compose(row -> AuditService.record(tx, AuditService.entry(user, UPDATED, "visit", id.toString(),
                                new JsonObject().put("personId", visit.getString("personId")))));
            }));
        }).onSuccess(v -> changed()).compose(v -> get(user, id));
    }

    Future<Void> delete(JsonObject user, UUID id, int expectedVersion)
    {
        return editable(user, id).compose(visit -> ctx.sql().inTransaction(tx -> new VisitRepository(tx).delete(id, expectedVersion)
                .compose(removed ->
                {
                    if (removed == 0)
                    {
                        return Future.failedFuture(ApiException.conflict("version_conflict",
                                "Someone changed this visit first. Reload and try again.").currentVersion(visit.getInteger("version")));
                    }

                    return AuditService.record(tx, AuditService.entry(user, DELETED, "visit", id.toString(),
                            new JsonObject().put("personId", visit.getString("personId"))));
                }))).onSuccess(v -> changed());
    }

    // ------------------------------------------------------------------ report, feedback, verification

    Future<JsonObject> report(JsonObject user, UUID id, JsonObject body, int expectedVersion)
    {
        return visible(user, id).compose(visit ->
        {
            if (!isEngineer(user, visit))
            {
                return Future.failedFuture(ApiException.forbidden("no_access", "Only the engineer who went files the visit report."));
            }

            if (visit.getValue("reportedOn") != null)
            {
                return Future.failedFuture(outOfOrder("The report for this visit is already filed."));
            }

            LocalDate today = today();

            if (LocalDate.parse(visit.getString("from")).isAfter(today))
            {
                return Future.failedFuture(ApiException.unprocessable("visit_not_started", "File the report once the visit has started."));
            }

            return ctx.sql().inTransaction(tx -> new VisitRepository(tx).report(id, body, today, expectedVersion)
                    .compose(row -> AuditService.record(tx, AuditService.entry(user, REPORTED, "visit", id.toString(),
                            new JsonObject().put("personId", visit.getString("personId")))))
                    .compose(v -> ctx.notifications().notify(tx, NotificationTriggers.visitFeedbackRequested(visit.getString("ownerId"),
                            visit.getString("personId"), visit.getString("personName"), visit.getString("customer"),
                            LocalDate.parse(visit.getString("from")), LocalDate.parse(visit.getString("to")), id.toString()))));
        }).onSuccess(v -> changed()).compose(v -> get(user, id));
    }

    Future<JsonObject> feedback(JsonObject user, UUID id, JsonObject body, int expectedVersion)
    {
        return visible(user, id).compose(visit ->
        {
            if (!mayRecordFeedback(user, visit))
            {
                return Future.failedFuture(ApiException.forbidden("no_access",
                        "Only the account owner of this visit records the customer's feedback."));
            }

            if (visit.getValue("reportedOn") == null)
            {
                return Future.failedFuture(outOfOrder("Customer feedback comes after the visit report."));
            }

            if (visit.getValue("csat") != null)
            {
                return Future.failedFuture(outOfOrder("Customer feedback for this visit is already recorded."));
            }

            if (LocalDate.parse(body.getString("feedbackOn")).isAfter(today()))
            {
                return Future.failedFuture(ApiException.badRequest("invalid_field", "The feedback date can't be in the future.")
                        .field("feedbackOn"));
            }

            return ctx.sql().inTransaction(tx -> new VisitRepository(tx)
                    .feedback(id, body, UUID.fromString(user.getString("id")), expectedVersion)
                    .compose(row -> AuditService.record(tx, AuditService.entry(user, FEEDBACK, "visit", id.toString(),
                            new JsonObject().put("personId", visit.getString("personId")).put("csat", body.getNumber("csat"))))));
        }).onSuccess(v -> changed()).compose(v -> get(user, id));
    }

    /** Verify ({@code confirmed}) or reject ({@code rejected}) the visit's points. */
    Future<JsonObject> decide(JsonObject user, UUID id, String decision, String note, int expectedVersion, JsonObject request)
    {
        return visible(user, id)
                .compose(visit -> policy.refuseSelf(user, AuditEvents.VISIT_VERIFY, visit.getString("personId"), null, request)
                        .map(visit))
                .compose(visit ->
                {
                    if (!mayDecide(user, visit))
                    {
                        return Future.failedFuture(PolicyService.noAccess());
                    }

                    if (visit.getValue("reportedOn") == null)
                    {
                        return Future.failedFuture(outOfOrder("A visit can't be verified before its report is filed."));
                    }

                    if (visit.getValue("csat") == null)
                    {
                        return Future.failedFuture(outOfOrder("Verify once the customer's feedback is recorded."));
                    }

                    if (!"pending".equals(visit.getString("verification")))
                    {
                        return Future.failedFuture(outOfOrder("This visit is already " + ("confirmed".equals(visit.getString("verification"))
                                ? "verified." : "marked not counted.")));
                    }

                    String event = "confirmed".equals(decision) ? AuditEvents.VISIT_VERIFY : REJECTED;

                    return ctx.sql().inTransaction(tx -> new VisitRepository(tx)
                            .decide(id, decision, UUID.fromString(user.getString("id")), note, expectedVersion)
                            .compose(row -> AuditService.record(tx, AuditService.entry(user, event, "visit", id.toString(),
                                    new JsonObject().put("personId", visit.getString("personId")).put("decision", decision)))));
                })
                .onSuccess(v -> changed())
                .compose(v -> get(user, id));
    }

    /**
     * A manager reminds the engineer of an overdue report (spec "Report": the manager MAY send a reminder).
     * Resolves to {@code {notified}}: false when the engineer may not receive it (deactivated).
     */
    Future<JsonObject> remind(JsonObject user, UUID id)
    {
        return visible(user, id).compose(visit ->
        {
            if (!isManager(visit))
            {
                return Future.failedFuture(ApiException.forbidden("no_access", "Only the engineer's manager sends reminders."));
            }

            if (!"report_due".equals(status(visit, today())))
            {
                return Future.failedFuture(ApiException.conflict("nothing_to_remind", "This visit has no overdue report."));
            }

            return ctx.notifications().notify(ctx.sql(), NotificationTriggers.visitReportDue(visit.getString("personUserId"),
                            visit.getString("customer"), LocalDate.parse(visit.getString("from")), LocalDate.parse(visit.getString("to")),
                            id.toString()))
                    .map(sent -> new JsonObject().put("notified", sent != null));
        });
    }

    /** Tells the engineer about a visit someone else planned for them, in the planning transaction. */
    private Future<JsonObject> notifyPlanned(SqlExecutor tx, JsonObject user, JsonObject visit)
    {
        String engineer = visit.getString("personUserId");

        if (engineer == null || engineer.equals(user.getString("id")))
        {
            return Future.succeededFuture(visit);
        }

        return ctx.notifications().notify(tx, NotificationTriggers.visitPlanned(engineer, visit.getString("customer"), visit.getString("city"),
                        LocalDate.parse(visit.getString("from")), LocalDate.parse(visit.getString("to")), visit.getString("requestedBy"),
                        visit.getString("id")))
                .map(visit);
    }

    // ------------------------------------------------------------------ rules

    /** The visit when the user may see it (its person is visible, or they own the account), else 404. */
    private Future<JsonObject> visible(JsonObject user, UUID id)
    {
        return policy.scope(user, PolicyService.DOMAIN_VISITS)
                .compose(scope -> new VisitRepository(ctx.sql()).byId(scope, id)
                        .compose(visit -> visit == null
                                ? Future.failedFuture(PolicyService.notFound())
                                : Future.succeededFuture(markManager(visit, user, scope))));
    }

    /** A visit that can still be edited (no report) by this user (the engineer or a manager who sees them). */
    private Future<JsonObject> editable(JsonObject user, UUID id)
    {
        return visible(user, id).compose(visit ->
        {
            if (!mayEdit(user, visit))
            {
                return Future.failedFuture(ApiException.forbidden("no_access", "Only the engineer or their manager can change this visit."));
            }

            if (visit.getValue("reportedOn") != null)
            {
                return Future.failedFuture(outOfOrder("A visit with a filed report can't be changed or deleted."));
            }

            return Future.succeededFuture(visit);
        });
    }

    /** Dates, length and account owner. */
    private Future<Void> validateDetails(JsonObject body, UUID exceptId)
    {
        LocalDate from = LocalDate.parse(body.getString("from"));

        LocalDate to = LocalDate.parse(body.getString("to"));

        if (to.isBefore(from))
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "The last day can't be before the first day.").field("to"));
        }

        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS)
        {
            return Future.failedFuture(ApiException.unprocessable("visit_too_long", "A visit can be at most 14 days."));
        }

        return new VisitRepository(ctx.sql()).activeUser(UUID.fromString(body.getString("ownerId"))).compose(active -> active
                ? Future.<Void>succeededFuture()
                : Future.failedFuture(ApiException.badRequest("invalid_field", "Choose an account owner who can sign in to Pulse.")
                        .field("ownerId")));
    }

    /**
     * Refuses a visit that shares a day with another visit of the same person, or with their leave or
     * absence in the attendance records (spec "Plan or log").
     */
    private static Future<Void> refuseOverlap(VisitRepository repo, JsonObject body, UUID exceptId)
    {
        UUID person = UUID.fromString(body.getString("personId"));

        LocalDate from = LocalDate.parse(body.getString("from"));

        LocalDate to = LocalDate.parse(body.getString("to"));

        return repo.overlapping(person, from, to, exceptId)
                .compose(other -> other == null
                        ? Future.<Void>succeededFuture()
                        : Future.failedFuture(ApiException.unprocessable("visit_overlap", "Overlaps the visit to "
                                + other.getString("customer") + " (" + dates(other) + ").")))
                .compose(v -> repo.leaveDays(new UUID[] {person}, from, to))
                .compose(leave -> leave.isEmpty()
                        ? Future.<Void>succeededFuture()
                        : Future.failedFuture(ApiException.unprocessable("visit_on_leave", "The engineer is on leave on "
                                + SHORT.format(LocalDate.parse(leave.getJsonObject(0).getString("day"))) + ".")));
    }

    private static boolean isEngineer(JsonObject user, JsonObject visit)
    {
        return visit.getString("personId").equals(user.getString("personId"));
    }

    /**
     * Marks whether the user sees the visit's person as their manager or HR ({@code team.view} /
     * {@code all.view} through the policy scope), as opposed to only as the account owner.
     */
    private static JsonObject markManager(JsonObject visit, JsonObject user, Scope scope)
    {
        boolean manages = false;

        for (String held : PolicyService.permissions(user))
        {
            manages = manages || MANAGES.contains(held);
        }

        return visit.put(MANAGER_MARK, manages && !isEngineer(user, visit) && scope.allows(visit.getString("personId")));
    }

    private static boolean isManager(JsonObject visit)
    {
        return Boolean.TRUE.equals(visit.getBoolean(MANAGER_MARK));
    }

    /** The engineer, or a manager (or HR) who sees them. */
    private static boolean mayEdit(JsonObject user, JsonObject visit)
    {
        return isEngineer(user, visit) || isManager(visit);
    }

    /** The account owner, or a holder of effort.confirm who manages the engineer (prototype {@code vCanFb}). */
    private static boolean mayRecordFeedback(JsonObject user, JsonObject visit)
    {
        boolean owner = user.getString("id").equals(visit.getString("ownerId"));

        return owner || (PolicyService.permissions(user).contains("effort.confirm") && isManager(visit));
    }

    /** A holder of effort.confirm who manages the engineer; never the engineer themself. */
    private static boolean mayDecide(JsonObject user, JsonObject visit)
    {
        return PolicyService.permissions(user).contains("effort.confirm") && isManager(visit);
    }

    /** Adds the derived status and what this user may do next. */
    static JsonObject decorate(JsonObject visit, JsonObject user, LocalDate today)
    {
        String status = status(visit, today);

        JsonArray actions = new JsonArray();

        boolean reported = visit.getValue("reportedOn") != null;

        if (!reported && mayEdit(user, visit))
        {
            actions.add("edit").add("delete");
        }

        if (!reported && isEngineer(user, visit) && !LocalDate.parse(visit.getString("from")).isAfter(today))
        {
            actions.add("report");
        }

        if (reported && visit.getValue("csat") == null && mayRecordFeedback(user, visit))
        {
            actions.add("feedback");
        }

        if ("awaiting_manager".equals(status) && mayDecide(user, visit))
        {
            actions.add("verify").add("reject");
        }

        visit.remove("personUserId");

        visit.remove(MANAGER_MARK);

        return visit.put("status", status).put("days", ChronoUnit.DAYS.between(LocalDate.parse(visit.getString("from")),
                LocalDate.parse(visit.getString("to"))) + 1).put("actions", actions);
    }

    /** planned · report_due · feedback_pending · awaiting_manager · verified · not_counted. */
    static String status(JsonObject visit, LocalDate today)
    {
        if (visit.getValue("reportedOn") == null)
        {
            return LocalDate.parse(visit.getString("to")).isBefore(today) ? "report_due" : "planned";
        }

        if ("confirmed".equals(visit.getString("verification")))
        {
            return "verified";
        }

        if ("rejected".equals(visit.getString("verification")))
        {
            return "not_counted";
        }

        return visit.getValue("csat") == null ? "feedback_pending" : "awaiting_manager";
    }

    private static ApiException outOfOrder(String message)
    {
        return ApiException.conflict("out_of_order", message);
    }

    private static String dates(JsonObject other)
    {
        LocalDate from = LocalDate.parse(other.getString("from"));

        LocalDate to = LocalDate.parse(other.getString("to"));

        return from.equals(to) ? SHORT.format(from) : SHORT.format(from) + " – " + SHORT.format(to);
    }

    // ------------------------------------------------------------------ Team view

    /** Months after joining during which a person is a new joiner and stays out of the team average. */
    static final int NEW_JOINER_MONTHS = 3;

    /** Stats fields averaged across the team. */
    private static final String[] AVERAGED = {"visits", "days", "onsiteHours", "travelHours", "csat", "points"};

    /**
     * The Team view (spec "Views"): every person in the viewer's visits scope, with or without visits, with
     * {@code people.visitStats} for the period, and the team average of the people who are not new joiners
     * ({@value #NEW_JOINER_MONTHS} months): {@code {period, people: [{personId, name, title, newJoiner, stats}], average}}.
     */
    Future<JsonObject> team(JsonObject user, String period)
    {
        LocalDate today = today();

        return policy.scope(user, PolicyService.DOMAIN_VISITS).compose(scope -> new VisitRepository(ctx.sql()).people(scope)).compose(people ->
        {
            java.util.List<Future<JsonObject>> stats = new java.util.ArrayList<>();

            for (Object o : people)
            {
                stats.add(ctx.calc().getObject("people.visitStats", new JsonObject().put("person", ((JsonObject) o).getString("id"))
                        .put("period", period)));
            }

            return Future.all(stats).map(all ->
            {
                JsonArray rows = new JsonArray();

                double[] sums = new double[AVERAGED.length];

                int[] counts = new int[AVERAGED.length];

                for (int i = 0; i < people.size(); i++)
                {
                    JsonObject p = people.getJsonObject(i);

                    JsonObject st = stats.get(i).result();

                    boolean joiner = p.getString("joinedOn") != null
                            && LocalDate.parse(p.getString("joinedOn")).plusMonths(NEW_JOINER_MONTHS).isAfter(today);

                    if (!joiner)
                    {
                        for (int k = 0; k < AVERAGED.length; k++)
                        {
                            if (st.getValue(AVERAGED[k]) instanceof Number n)
                            {
                                sums[k] += n.doubleValue();

                                counts[k]++;
                            }
                        }
                    }

                    rows.add(new JsonObject().put("personId", p.getString("id")).put("name", p.getString("name"))
                            .put("title", p.getString("title")).put("newJoiner", joiner).put("stats", st));
                }

                JsonObject average = new JsonObject();

                for (int k = 0; k < AVERAGED.length; k++)
                {
                    average.put(AVERAGED[k], counts[k] == 0 ? null : Math.round(sums[k] / counts[k] * 10) / 10.0);
                }

                return new JsonObject().put("period", period).put("people", rows).put("average", average);
            });
        }).recover(error -> error instanceof IllegalArgumentException
                ? Future.failedFuture(ApiException.badRequest("invalid_parameter", error.getMessage()).field("period"))
                : Future.failedFuture(error));
    }

    Future<JsonArray> ownerOptions()
    {
        return new VisitRepository(ctx.sql()).ownerOptions();
    }
}
