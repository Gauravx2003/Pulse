package com.pulse.modules.users;

import com.pulse.modules.imports.csv.CsvColumns;
import com.pulse.modules.imports.type.ImportType;
import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.auth.AuthSettings;
import com.pulse.platform.auth.PasswordService;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Users import (spec admin/users-roles "User CSV import and export", admin/data-imports-exports; task
 * 16.3), with the directory's rules ({@link UsersService}, task 5.1) and the prototype's checks
 * ({@code IMPORTERS.users}).
 *
 * <ul>
 *   <li>Columns {@code name, email} and optional {@code role, manager_email, title, department, level,
 *       status, ctc}. Only Admin, Manager and Employee roles (a trainee or intern is an Employee).</li>
 *   <li>users.team holders add and edit only Employees reporting to them: other roles are errors, their
 *       new users report to them, and {@code ctc} is ignored with a warning (users.all only).</li>
 *   <li>A manager may be a new user in the same file; nobody edits their own account here.</li>
 *   <li>New users are Invited and get a set-password link; Employees get a performance profile.</li>
 *   <li>Undo restores the type itself (accounts, profiles and effective-dated reporting lines): a created
 *       account is removed again unless it was used meanwhile, an updated one gets its old values back.</li>
 * </ul>
 */
public class UsersImportType implements ImportType
{
    static final String UPSERT = "upsert";

    static final String NEW_ONLY = "new";

    static final List<String> ROLES = List.of("Admin", "Manager", "Employee");

    private static final Pattern TRAINEE = Pattern.compile("(?i)trainee|intern");

    @Override
    public String id()
    {
        return "users";
    }

    @Override
    public String name()
    {
        return "Users";
    }

    @Override
    public String feeds()
    {
        return "Users, sign-in, the org chart and who sees whom";
    }

    @Override
    public List<String> permissions()
    {
        return List.of("users.all", "users.team");
    }

    @Override
    public JsonArray columns()
    {
        return JsonArray.of(
                CsvColumns.required("name", CsvColumns.TEXT, "full_name"),
                CsvColumns.required("email", CsvColumns.EMAIL, "work_email"),
                CsvColumns.optional("role", CsvColumns.TEXT),
                CsvColumns.optional("manager_email", CsvColumns.EMAIL, "manager", "reports_to"),
                CsvColumns.optional("title", CsvColumns.TEXT),
                CsvColumns.optional("department", CsvColumns.TEXT, "team"),
                CsvColumns.optional("level", CsvColumns.TEXT),
                CsvColumns.optional("status", CsvColumns.TEXT),
                CsvColumns.optional("ctc", CsvColumns.NUMBER));
    }

    @Override
    public JsonArray modes()
    {
        return JsonArray.of(
                new JsonObject().put("key", UPSERT).put("label", "Create new users and update existing ones"),
                new JsonObject().put("key", NEW_ONLY).put("label", "Create new users only"));
    }

    @Override
    public JsonObject templateExample()
    {
        return new JsonObject().put("name", "Ishaan Rao").put("email", "ishaan@pulse.demo").put("role", "Employee")
                .put("manager_email", "rahul@pulse.demo").put("title", "Software Engineer").put("department", "Platform")
                .put("level", "L2").put("status", "").put("ctc", "");
    }

    @Override
    public List<String> targetTables()
    {
        return List.of("users");
    }

    @Override
    public boolean restoresItself()
    {
        return true;
    }

    // ------------------------------------------------------------------ check

