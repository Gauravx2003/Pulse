package com.pulse.modules.users;

import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.auth.PermissionCatalogue;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.nav.Navigation;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Roles &amp; permissions (task 5.1, spec admin/users-roles "Permission matrix", "Custom roles", spec
 * rbac-visibility "Permission changes take effect immediately"). Permissions are read from the database
 * on every request, so a toggle applies to everyone holding the role from their next request without
 * signing in again. The built-in Admin role always holds every permission and cannot be changed.
 */
final class RolesService
{
    static final String ADMIN = "Admin";

    private static final String UNIQUE_VIOLATION = "23505";

    private final ModuleContext ctx;

    private final PermissionCatalogue catalogue;

    private final Navigation navigation;

    RolesService(ModuleContext ctx)
    {
        this.ctx = ctx;
        this.catalogue = PermissionCatalogue.of(ctx.registry());
        this.navigation = Navigation.of(ctx.registry());
    }

    /** {@code {areas, permissions, roles}}: the matrix, user counts and the menu each role produces. */
    Future<JsonObject> matrix()
    {
        return new RolesRepository(ctx.sql()).all().map(rows ->
        {
            JsonArray roles = new JsonArray();

            rows.forEach(r -> roles.add(view((JsonObject) r)));

            Set<String> areas = new LinkedHashSet<>();

            catalogue.toJson().forEach(p -> areas.add(((JsonObject) p).getString("area")));

            return new JsonObject()
                    .put("areas", new JsonArray(List.copyOf(areas)))
                    .put("permissions", catalogue.toJson())
                    .put("roles", roles);
        });
    }

