package com.pulse.modules.users;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.ListQuery;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.UserScope;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.UUID;

/**
 * SQL of the Users directory and the user lifecycle (task 5.1): accounts, performance profiles,
 * effective-dated reporting lines and the erasure of a deleted user. Reads of the directory take the
 * {@link UserScope} from the policy service.
 */
final class UsersRepository extends BaseRepository
{
    /** Directory row; the current manager is the open reporting line. */
    private static final String ROW = "SELECT u.id, u.name, u.email, u.title, u.department, "
            + "COALESCE(p.level, u.level) AS level, u.status, u.last_login_at AS \"lastLoginAt\", u.version, "
            + "u.created_at AS \"createdAt\", r.id AS \"roleId\", r.name AS role, r.built_in AS \"roleBuiltIn\", "
            + "p.id AS \"personId\", rl.manager_id AS \"managerId\", m.name AS \"managerName\" "
            + "FROM users u "
            + "LEFT JOIN roles r ON r.id = u.role_id "
            + "LEFT JOIN people p ON p.user_id = u.id "
            + "LEFT JOIN reporting_lines rl ON rl.user_id = u.id AND rl.valid_to IS NULL "
            + "LEFT JOIN users m ON m.id = rl.manager_id";

    UsersRepository(SqlExecutor sql)
    {
        super(sql);
    }

    // ------------------------------------------------------------------ directory

    /** A page of the directory: {@code ?q=} searches name, email and role; filters role and status. */
    Future<JsonObject> page(UserScope scope, MultiMap params)
    {
        ListQuery query = ListQuery.from(ROW)
                .where("u.deleted_at IS NULL")
                .filter("role", "r.name")
                .filter("status", "u.status")
                .search("u.name", "u.email", "r.name")
                .sort("name", "lower(u.name)")
                .sort("email", "lower(u.email)")
                .sort("role", "r.name")
                .sort("status", "u.status")
                .sort("lastLogin", "u.last_login_at")
                .defaultSort("name")
                .tieBreaker("u.id");

        return page(scope.applyTo(query, "u.id"), params);
    }

    /** {@code {admins, managers, employees, invited}} over the visible accounts. */
    Future<JsonObject> counts(UserScope scope)
    {
        var c = scope.condition("u.id");
        String where = c.sql().replace("?", "$1");

        return queryOne("SELECT "
                        + "count(*) FILTER (WHERE r.name = 'Admin' AND r.built_in) AS admins, "
                        + "count(*) FILTER (WHERE r.name = 'Manager' AND r.built_in) AS managers, "
                        + "count(*) FILTER (WHERE r.name = 'Employee' AND r.built_in) AS employees, "
                        + "count(*) FILTER (WHERE u.status = 'Invited') AS invited "
                        + "FROM users u LEFT JOIN roles r ON r.id = u.role_id WHERE u.deleted_at IS NULL AND " + where,
                c.values().isEmpty() ? Tuple.tuple() : Tuple.of(c.values().get(0)));
    }

    /** One live (not deleted) account as a directory row; null when there is none. */
    Future<JsonObject> byId(UUID id)
    {
        return queryOne(ROW + " WHERE u.id = $1 AND u.deleted_at IS NULL", Tuple.of(id));
    }

    Future<JsonObject> byEmail(String email)
    {
        return queryOne("SELECT id FROM users WHERE lower(email) = lower($1)", Tuple.of(email));
    }

    /** Live, non-deactivated accounts whose role holds team.view (or is Admin): the "Reports to" options. */
    Future<JsonArray> managerOptions()
    {
        return queryList("SELECT u.id, u.name FROM users u JOIN roles r ON r.id = u.role_id "
                + "WHERE u.deleted_at IS NULL AND u.status <> 'Deactivated' AND (r.name = 'Admin' AND r.built_in "
                + "OR EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = r.id AND rp.permission = 'team.view')) "
                + "ORDER BY lower(u.name)", Tuple.tuple());
    }

    /** Live accounts whose open reporting line points to {@code managerId}. */
    Future<JsonArray> currentReports(UUID managerId)
    {
        return queryList("SELECT rl.user_id AS id FROM reporting_lines rl JOIN users u ON u.id = rl.user_id "
                + "WHERE rl.manager_id = $1 AND rl.valid_to IS NULL AND u.deleted_at IS NULL", Tuple.of(managerId));
    }

    // ------------------------------------------------------------------ writes

    Future<JsonObject> insertUser(String email, String name, UUID roleId, String title, String department, String level)
    {
        return queryOne("INSERT INTO users (email, name, role_id, title, department, level, status) "
                        + "VALUES ($1, $2, $3, $4, $5, $6, 'Invited') RETURNING id",
                Tuple.of(email, name, roleId, title, department, level));
    }