    @Override
    public Future<JsonArray> check(JsonArray rows, String mode, JsonObject actor, SqlExecutor sql, PolicyService policy)
    {
        Set<String> emails = new LinkedHashSet<>();

        for (Object item : rows)
        {
            JsonObject v = ((JsonObject) item).getJsonObject("values");

            for (String key : List.of("email", "manager_email"))
            {
                if (v.getString(key) != null)
                {
                    emails.add(v.getString(key));
                }
            }
        }

        UsersImportRepository repo = new UsersImportRepository(sql);

        return Future.all(repo.byEmails(emails.toArray(new String[0])), repo.builtInRoles()).map(all ->
        {
            Map<String, JsonObject> existing = new HashMap<>();

            all.<JsonArray>resultAt(0).forEach(o -> existing.put(((JsonObject) o).getString("email"), (JsonObject) o));

            Map<String, String> roleIds = new HashMap<>();

            all.<JsonArray>resultAt(1).forEach(o -> roleIds.put(((JsonObject) o).getString("name"), ((JsonObject) o).getString("id")));

            Map<String, JsonObject> inFile = new HashMap<>();

            for (Object item : rows)
            {
                JsonObject row = (JsonObject) item;

                if (row.getJsonArray("errors").isEmpty())
                {
                    inFile.putIfAbsent(row.getJsonObject("values").getString("email"), row);
                }
            }

            Map<String, Integer> seen = new HashMap<>();

            boolean admins = false;

            for (Object item : rows)
            {
                JsonObject row = (JsonObject) item;

                checkRow(row, mode, actor, existing, roleIds, inFile, seen);

                admins |= row.containsKey("user") && "Admin".equals(row.getJsonObject("user").getString("role"));
            }

            if (admins)
            {
                for (Object item : rows)
                {
                    JsonObject row = (JsonObject) item;

                    if (row.containsKey("user") && "Admin".equals(row.getJsonObject("user").getString("role")))
                    {
                        warn(row, "role", "Admin rows get every permission, including salaries — double-check them.");
                    }
                }
            }

            return rows;
        });
    }

