package com.pulse.platform.policy;

import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.auth.SessionHandler;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.http.ClientIp;
import com.pulse.platform.http.RequestId;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The single enforcement point for authorization (task 3.5, spec platform/rbac-visibility, design.md
 * decision 2). Modules get it from {@code ModuleContext.policy()} and use it for three things:
 *
 * <ol>
 *   <li><b>Route permissions.</b> Every route declares what it needs:
 *       {@code router.get("/team").handler(policy.requires("team.view")).handler(this::team)}.</li>
 *   <li><b>Visibility.</b> {@link #scope} resolves which people the caller may see in a data domain;
 *       {@link #requireVisible} checks one person. Repositories of personal data extend
 *       {@link ScopedRepository} and take the {@link Scope}. Nothing else builds visibility filters.</li>
 *   <li><b>Separation of duties.</b> {@link #refuseSelf} refuses, audits and answers 422 when the
 *       caller acts on their own record.</li>
 * </ol>
 *
 * <p>Visibility rules: a person sees themself (self.view); a holder of team.view sees their current
 * direct reports (effective-dated reporting line); all.view sees everyone; modules widen one domain
 * through {@link VisibilityRelation}s (mentor sees mentees' training) and owner columns (account owner
 * sees their visits). Check-in times ({@link #DOMAIN_CHECKIN}) are visible to the person and their
 * direct manager only, not to all.view.
 *
 * <p>A person the caller may not see and a person who does not exist get the same 403
 * ({@link #noAccess()}), so existence is not disclosed. Only a caller who sees everyone gets a 404 for
 * a missing person.
 */
public final class PolicyService
{
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    /** Profile, overview and other personal data (the base rules). */
    public static final String DOMAIN_PERSON = "person";

    /** Training plans; widened by the mentor relation. */
    public static final String DOMAIN_TRAINING = "training";

    /** Customer visits; widened by the account-owner column. */
    public static final String DOMAIN_VISITS = "visits";

    public static final String DOMAIN_OKRS = "okrs";

    public static final String DOMAIN_ATTENDANCE = "attendance";

    /** Attendance check-in times: the person and their direct manager only. */
    public static final String DOMAIN_CHECKIN = "attendance.checkin";

    public static final String DOMAIN_SALARY = "salary";

    public static final String DOMAIN_REVIEWS = "reviews";

    public static final String DOMAIN_PULSE = "pulse";

    /**
     * Actions nobody may take on their own record (spec rbac-visibility "Separation of duties"), with
     * the sentence shown when it is refused.
     */
    public static final Map<String, String> SELF_REFUSED = Map.of(
            "appraisal.increment.approve", "You can't approve your own increment.",
            "appraisal.increment.propose", "You can't propose your own increment.",
            "discipline.override", "You can't override your own Discipline score.",
            "effort.confirm", "You can't confirm your own extra-mile points.",
            "visit.verify", "You can't verify your own visit.",
            "reviews.calibrate", "You can't calibrate yourself.",
            "bugs.dispute.resolve", "You can't resolve your own bug dispute.",
            "review360.reviewer.assign", "You can't assign yourself as a 360° reviewer of another person's results.");

    private final SqlExecutor sql;

    private final Set<String> catalogue;

    private final List<VisibilityRelation> relations;

    private final boolean trustProxy;

    private final Clock clock;

    public PolicyService(SqlExecutor sql, Set<String> catalogue, List<VisibilityRelation> relations, boolean trustProxy)
    {
        this(sql, catalogue, relations, trustProxy, Clock.system(ZONE));
    }

    PolicyService(SqlExecutor sql, Set<String> catalogue, List<VisibilityRelation> relations, boolean trustProxy,
                  Clock clock)
    {
        this.sql = sql;
        this.catalogue = Set.copyOf(catalogue);
        this.relations = List.copyOf(relations);
        this.trustProxy = trustProxy;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ uniform refusals

    /** The one refusal for "not allowed" and "not there" (403 {@code no_access}). */
    public static ApiException noAccess()
    {
        return ApiException.forbidden("no_access", "You don't have access");
    }

    /** 404 for a missing record, only for callers who could see it if it existed. */
    public static ApiException notFound()
    {
        return new ApiException(404, "not_found", "Not found");
    }

    // ------------------------------------------------------------------ route permissions

    /** A route handler that lets the request through only when the caller holds every permission. */
    public Handler<RoutingContext> requires(String... permissions)
    {
        Set<String> needed = declared(permissions);

        return ctx ->
        {
            JsonObject user = user(ctx);

            if (user == null)
            {
                ctx.fail(401, ApiException.unauthorized("unauthenticated", "Sign in to continue."));

                return;
            }

            if (!permissions(user).containsAll(needed))
            {
                refuseRoute(ctx, user, needed);

                return;
            }

            ctx.next();
        };
    }

    /** A route handler that lets the request through when the caller holds at least one permission. */
    public Handler<RoutingContext> requiresAny(String... permissions)
    {
        Set<String> needed = declared(permissions);

        return ctx ->
        {
            JsonObject user = user(ctx);

            if (user == null)
            {
                ctx.fail(401, ApiException.unauthorized("unauthenticated", "Sign in to continue."));

                return;
            }

            Set<String> held = permissions(user);

            if (needed.stream().noneMatch(held::contains))
            {
                refuseRoute(ctx, user, needed);

                return;
            }

            ctx.next();
        };
    }

    /** Audits a refused route permission check (spec audit-log), then answers 403. */
    private void refuseRoute(RoutingContext ctx, JsonObject user, Set<String> needed)
    {
        JsonObject entry = AuditService.entry(user, AuditEvents.PERMISSION_REFUSED, "route",
                        ctx.request().method() + " " + ctx.request().path(),
                        new JsonObject().put("required", new JsonArray(List.copyOf(needed))))
                .put("outcome", "refused");

        AuditService.record(sql, entry).onComplete(ignored -> ctx.fail(403, noAccess()));
    }

    /** Unknown permission keys fail at mount time, so a typo cannot silently open or close a route. */
    private Set<String> declared(String... permissions)
    {
        if (permissions == null || permissions.length == 0)
        {
            throw new IllegalArgumentException("A route must declare at least one permission");
        }

        Set<String> keys = new LinkedHashSet<>(List.of(permissions));

        for (String key : keys)
        {
            if (!catalogue.contains(key))
            {
                throw new IllegalArgumentException("Unknown permission '" + key + "'. Register it in the module descriptor.");
            }
        }

        return keys;
    }

    // ------------------------------------------------------------------ visibility

    /** The signed-in user ({@code {id, role, personId, permissions}}) of a request; null before the session guard. */
    public static JsonObject user(RoutingContext ctx)
    {
        return ctx.get(SessionHandler.USER);
    }

    public static Set<String> permissions(JsonObject user)
    {
        Set<String> held = new LinkedHashSet<>();

        user.getJsonArray("permissions", new JsonArray()).forEach(p -> held.add(String.valueOf(p)));

        return held;
    }

    /** The people {@code user} may see in {@code domain}. */
    public Future<Scope> scope(JsonObject user, String domain)
    {
        if (user == null || user.getString("id") == null)
        {
            return Future.failedFuture(ApiException.unauthorized("unauthenticated", "Sign in to continue."));
        }

        if (domain == null || domain.isBlank())
        {
            return Future.failedFuture(new IllegalArgumentException("domain"));
        }

        String userId = user.getString("id");
        String personId = user.getString("personId");
        Set<String> held = permissions(user);

        boolean checkin = DOMAIN_CHECKIN.equals(domain);

        if (!checkin && held.contains("all.view"))
        {
            return Future.succeededFuture(new Scope(domain, userId, personId, true, Set.of()));
        }

        PolicyRepository repo = new PolicyRepository(sql);

        List<Future<JsonArray>> parts = new ArrayList<>();

        if (held.contains("team.view"))
        {
            parts.add(repo.directReports(userId, LocalDate.now(clock)));
        }

        if (!checkin && personId != null)
        {
            for (VisibilityRelation relation : relations)
            {
                if (relation.domain().equals(domain))
                {
                    parts.add(repo.related(relation.sql(), personId));
                }
            }
        }

        return Future.all(parts).map(all ->
        {
            Set<String> visible = new LinkedHashSet<>();

            if (personId != null && held.contains("self.view"))
            {
                visible.add(personId);
            }

            for (int i = 0; i < parts.size(); i++)
            {
                JsonArray rows = all.resultAt(i);

                rows.forEach(row -> visible.add(((JsonObject) row).getString("person_id")));
            }

            visible.remove(null);

            return new Scope(domain, userId, personId, false, visible);
        });
    }

    /**
     * Succeeds when {@code user} may see {@code personId} in {@code domain}; otherwise fails with
     * {@link #noAccess()}, whether or not the person exists. A caller who sees everyone gets
     * {@link #notFound()} for a missing person.
     */
    public Future<Scope> requireVisible(JsonObject user, String personId, String domain)
    {
        return scope(user, domain).compose(scope ->
        {
            UUID id = uuid(personId);

            if (!scope.all())
            {
                if (id != null && scope.allows(id.toString()))
                {
                    return Future.succeededFuture(scope);
                }

                JsonObject entry = AuditService.entry(user, AuditEvents.ACCESS_REFUSED, "person",
                                personId == null ? null : personId.substring(0, Math.min(personId.length(), 64)),
                                new JsonObject().put("domain", domain))
                        .put("outcome", "refused");

                return AuditService.record(sql, entry)
                        .transform(ignored -> Future.failedFuture(noAccess()));
            }

            if (id == null)
            {
                return Future.failedFuture(notFound());
            }

            return new PolicyRepository(sql).person(id)
                    .compose(row -> row == null ? Future.failedFuture(notFound()) : Future.succeededFuture(scope));
        });
    }

    // ------------------------------------------------------------------ separation of duties

    /** {@code {sourceIp, requestId}} of a request, for audit entries. */
    public JsonObject request(RoutingContext ctx)
    {
        return new JsonObject()
                .put("sourceIp", ClientIp.of(ctx, trustProxy))
                .put("requestId", RequestId.of(ctx));
    }

    /**
     * Refuses {@code action} when {@code subjectPersonId} is the caller's own person (or
     * {@code subjectUserId} their own account): writes a "refused" audit entry and fails with 422
     * {@code self_action_refused}. For {@code review360.reviewer.assign} the subject is the reviewer
     * being assigned. The audit entry is written outside the caller's transaction so it survives the
     * refusal.
     *
     * @param action  a key of {@link #SELF_REFUSED}
     * @param request {@code {sourceIp, requestId}} from {@link #request(RoutingContext)}
     */
    public Future<Void> refuseSelf(JsonObject user, String action, String subjectPersonId, String subjectUserId,
                                   JsonObject request)
    {
        String message = SELF_REFUSED.get(action);

        if (message == null)
        {
            return Future.failedFuture(new IllegalArgumentException("Unknown separation-of-duties action '" + action + "'"));
        }

        boolean self = (subjectPersonId != null && subjectPersonId.equalsIgnoreCase(user.getString("personId", "")))
                || (subjectUserId != null && subjectUserId.equalsIgnoreCase(user.getString("id", "")));

        if (!self)
        {
            return Future.succeededFuture();
        }

        JsonObject req = request == null ? new JsonObject() : request;

        JsonObject entry = new JsonObject()
                .put("actorId", user.getString("id"))
                .put("actorRole", user.getString("role"))
                .put("action", action)
                .put("targetType", subjectPersonId != null ? "person" : "user")
                .put("targetId", subjectPersonId != null ? subjectPersonId : subjectUserId)
                .put("outcome", "refused")
                .put("sourceIp", req.getString("sourceIp"))
                .put("requestId", req.getString("requestId"))
                .put("summary", new JsonObject().put("reason", "separation_of_duties"));

        return AuditService.record(sql, entry)
                .transform(ignored -> Future.failedFuture(ApiException.unprocessable("self_action_refused", message)));
    }

    private static UUID uuid(String value)
    {
        if (value == null)
        {
            return null;
        }

        try
        {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }
}