    /** Creates a custom role with the permissions of an existing role. */
    Future<JsonObject> create(JsonObject user, String name, UUID fromRoleId, String description)
    {
        String trimmed = name.strip();

        if (trimmed.isEmpty())
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "Enter a role name.").field("name"));
        }

        return ctx.sql().inTransaction(tx ->
        {
            RolesRepository repo = new RolesRepository(tx);

            return repo.byId(fromRoleId).compose(source ->
            {
                if (source == null)
                {
                    return Future.failedFuture(ApiException.badRequest("invalid_field", "Choose a role to start from.")
                            .field("fromRoleId"));
                }

                return repo.byName(trimmed).compose(existing ->
                {
                    if (existing != null)
                    {
                        return Future.failedFuture(nameTaken());
                    }

                    Set<String> permissions = effective(source);

                    return repo.insert(trimmed, description == null ? "" : description.strip())
                            .recover(e -> Future.failedFuture(isUnique(e) ? nameTaken() : e))
                            .compose(row ->
                            {
                                UUID id = UUID.fromString(row.getString("id"));

                                return repo.grantAll(id, permissions)
                                        .compose(n -> AuditService.record(tx, AuditService.entry(user,
                                                AuditEvents.ROLE_CREATED, "role", id.toString(), new JsonObject()
                                                        .put("name", trimmed)
                                                        .put("fromRoleId", fromRoleId.toString())
                                                        .put("permissions", new JsonArray(List.copyOf(permissions))))))
                                        .compose(v -> repo.byId(id));
                            });
                });
            });
        }).map(this::view);
    }

    /**
     * Grants or revokes one permission of a role; audited with the permission sets before and after.
     * Admin is locked (422 {@code admin_locked}); an unknown permission answers 404.
     */
    Future<JsonObject> toggle(JsonObject user, UUID roleId, String permission, boolean granted)
    {
        if (!catalogue.contains(permission))
        {
            return Future.failedFuture(PolicyService.notFound());
        }

        return ctx.sql().inTransaction(tx ->
        {
            RolesRepository repo = new RolesRepository(tx);

            return repo.byId(roleId).compose(role ->
            {
                if (role == null)
                {
                    return Future.failedFuture(PolicyService.notFound());
                }

                if (isAdmin(role))
                {
                    return Future.failedFuture(ApiException.unprocessable("admin_locked",
                            "Admin always keeps every permission."));
                }

                Set<String> before = stored(role);

                if (before.contains(permission) == granted)
                {
                    return Future.succeededFuture(role);
                }

                Set<String> after = new LinkedHashSet<>(before);

                if (granted)
                {
                    after.add(permission);
                }
                else
                {
                    after.remove(permission);
                }

                Future<Integer> change = granted ? repo.grant(roleId, permission) : repo.revoke(roleId, permission);

                return change
                        .compose(n -> repo.touch(roleId))
                        .compose(n -> AuditService.record(tx, AuditService.entry(user, AuditEvents.ROLE_PERMISSIONS_CHANGED,
                                "role", roleId.toString(), new JsonObject()
                                        .put("role", role.getString("name"))
                                        .put("permission", permission)
                                        .put("granted", granted)
                                        .put("before", new JsonArray(before.stream().sorted().toList()))
                                        .put("after", new JsonArray(after.stream().sorted().toList())))))
                        .compose(v -> repo.byId(roleId));
            });
        }).map(this::view);
    }

    /** Deletes a custom role that no user holds (422 otherwise). */
    Future<Void> delete(JsonObject user, UUID roleId)
    {
        return ctx.sql().inTransaction(tx ->
        {
            RolesRepository repo = new RolesRepository(tx);

            return repo.byId(roleId).compose(role ->
            {
                if (role == null)
                {
                    return Future.failedFuture(PolicyService.notFound());
                }

                if (role.getBoolean("builtIn"))
                {
                    return Future.failedFuture(ApiException.unprocessable("role_built_in",
                            "Built-in roles can't be deleted."));
                }

                long users = role.getLong("userCount");

                if (users > 0)
                {
                    return Future.failedFuture(ApiException.unprocessable("role_in_use",
                                    "This role can't be deleted while " + users + (users == 1 ? " user holds" : " users hold")
                                            + " it. Give them another role first.")
                            .detail("userCount", users));
                }

                return repo.deleteUnused(roleId).compose(n -> n == 0
                        ? Future.failedFuture(ApiException.unprocessable("role_in_use",
                                "This role can't be deleted while a user holds it."))
                        : AuditService.record(tx, AuditService.entry(user, AuditEvents.ROLE_DELETED, "role",
                                roleId.toString(), new JsonObject()
                                        .put("name", role.getString("name"))
                                        .put("permissions", role.getJsonArray("permissions")))));
            });
        });
    }

    // ------------------------------------------------------------------ helpers

    /** {@code {id, name, description, builtIn, locked, userCount, permissions, menu}}. */
    JsonObject view(JsonObject role)
    {
        Set<String> permissions = effective(role);

        return new JsonObject()
                .put("id", role.getString("id"))
                .put("name", role.getString("name"))
                .put("description", role.getString("description"))
                .put("builtIn", role.getBoolean("builtIn"))
                .put("locked", isAdmin(role))
                .put("userCount", role.getLong("userCount"))
                .put("version", role.getInteger("version"))
                .put("permissions", new JsonArray(permissions.stream().sorted().toList()))
                .put("menu", navigation.menuFor(permissions, true));
    }

    /** What the role grants: every catalogue permission for Admin, otherwise its stored grants. */
    Set<String> effective(JsonObject role)
    {
        if (isAdmin(role))
        {
            return new LinkedHashSet<>(catalogue.keys());
        }

        return stored(role);
    }

    static boolean isAdmin(JsonObject role)
    {
        return ADMIN.equals(role.getString("name")) && Boolean.TRUE.equals(role.getBoolean("builtIn"));
    }

    private static Set<String> stored(JsonObject role)
    {
        Set<String> out = new LinkedHashSet<>();
        List<Object> list = new ArrayList<>(role.getJsonArray("permissions", new JsonArray()).getList());

        list.forEach(p -> out.add(String.valueOf(p)));

        return out;
    }

    private static ApiException nameTaken()
    {
        return ApiException.conflict("role_name_taken", "A role with this name already exists.").field("name");
    }

    private static boolean isUnique(Throwable e)
    {
        return e instanceof PgException pg && UNIQUE_VIOLATION.equals(pg.getSqlState());
    }
}