    private static void checkRow(JsonObject row, String mode, JsonObject actor, Map<String, JsonObject> existing, Map<String, String> roleIds,
                                 Map<String, JsonObject> inFile, Map<String, Integer> seen)
    {
        if (!row.getJsonArray("errors").isEmpty())
        {
            return;
        }

        boolean all = PolicyService.permissions(actor).contains("users.all");

        JsonObject v = row.getJsonObject("values");

        String email = v.getString("email");

        Integer earlier = seen.putIfAbsent(email, row.getInteger("line"));

        if (earlier != null)
        {
            error(row, "email", "Same email as line " + earlier + ".");

            return;
        }

        JsonObject current = existing.get(email);

        // An update without a role keeps the account's role; a new account is an Employee by default.
        String roleText = v.getString("role") != null ? v.getString("role").strip()
                : current != null && current.getString("role") != null ? current.getString("role") : "Employee";

        String role = v.getString("role") == null && current != null ? current.getString("role")
                : ROLES.stream().filter(r -> r.equalsIgnoreCase(roleText)).findFirst().orElse(null);

        if (role == null)
        {
            error(row, "role", "“" + roleText + "” — use " + String.join(", ", ROLES)
                    + (TRAINEE.matcher(roleText).find() ? " (a trainee is an Employee on a training plan)." : "."));

            return;
        }

        if (!all && v.getString("role") != null && !"Employee".equals(role))
        {
            error(row, "role", UsersService.MSG_ROLE_NOT_ALLOWED);

            return;
        }

        String level = v.getString("level") == null ? null : v.getString("level").strip().toUpperCase(Locale.ROOT);

        if (level != null && !UsersService.LEVELS.contains(level))
        {
            error(row, "level", "“" + level + "” — use L1 to L5.");

            return;
        }

        String status = null;

        if (v.getString("status") != null)
        {
            String text = v.getString("status").strip();

            status = UsersService.STATUSES.stream().filter(s -> s.equalsIgnoreCase(text)).findFirst().orElse(null);

            if (status == null)
            {
                error(row, "status", "Use Active, Invited or Deactivated.");

                return;
            }
        }

        // Manager: users.team holders' people report to them; users.all may name anyone live, or a new user in this file.
        String managerEmail = v.getString("manager_email");

        String managerId = null;

        String managerInFile = null;

        if (!all)
        {
            managerId = actor.getString("id");

            if (managerEmail != null && !managerEmail.equalsIgnoreCase(actor.getString("email", "")))
            {
                warn(row, "manager_email", "Set to you — managers add people to their own team only.");
            }
        }
        else if (managerEmail != null)
        {
            JsonObject manager = existing.get(managerEmail);

            if (managerEmail.equals(email))
            {
                error(row, "manager_email", "A user can't report to themself.");

                return;
            }

            if (manager != null && !"Deactivated".equals(manager.getString("status")))
            {
                managerId = manager.getString("id");

                if (!manager.getBoolean("teamView", false))
                {
                    warn(row, "manager_email", manager.getString("name") + " has no team view, so won’t see this person.");
                }
            }
            else if (manager == null && inFile.containsKey(managerEmail))
            {
                managerInFile = managerEmail;
            }
            else
            {
                error(row, "manager_email", manager == null ? "No user with " + managerEmail + "." : manager.getString("name") + " is deactivated.");

                return;
            }
        }

        BigDecimal ctc = null;

        if (v.getValue("ctc") != null)
        {
            if (!all)
            {
                warn(row, "ctc", "ctc ignored — only holders of users.all set salaries.");
            }
            else if (!"Employee".equals(role))
            {
                warn(row, "ctc", "ctc ignored — only Employees have a performance profile.");
            }
            else
            {
                ctc = new BigDecimal(String.valueOf(v.getValue("ctc")));

                if (ctc.signum() < 0)
                {
                    error(row, "ctc", "ctc can't be negative.");

                    return;
                }
            }
        }

        String title = v.getString("title");

        if (title != null && TRAINEE.matcher(title).find() && "Employee".equals(role))
        {
            warn(row, "title", "Trainees are Employees — assign a training plan and mentor after the import.");
        }

        JsonObject user = new JsonObject()
                .put("name", v.getString("name").strip())
                .put("email", email)
                .put("role", role)
                .put("roleId", v.getString("role") == null && current != null ? current.getString("roleId") : roleIds.get(role))
                .put("managerId", managerId)
                .put("managerEmail", managerInFile)
                .put("title", title)
                .put("department", v.getString("department"))
                .put("level", level)
                .put("status", status)
                .put("ctc", ctc == null ? null : ctc.toPlainString());

        if (current == null)
        {
            if ("Deactivated".equals(status))
            {
                error(row, "status", "A new user can’t start deactivated.");

                return;
            }

            row.put("user", user).put("action", "new");

            return;
        }

        if (NEW_ONLY.equals(mode))
        {
            warn(row, "email", current.getString("name") + " already exists — skipped.");

            row.put("action", "unchanged");

            return;
        }

        if (current.getString("id").equals(actor.getString("id")))
        {
            error(row, "email", "You can’t change your own account in an import.");

            return;
        }

        boolean employee = "Employee".equals(current.getString("role")) && current.getBoolean("roleBuiltIn", false);

        if (!all && (!employee || !actor.getString("id").equals(current.getString("managerId"))))
        {
            error(row, "email", "You can’t edit " + current.getString("name") + " — only Employees who report to you.");

            return;
        }

        // An update keeps what the file leaves empty; users.team holders never move people to another manager.
        user.put("id", current.getString("id"))
                .put("managerId", !all || managerEmail == null ? current.getString("managerId") : managerId)
                .put("title", title == null ? current.getString("title") : title)
                .put("department", user.getString("department") == null ? current.getString("department") : user.getString("department"))
                .put("level", level == null ? current.getString("level") : level)
                .put("status", status == null ? current.getString("status") : status)
                .put("ctc", ctc == null ? text(current.getValue("ctc")) : user.getString("ctc"))
                .put("hasProfile", current.getString("personId") != null);

        if ("Invited".equals(user.getString("status")) && !"Invited".equals(current.getString("status")))
        {
            error(row, "status", "An account that has signed in can't go back to Invited.");

            return;
        }

        boolean same = managerInFile == null
                && Objects.equals(current.getString("name"), user.getString("name"))
                && Objects.equals(current.getString("role"), role)
                && Objects.equals(current.getString("managerId"), user.getString("managerId"))
                && Objects.equals(current.getString("title"), user.getString("title"))
                && Objects.equals(current.getString("department"), user.getString("department"))
                && Objects.equals(current.getString("level"), user.getString("level"))
                && Objects.equals(current.getString("status"), user.getString("status"))
                && sameNumber(current.getValue("ctc"), user.getString("ctc"));

        row.put("user", user).put("action", same ? "unchanged" : "update");
    }