    /** Edits an account under the optimistic lock; the email never changes here. */
    Future<JsonObject> updateUser(UUID id, JsonObject v, int expectedVersion)
    {
        return updateVersioned("users", "name = $1, title = $2, department = $3, level = $4, status = $5, role_id = $6",
                Tuple.of(v.getString("name"), v.getString("title"), v.getString("department"), v.getString("level"),
                        v.getString("status"), v.getString("roleId") == null ? null : UUID.fromString(v.getString("roleId"))),
                id, expectedVersion);
    }

    /** Creates the performance profile of an Employee. */
    Future<JsonObject> insertProfile(UUID userId, String name, String title, String department, String level,
                                     LocalDate joinedOn)
    {
        return queryOne("INSERT INTO people (user_id, name, title, department, level, joined_on, source, external_id) "
                        + "VALUES ($1, $2, $3, $4, $5, $6, 'app', $7) RETURNING id",
                Tuple.of(userId, name, title, department, level, joinedOn, userId.toString()));
    }

    /** Keeps the profile's name, title, department and level in step with the account. */
    Future<Integer> syncProfile(UUID userId, String name, String title, String department, String level)
    {
        return execute("UPDATE people SET name = $2, title = $3, department = $4, level = $5, updated_at = now(), "
                        + "version = version + 1 WHERE user_id = $1",
                Tuple.of(userId, name, title, department, level));
    }

    // ------------------------------------------------------------------ reporting lines

    /** The open reporting line of an account: {@code {id, managerId, validFrom}} or null. */
    Future<JsonObject> openLine(UUID userId)
    {
        return queryOne("SELECT id, manager_id AS \"managerId\", valid_from AS \"validFrom\" FROM reporting_lines "
                + "WHERE user_id = $1 AND valid_to IS NULL", Tuple.of(userId));
    }

    Future<Integer> closeLine(UUID lineId, LocalDate validTo)
    {
        return execute("UPDATE reporting_lines SET valid_to = $2 WHERE id = $1", Tuple.of(lineId, validTo));
    }

    /** Removes a line that had not taken effect before today (replaced the same day). */
    Future<Integer> deleteLine(UUID lineId)
    {
        return execute("DELETE FROM reporting_lines WHERE id = $1", Tuple.of(lineId));
    }

    Future<Integer> openNewLine(UUID userId, UUID managerId, LocalDate validFrom)
    {
        return execute("INSERT INTO reporting_lines (user_id, manager_id, valid_from) VALUES ($1, $2, $3)",
                Tuple.of(userId, managerId, validFrom));
    }

    // ------------------------------------------------------------------ deletion

    /**
     * Erases the account's personal data (spec privacy-retention): name, email, password, title,
     * department, level and last login. The row and its id stay as an opaque pseudonymous id.
     */
    Future<Integer> eraseUser(UUID id)
    {
        return execute("UPDATE users SET name = 'Deleted user', email = id::text || '@deleted.invalid', "
                + "password_hash = NULL, title = NULL, department = NULL, level = NULL, role_id = NULL, "
                + "status = 'Deactivated', last_login_at = NULL, deleted_at = now(), updated_at = now(), "
                + "version = version + 1 WHERE id = $1", Tuple.of(id));
    }

    /** Erases the performance profile's personal data; scores keyed by its id stay. */
    Future<Integer> eraseProfile(UUID userId)
    {
        return execute("UPDATE people SET name = 'Deleted user', title = NULL, department = NULL, ctc = NULL, "
                + "source = 'deleted', external_id = NULL, synced_at = NULL, updated_at = now(), version = version + 1 "
                + "WHERE user_id = $1", Tuple.of(userId));
    }

    /** Sign-in material and messages addressed to the user: sessions, links, lockout row, notifications, outbox. */
    Future<Void> eraseAccess(UUID userId, String email)
    {
        return execute("DELETE FROM sessions WHERE user_id = $1", Tuple.of(userId))
                .compose(n -> execute("DELETE FROM password_tokens WHERE user_id = $1", Tuple.of(userId)))
                .compose(n -> execute("DELETE FROM notifications WHERE user_id = $1", Tuple.of(userId)))
                .compose(n -> execute("DELETE FROM login_attempts WHERE email = lower($1)", Tuple.of(email)))
                .compose(n -> execute("DELETE FROM outbox WHERE lower(recipient) = lower($1)", Tuple.of(email)))
                .mapEmpty();
    }

    Future<Integer> recordDeletion(UUID userId, String personId, LocalDate deletedOn, String deletedBy)
    {
        return execute("INSERT INTO user_deletions (user_id, person_id, deleted_on, deleted_by) VALUES ($1, $2, $3, $4)",
                Tuple.of(userId, personId == null ? null : UUID.fromString(personId), deletedOn,
                        deletedBy == null ? null : UUID.fromString(deletedBy)));
    }
}
