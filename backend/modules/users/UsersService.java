package com.pulse.modules.users;

import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.auth.AuthService;
import com.pulse.platform.auth.AuthSettings;
import com.pulse.platform.auth.PasswordService;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The Users directory and the user lifecycle (task 5.1, spec admin/users-roles, rbac-visibility
 * "Managers' user-management limits", privacy-retention "User deletion and anonymization" and
 * "Deactivation versus deletion").
 *
 * <ul>
 *   <li>Who sees and manages whom comes from {@link PolicyService#userScope}: users.all sees every
 *       account; users.team sees the accounts that report to them (not deactivated ones) and manages only
 *       the Employees among them, cannot grant another role or manager, and their new users report to them.</li>
 *   <li>Nobody edits or deletes their own account here (422 {@code self_action_refused}, audited).</li>
 *   <li>Add creates an Invited account with no password and emails a set-password link; Employees get a
 *       performance profile. Manager changes are effective-dated (the open line closes today).</li>
 *   <li>Delete reassigns the user's reports to the deleter, erases name, email and sign-in material, runs
 *       every module's {@link PulseModule#erasePersonalData} hook and keeps a deletion record of the
 *       pseudonymous ids. Deactivate keeps everything and ends the user's sessions.</li>
 * </ul>
 */
final class UsersService
{
    static final String ACTION_EDIT = "users.edit";

    static final String ACTION_DELETE = "users.delete";

    static final String EMPLOYEE = "Employee";

    static final List<String> LEVELS = List.of("L1", "L2", "L3", "L4", "L5");

    static final List<String> STATUSES = List.of("Active", "Invited", "Deactivated");

    static final String MSG_EMAIL_TAKEN = "A user with this email already exists.";

    static final String MSG_ROLE_NOT_ALLOWED = "Only Admin can assign the Manager or Admin role.";

    static final String MSG_MANAGER_NOT_ALLOWED = "Your new users report to you; only Admin can choose another manager.";

    /** What a deletion removes and keeps, shown in the confirmation (spec privacy-retention). */
    static final List<String> REMOVED = List.of("Login, password and sessions", "Name, email and contact details",
            "Performance profile details", "OKRs", "Extra-work records",
            "Free-text pulse notes, 1:1 notes and kudos text they wrote");

    static final List<String> KEPT = List.of("Aggregated scores and review records, under an anonymous id",
            "Audit log entries, under an anonymous id",
            "360° comments, unlinked from the person and scrubbed of names and emails");

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final String UNIQUE_VIOLATION = "23505";

    private final ModuleContext ctx;

    private final PolicyService policy;

    UsersService(ModuleContext ctx)
    {
        this.ctx = ctx;
        this.policy = ctx.policy();
    }

    // ------------------------------------------------------------------ reads

    /** {@code {items, page, pageSize, total, pageSizeCapped, counts, scope}}; each item has canEdit / canDelete. */
    Future<JsonObject> directory(JsonObject user, MultiMap params)
    {
        return policy.userScope(user).compose(scope ->
        {
            UsersRepository repo = new UsersRepository(ctx.sql());

            return Future.all(repo.page(scope, params), repo.counts(scope)).map(all ->
            {
                JsonObject page = all.resultAt(0);
                JsonArray items = new JsonArray();

                page.getJsonArray("items").forEach(row -> items.add(decorate(user, (JsonObject) row)));

                return page
                        .put("items", items)
                        .put("counts", all.<JsonObject>resultAt(1))
                        .put("scope", scope.all() ? "all" : "team");
            });
        });
    }

    /** One account the caller may see. */
    Future<JsonObject> get(JsonObject user, UUID id)
    {
        return visible(user, id).map(row -> decorate(user, row));
    }

    /** The Add / Edit form's choices for this caller: roles, managers, levels and statuses. */
    Future<JsonObject> options(JsonObject user)
    {
        boolean all = PolicyService.permissions(user).contains("users.all");

        RolesRepository roles = new RolesRepository(ctx.sql());

        Future<JsonArray> roleList = all ? roles.all()
                : roles.builtIn(EMPLOYEE).map(r -> r == null ? new JsonArray() : new JsonArray().add(r));

        Future<JsonArray> managers = all ? new UsersRepository(ctx.sql()).managerOptions()
                : Future.succeededFuture(new JsonArray().add(new JsonObject()
                        .put("id", user.getString("id")).put("name", user.getString("name"))));

        return Future.all(roleList, managers).map(both ->
        {
            JsonArray roleOptions = new JsonArray();

            both.<JsonArray>resultAt(0).forEach(r ->
            {
                JsonObject role = (JsonObject) r;

                roleOptions.add(new JsonObject()
                        .put("id", role.getString("id"))
                        .put("name", role.getString("name"))
                        .put("builtIn", role.getBoolean("builtIn")));
            });

            return new JsonObject()
                    .put("roles", roleOptions)
                    .put("managers", both.<JsonArray>resultAt(1))
                    .put("levels", new JsonArray(LEVELS))
                    .put("statuses", new JsonArray(STATUSES))
                    .put("managerLocked", !all);
        });
    }

    /** What deleting the account removes and keeps, and how many reports move to the caller. */
    Future<JsonObject> deletionPreview(JsonObject user, UUID id)
    {
        return manageable(user, id).compose(target -> new UsersRepository(ctx.sql()).currentReports(id).map(reports ->
                new JsonObject()
                        .put("id", id.toString())
                        .put("removed", new JsonArray(REMOVED))
                        .put("kept", new JsonArray(KEPT))
                        .put("reports", reports.size())
                        .put("reassignTo", new JsonObject().put("id", user.getString("id")).put("name", user.getString("name")))
                        .put("suggestion", "Deactivate instead to block sign-in and keep their records.")));
    }

    // ------------------------------------------------------------------ add

    /** Adds an Invited user and emails the set-password link; resolves to the new directory row. */
    Future<JsonObject> create(JsonObject user, JsonObject body, JsonObject request)
    {
        boolean all = PolicyService.permissions(user).contains("users.all");
        String name = body.getString("name", "").strip();
        String email = body.getString("email", "").strip();

        if (name.isEmpty())
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "Enter a name.").field("name"));
        }

        if (!EMAIL.matcher(email).matches())
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "Enter a valid work email.").field("email"));
        }

        String managerId = body.getString("managerId");

        if (!all && managerId != null && !managerId.equalsIgnoreCase(user.getString("id")))
        {
            return refuseLimit(user, "manager_not_allowed", MSG_MANAGER_NOT_ALLOWED, null);
        }

        String manager = all ? managerId : user.getString("id");

        return resolveRole(user, all, body.getString("roleId"), null).compose(role -> ctx.sql().inTransaction(tx ->
        {
            UsersRepository repo = new UsersRepository(tx);
            LocalDate today = today();

            return repo.byEmail(email)
                    .compose(existing -> existing != null ? Future.failedFuture(emailTaken()) : checkManager(repo, manager, null))
                    .compose(v -> repo.insertUser(email, name, uuid(role.getString("id")), text(body, "title"),
                                    text(body, "department"), body.getString("level"))
                            .recover(e -> Future.failedFuture(isUnique(e) ? emailTaken() : e)))
                    .compose(row ->
                    {
                        UUID id = uuid(row.getString("id"));
                        boolean profile = isEmployee(role);

                        Future<?> chain = profile
                                ? repo.insertProfile(id, name, text(body, "title"), text(body, "department"),
                                        body.getString("level"), today)
                                : Future.succeededFuture();

                        return chain
                                .compose(x -> manager == null ? Future.succeededFuture(0) : repo.openNewLine(id, uuid(manager), today))
                                .compose(x -> PasswordService.issueInvite(tx, AuthSettings.from(ctx.config()), id.toString(),
                                        user.getString("id"), request.getString("sourceIp")))
                                .compose(x -> AuditService.record(tx, AuditService.entry(user, AuditEvents.USER_CREATED,
                                        "user", id.toString(), new JsonObject()
                                                .put("roleId", role.getString("id"))
                                                .put("role", role.getString("name"))
                                                .put("managerId", manager)
                                                .put("profile", profile))))
                                .compose(x -> repo.byId(id));
                    });
        })).map(row -> decorate(user, row));
    }

    // ------------------------------------------------------------------ edit

    /**
     * Edits name, role, manager, title, department, level and status (never the email). Role and manager
     * changes are audited; a manager change is effective-dated; deactivation and a role change end the
     * user's sessions.
     */
    Future<JsonObject> update(JsonObject user, UUID id, JsonObject body, int expectedVersion, JsonObject request)
    {
        boolean all = PolicyService.permissions(user).contains("users.all");

        return policy.refuseSelf(user, ACTION_EDIT, null, id.toString(), request)
                .compose(v -> manageable(user, id))
                .compose(target ->
                {
                    String managerId = body.getString("managerId");
                    boolean managerSent = body.containsKey("managerId");

                    if (!all && managerSent && !Objects.equals(lower(managerId), lower(target.getString("managerId"))))
                    {
                        return refuseLimit(user, "manager_not_allowed", "Only Admin can change a user's manager.", id);
                    }

                    String roleId = body.getString("roleId", target.getString("roleId"));

                    return resolveRole(user, all, roleId, id).compose(role -> apply(user, target, role, body, expectedVersion));
                })
                .map(row -> decorate(user, row));
    }

    private Future<JsonObject> apply(JsonObject user, JsonObject target, JsonObject role, JsonObject body, int expectedVersion)
    {
        UUID id = uuid(target.getString("id"));

        String name = body.getString("name") != null ? body.getString("name").strip() : target.getString("name");

        if (name.isEmpty())
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "Enter a name.").field("name"));
        }

        JsonObject next = new JsonObject()
                .put("name", name)
                .put("title", body.containsKey("title") ? text(body, "title") : target.getString("title"))
                .put("department", body.containsKey("department") ? text(body, "department") : target.getString("department"))
                .put("level", body.getString("level", target.getString("level")))
                .put("status", body.getString("status", target.getString("status")))
                .put("roleId", role.getString("id"));

        String managerBefore = target.getString("managerId");
        String managerAfter = body.containsKey("managerId") ? body.getString("managerId") : managerBefore;

        List<String> changed = new ArrayList<>();

        for (String field : List.of("name", "title", "department", "level", "status", "roleId"))
        {
            if (!Objects.equals(next.getValue(field), target.getValue(field)))
            {
                changed.add(field);
            }
        }

        boolean managerChanged = !Objects.equals(lower(managerBefore), lower(managerAfter));

        if (managerChanged)
        {
            changed.add("managerId");
        }

        boolean roleChanged = changed.contains("roleId");
        boolean deactivated = "Deactivated".equals(next.getString("status")) && !"Deactivated".equals(target.getString("status"));

        return ctx.sql().inTransaction(tx ->
        {
            UsersRepository repo = new UsersRepository(tx);
            LocalDate today = today();

            Future<Void> checks = managerChanged ? checkManager(repo, managerAfter, id) : Future.succeededFuture();

            return checks
                    .compose(v -> repo.updateUser(id, next, expectedVersion))
                    .compose(v -> target.getString("personId") != null
                            ? repo.syncProfile(id, name, next.getString("title"), next.getString("department"), next.getString("level"))
                            : isEmployee(role)
                                    ? repo.insertProfile(id, name, next.getString("title"), next.getString("department"),
                                            next.getString("level"), today).map(1)
                                    : Future.succeededFuture(0))
                    .compose(v -> managerChanged ? changeManager(repo, id, managerAfter, today) : Future.succeededFuture())
                    .compose(v -> roleChanged || deactivated ? AuthService.revokeUser(tx, id.toString()) : Future.succeededFuture(0))
                    .compose(v ->
                    {
                        if (changed.isEmpty())
                        {
                            return Future.succeededFuture();
                        }

                        JsonObject summary = new JsonObject().put("changed", new JsonArray(changed));

                        if (roleChanged)
                        {
                            summary.put("role", new JsonObject()
                                    .put("before", target.getString("role"))
                                    .put("after", role.getString("name")));
                        }

                        if (managerChanged)
                        {
                            summary.put("managerId", new JsonObject().put("before", managerBefore).put("after", managerAfter))
                                    .put("effectiveFrom", today.toString());
                        }

                        return AuditService.record(tx, AuditService.entry(user, AuditEvents.USER_UPDATED, "user",
                                id.toString(), summary));
                    })
                    .compose(v -> deactivated
                            ? AuditService.record(tx, AuditService.entry(user, AuditEvents.USER_DEACTIVATED, "user",
                                    id.toString(), new JsonObject().put("sessionsEnded", true)))
                            : Future.succeededFuture())
                    .compose(v -> repo.byId(id));
        });
    }

    // ------------------------------------------------------------------ invite

    /** Sends an Invited user a new set-password link (409 when the user is not Invited). */
    Future<JsonObject> resendInvite(JsonObject user, UUID id, JsonObject request)
    {
        return manageable(user, id).compose(target -> ctx.sql().inTransaction(tx -> PasswordService.issueInvite(tx,
                        AuthSettings.from(ctx.config()), id.toString(), user.getString("id"), request.getString("sourceIp"))))
                .map(v -> new JsonObject().put("sent", true));
    }

    // ------------------------------------------------------------------ delete

    /**
     * Deletes a user: reports move to the deleter, the account and profile are erased, every module erases
     * its personal data and a deletion record is kept. Resolves to {@code {deleted, reportsReassigned}}.
     */
    Future<JsonObject> delete(JsonObject user, UUID id, JsonObject request)
    {
        return policy.refuseSelf(user, ACTION_DELETE, null, id.toString(), request)
                .compose(v -> manageable(user, id))
                .compose(target -> ctx.sql().inTransaction(tx ->
                {
                    UsersRepository repo = new UsersRepository(tx);
                    LocalDate today = today();
                    String deleter = user.getString("id");
                    JsonObject subject = new JsonObject().put("userId", id.toString()).put("personId", target.getString("personId"));

                    return repo.currentReports(id)
                            .compose(reports ->
                            {
                                Future<Void> chain = Future.succeededFuture();

                                for (Object r : reports)
                                {
                                    UUID report = uuid(((JsonObject) r).getString("id"));

                                    chain = chain.compose(v -> changeManager(repo, report, deleter, today));
                                }

                                return chain.map(reports.size());
                            })
                            .compose(moved -> changeManager(repo, id, null, today)
                                    .compose(v -> repo.eraseAccess(id, target.getString("email")))
                                    .compose(v -> repo.eraseProfile(id))
                                    .compose(v -> repo.eraseUser(id))
                                    .compose(v -> eraseModules(tx, subject))
                                    .compose(v -> repo.recordDeletion(id, target.getString("personId"), today, deleter))
                                    .compose(v -> AuditService.record(tx, AuditService.entry(user, AuditEvents.USER_DELETED,
                                            "user", id.toString(), new JsonObject()
                                                    .put("reportsReassigned", moved)
                                                    .put("reassignedTo", deleter)
                                                    .put("profile", target.getString("personId") != null))))
                                    .map(v -> new JsonObject().put("deleted", true).put("reportsReassigned", moved)));
                }));
    }

    private Future<Void> eraseModules(SqlExecutor tx, JsonObject subject)
    {
        Future<Void> chain = Future.succeededFuture();

        for (PulseModule module : ctx.registry().modules())
        {
            chain = chain.compose(v -> module.erasePersonalData(tx, subject.copy()));
        }

        return chain;
    }

    // ------------------------------------------------------------------ rules

    /** The account when the caller may see it: 403 (audited) when not, 404 for users.all on a missing account. */
    private Future<JsonObject> visible(JsonObject user, UUID id)
    {
        return policy.requireUserVisible(user, id.toString()).compose(scope -> new UsersRepository(ctx.sql()).byId(id)
                .compose(row ->
                {
                    if (row == null)
                    {
                        return Future.failedFuture(scope.all() ? PolicyService.notFound() : PolicyService.noAccess());
                    }

                    return Future.succeededFuture(row);
                }));
    }

    /**
     * The account when the caller may manage it: visible, and for users.team holders an Employee
     * (spec "Role limits for managers"); otherwise 403.
     */
    private Future<JsonObject> manageable(JsonObject user, UUID id)
    {
        boolean all = PolicyService.permissions(user).contains("users.all");

        return visible(user, id).compose(row ->
        {
            if (all || isEmployeeRow(row))
            {
                return Future.succeededFuture(row);
            }

            return refuse(user, id, "not_an_employee").map(row);
        });
    }

    /** users.team holders may only give the built-in Employee role (403, audited). */
    private Future<JsonObject> resolveRole(JsonObject user, boolean all, String roleId, UUID targetId)
    {
        RolesRepository roles = new RolesRepository(ctx.sql());

        if (roleId == null)
        {
            return roles.builtIn(EMPLOYEE).compose(r -> r == null
                    ? Future.failedFuture(ApiException.badRequest("invalid_field", "Choose a role.").field("roleId"))
                    : Future.succeededFuture(r));
        }

        return roles.byId(uuid(roleId)).compose(role ->
        {
            if (role == null)
            {
                return Future.failedFuture(ApiException.badRequest("invalid_field", "Choose a role.").field("roleId"));
            }

            if (!all && !isEmployee(role))
            {
                return refuseLimit(user, "role_not_allowed", MSG_ROLE_NOT_ALLOWED, targetId);
            }

            return Future.succeededFuture(role);
        });
    }

    /** A manager must be a live account other than the user. */
    private static Future<Void> checkManager(UsersRepository repo, String managerId, UUID userId)
    {
        if (managerId == null)
        {
            return Future.succeededFuture();
        }

        if (userId != null && managerId.equalsIgnoreCase(userId.toString()))
        {
            return Future.failedFuture(ApiException.unprocessable("manager_self", "A user can't report to themself.")
                    .field("managerId"));
        }

        return repo.byId(uuid(managerId)).compose(m -> m == null || "Deactivated".equals(m.getString("status"))
                ? Future.failedFuture(ApiException.badRequest("invalid_field", "Choose an active manager.").field("managerId"))
                : Future.succeededFuture());
    }

    /**
     * Effective-dated manager change: the open line ends today (a line that started today or later is
     * replaced), and a new line to {@code managerId} starts today. {@code null} only ends the open line.
     */
    static Future<Void> changeManager(UsersRepository repo, UUID userId, String managerId, LocalDate today)
    {
        return repo.openLine(userId).compose(line ->
        {
            if (line != null && managerId != null && managerId.equalsIgnoreCase(line.getString("managerId")))
            {
                return Future.succeededFuture();
            }

            Future<Integer> close = Future.succeededFuture(0);

            if (line != null)
            {
                UUID lineId = uuid(line.getString("id"));

                close = LocalDate.parse(line.getString("validFrom")).isBefore(today)
                        ? repo.closeLine(lineId, today)
                        : repo.deleteLine(lineId);
            }

            return close.compose(n -> managerId == null
                    ? Future.succeededFuture()
                    : repo.openNewLine(userId, uuid(managerId), today).mapEmpty());
        });
    }

    /** Refuses with 403 {@code no_access} and an {@code access.refused} audit entry. */
    private Future<Void> refuse(JsonObject user, UUID id, String reason)
    {
        JsonObject entry = AuditService.entry(user, AuditEvents.ACCESS_REFUSED, "user", id.toString(),
                new JsonObject().put("domain", "users").put("reason", reason)).put("outcome", "refused");

        return AuditService.record(ctx.sql(), entry).transform(x -> Future.failedFuture(PolicyService.noAccess()));
    }

    /** Refuses a role or manager beyond a users.team holder's limits: 403 with the reason, audited. */
    private <T> Future<T> refuseLimit(JsonObject user, String code, String message, UUID targetId)
    {
        JsonObject entry = AuditService.entry(user, AuditEvents.PERMISSION_REFUSED, "user",
                targetId == null ? null : targetId.toString(),
                new JsonObject().put("required", new JsonArray().add("users.all")).put("reason", code))
                .put("outcome", "refused");

        return AuditService.record(ctx.sql(), entry).transform(x -> Future.failedFuture(ApiException.forbidden(code, message)));
    }

    /** Adds {@code isSelf}, {@code canEdit}, {@code canDelete} and the manager as an object. */
    private static JsonObject decorate(JsonObject user, JsonObject row)
    {
        boolean all = PolicyService.permissions(user).contains("users.all");
        boolean self = row.getString("id").equalsIgnoreCase(user.getString("id"));
        boolean manage = !self && (all || isEmployeeRow(row));

        JsonObject out = row.copy();

        out.remove("roleBuiltIn");
        out.remove("managerName");
        out.put("manager", row.getString("managerId") == null ? null
                : new JsonObject().put("id", row.getString("managerId")).put("name", row.getString("managerName")));
        out.put("isSelf", self);
        out.put("canEdit", manage);
        out.put("canDelete", manage);

        return out;
    }

    private static boolean isEmployee(JsonObject role)
    {
        return EMPLOYEE.equals(role.getString("name")) && Boolean.TRUE.equals(role.getBoolean("builtIn"));
    }

    private static boolean isEmployeeRow(JsonObject row)
    {
        return EMPLOYEE.equals(row.getString("role")) && Boolean.TRUE.equals(row.getBoolean("roleBuiltIn"));
    }

    private static ApiException emailTaken()
    {
        return ApiException.conflict("email_taken", MSG_EMAIL_TAKEN).field("email");
    }

    private static boolean isUnique(Throwable e)
    {
        return e instanceof PgException pg && UNIQUE_VIOLATION.equals(pg.getSqlState());
    }

    private static String text(JsonObject body, String field)
    {
        String v = body.getString(field);

        return v == null || v.isBlank() ? null : v.strip();
    }

    private static String lower(String s)
    {
        return s == null ? null : s.toLowerCase();
    }

    private static UUID uuid(String s)
    {
        return s == null ? null : UUID.fromString(s);
    }

    private static LocalDate today()
    {
        return LocalDate.now(PolicyService.ZONE);
    }
}