    // ------------------------------------------------------------------ commit

    @Override
    public Future<JsonObject> commit(JsonArray rows, String mode, JsonObject actor, SqlExecutor tx, PolicyService policy)
    {
        UsersRepository users = new UsersRepository(tx);

        UsersImportRepository repo = new UsersImportRepository(tx);

        JsonArray changes = new JsonArray();

        Map<String, String> created = new HashMap<>();

        List<JsonObject> fresh = new ArrayList<>();

        List<JsonObject> updates = new ArrayList<>();

        for (Object item : rows)
        {
            JsonObject row = (JsonObject) item;

            ("new".equals(row.getString("action")) ? fresh : updates).add(row.getJsonObject("user"));
        }

        LocalDate today = LocalDate.now(PolicyService.ZONE);

        return repo.now().compose(clock ->
        {
            String at = clock.getString("at");

            Future<Void> chain = Future.succeededFuture();

            // 1. New accounts first, so a manager in the same file exists before their reports' lines.
            for (JsonObject u : fresh)
            {
                chain = chain.compose(v -> users.insertUser(u.getString("email"), u.getString("name"), UUID.fromString(u.getString("roleId")),
                                u.getString("title"), u.getString("department"), u.getString("level"))
                        .compose(added ->
                        {
                            UUID id = UUID.fromString(added.getString("id"));

                            created.put(u.getString("email"), id.toString());

                            Future<Integer> profile = !"Employee".equals(u.getString("role")) ? Future.succeededFuture(0)
                                    : users.insertProfile(id, u.getString("name"), u.getString("title"), u.getString("department"),
                                                    u.getString("level"), today)
                                            .compose(p -> u.getString("ctc") == null ? Future.succeededFuture(0)
                                                    : repo.setCtc(id, new BigDecimal(u.getString("ctc"))));

                            return profile.mapEmpty();
                        }));
            }

            // 2. Their reporting lines, invites and audit entries.
            for (JsonObject u : fresh)
            {
                chain = chain.compose(v ->
                {
                    UUID id = UUID.fromString(created.get(u.getString("email")));

                    String manager = u.getString("managerId") != null ? u.getString("managerId") : created.get(u.getString("managerEmail"));

                    return (manager == null ? Future.succeededFuture(0) : users.openNewLine(id, UUID.fromString(manager), today))
                            .compose(x -> PasswordService.issueInvite(tx, AuthSettings.from(null), id.toString(), actor.getString("id"), null))
                            .compose(x -> AuditService.record(tx, AuditService.entry(actor, AuditEvents.USER_CREATED, "user", id.toString(),
                                    new JsonObject().put("role", u.getString("role")).put("managerId", manager).put("import", true))))
                            .compose(x -> repo.version(id))
                            .map(version ->
                            {
                                changes.add(change(id.toString(), new JsonObject().put("created", true).put("id", id.toString()),
                                        version.getInteger("version")));

                                return (Void) null;
                            });
                });
            }

            // 3. Updates of existing accounts, with a before-image of the account, profile and open line.
            for (JsonObject u : updates)
            {
                UUID id = UUID.fromString(u.getString("id"));

                chain = chain.compose(v -> repo.snapshot(id).compose(before ->
                {
                    String manager = u.getString("managerId") != null ? u.getString("managerId") : created.get(u.getString("managerEmail"));

                    Future<Integer> profile = !u.getBoolean("hasProfile", false) ? Future.succeededFuture(0)
                            : users.syncProfile(id, u.getString("name"), u.getString("title"), u.getString("department"), u.getString("level"))
                                    .compose(n -> u.getString("ctc") == null ? Future.succeededFuture(0)
                                            : repo.setCtc(id, new BigDecimal(u.getString("ctc"))));

                    return repo.updateUser(id, u.getString("name"), UUID.fromString(u.getString("roleId")), u.getString("title"),
                                    u.getString("department"), u.getString("level"), u.getString("status"))
                            .compose(x -> profile)
                            .compose(x -> UsersService.changeManager(users, id, manager, today))
                            .compose(x -> AuditService.record(tx, AuditService.entry(actor, AuditEvents.USER_UPDATED, "user", id.toString(),
                                    new JsonObject().put("role", u.getString("role")).put("managerId", manager).put("import", true))))
                            .compose(x -> !"Deactivated".equals(u.getString("status"))
                                    || "Deactivated".equals(before.getJsonObject("user").getString("status")) ? Future.succeededFuture()
                                    : AuditService.record(tx, AuditService.entry(actor, AuditEvents.USER_DEACTIVATED, "user", id.toString(),
                                            new JsonObject().put("import", true))))
                            .compose(x -> repo.version(id))
                            .map(version ->
                            {
                                changes.add(change(id.toString(), new JsonObject()
                                        .put("created", false)
                                        .put("user", before.getJsonObject("user"))
                                        .put("person", before.getJsonObject("person"))
                                        .put("line", before.getJsonObject("line"))
                                        .put("at", at), version.getInteger("version")));

                                return (Void) null;
                            });
                }));
            }

            return chain.map(v -> new JsonObject()
                    .put("imported", rows.size())
                    .put("changes", changes)
                    .put("notes", JsonArray.of(fresh.size() + " new user" + (fresh.size() == 1 ? "" : "s")
                            + " · each gets an invite email with a set-password link", updates.size() + " updated")));
        });
    }

