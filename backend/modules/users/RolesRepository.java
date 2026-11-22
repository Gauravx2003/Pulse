package com.pulse.modules.users;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.Collection;
import java.util.UUID;

/** SQL of Roles &amp; permissions (task 5.1): roles, their grants and how many live users hold each. */
final class RolesRepository extends BaseRepository
{
    private static final String ROLE = "SELECT r.id, r.name, r.description, r.built_in AS \"builtIn\", r.version, "
            + "(SELECT count(*) FROM users u WHERE u.role_id = r.id AND u.deleted_at IS NULL) AS \"userCount\", "
            + "COALESCE((SELECT array_agg(p.permission ORDER BY p.permission) FROM role_permissions p "
            + "WHERE p.role_id = r.id), '{}') AS permissions FROM roles r";

    RolesRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** Built-in roles first (Admin, Manager, Employee), then custom roles by name. */
    Future<JsonArray> all()
    {
        return queryList(ROLE + " ORDER BY r.built_in DESC, CASE r.name WHEN 'Admin' THEN 0 WHEN 'Manager' THEN 1 "
                + "WHEN 'Employee' THEN 2 ELSE 3 END, lower(r.name)", Tuple.tuple());
    }

    Future<JsonObject> byId(UUID id)
    {
        return queryOne(ROLE + " WHERE r.id = $1", Tuple.of(id));
    }

    /** The built-in role of that name (Admin, Manager, Employee). */
    Future<JsonObject> builtIn(String name)
    {
        return queryOne(ROLE + " WHERE r.name = $1 AND r.built_in", Tuple.of(name));
    }

    Future<JsonObject> byName(String name)
    {
        return queryOne("SELECT id FROM roles WHERE lower(name) = lower($1)", Tuple.of(name));
    }

    Future<JsonObject> insert(String name, String description)
    {
        return queryOne("INSERT INTO roles (name, description, built_in) VALUES ($1, $2, false) RETURNING id",
                Tuple.of(name, description));
    }

    Future<Integer> grantAll(UUID roleId, Collection<String> permissions)
    {
        if (permissions.isEmpty())
        {
            return Future.succeededFuture(0);
        }

        return execute("INSERT INTO role_permissions (role_id, permission) SELECT $1, unnest($2::text[]) "
                + "ON CONFLICT DO NOTHING", Tuple.of(roleId, (Object) permissions.toArray(String[]::new)));
    }

    Future<Integer> grant(UUID roleId, String permission)
    {
        return execute("INSERT INTO role_permissions (role_id, permission) VALUES ($1, $2) ON CONFLICT DO NOTHING",
                Tuple.of(roleId, permission));
    }

    Future<Integer> revoke(UUID roleId, String permission)
    {
        return execute("DELETE FROM role_permissions WHERE role_id = $1 AND permission = $2", Tuple.of(roleId, permission));
    }

    Future<Integer> touch(UUID roleId)
    {
        return execute("UPDATE roles SET updated_at = now(), version = version + 1 WHERE id = $1", Tuple.of(roleId));
    }

    /** Deletes a custom role that no user holds; resolves to the rows deleted. */
    Future<Integer> deleteUnused(UUID roleId)
    {
        return execute("DELETE FROM roles r WHERE r.id = $1 AND NOT r.built_in "
                + "AND NOT EXISTS (SELECT 1 FROM users u WHERE u.role_id = r.id)", Tuple.of(roleId));
    }
}