    @Override
    public Future<Void> undoChange(JsonObject before, JsonObject actor, SqlExecutor tx, PolicyService policy)
    {
        UsersImportRepository repo = new UsersImportRepository(tx);

        if (before.getBoolean("created", false))
        {
            return repo.removeCreated(UUID.fromString(before.getString("id"))).recover(e -> Future.failedFuture(
                    ApiException.conflict("undo_conflict", "A user created by this import is already used elsewhere in Pulse, so the import can't be undone.")));
        }

        return repo.restore(before);
    }

    // ------------------------------------------------------------------ export

    @Override
    public Future<JsonArray> exportRows(JsonObject actor, JsonObject query, SqlExecutor sql, PolicyService policy)
    {
        boolean all = PolicyService.permissions(actor).contains("users.all");

        return new UsersImportRepository(sql).export(all ? null : UUID.fromString(actor.getString("id")));
    }

    // ------------------------------------------------------------------ helpers

    /** Reported as an update so undo receives the before-image; a created account carries {@code created: true}. */
    private static JsonObject change(String id, JsonObject before, Integer afterVersion)
    {
        return new JsonObject().put("table", "users").put("id", id).put("action", "update").put("before", before).put("afterVersion", afterVersion);
    }

    private static boolean sameNumber(Object stored, String text)
    {
        if (stored == null || text == null)
        {
            return stored == null && text == null;
        }

        return new BigDecimal(String.valueOf(stored)).compareTo(new BigDecimal(text)) == 0;
    }

    private static String text(Object value)
    {
        return value == null ? null : String.valueOf(value);
    }

    private static void error(JsonObject row, String column, String message)
    {
        row.getJsonArray("errors").add(new JsonObject().put("line", row.getInteger("line")).put("column", column).put("message", message));
    }

    private static void warn(JsonObject row, String column, String message)
    {
        row.getJsonArray("warnings").add(new JsonObject().put("line", row.getInteger("line")).put("column", column).put("message", message));
    }
}
