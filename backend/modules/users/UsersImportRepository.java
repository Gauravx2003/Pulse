package com.pulse.modules.users;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** What the Users import reads and writes beyond {@link UsersRepository} (task 16.3). */
final class UsersImportRepository extends BaseRepository
{
    UsersImportRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * Live accounts with these e-mails (lower case): {@code [{id, email, name, status, role, roleBuiltIn,
     * teamView, managerId, title, department, level, personId, ctc, version}]}.
     */
    Future<JsonArray> byEmails(String[] emails)
    {
        return queryList("""
                SELECT u.id, lower(u.email) AS email, u.name, u.status, u.role_id AS "roleId", r.name AS role, r.built_in AS "roleBuiltIn",
                       (r.name = 'Admin' AND r.built_in OR EXISTS (SELECT 1 FROM role_permissions rp
                            WHERE rp.role_id = r.id AND rp.permission = 'team.view')) AS "teamView",
                       rl.manager_id AS "managerId", u.title, u.department, u.level, p.id AS "personId", p.ctc, u.version
                FROM users u
                LEFT JOIN roles r ON r.id = u.role_id
                LEFT JOIN reporting_lines rl ON rl.user_id = u.id AND rl.valid_to IS NULL
                LEFT JOIN people p ON p.user_id = u.id
                WHERE lower(u.email) = ANY($1) AND u.deleted_at IS NULL
                """,
                Tuple.of((Object) emails));
    }

    /** The built-in roles by name: {@code [{id, name}]}. */
    Future<JsonArray> builtInRoles()
    {
        return queryList("SELECT id, name FROM roles WHERE built_in", Tuple.tuple());
    }

    /** The full rows an update may change, for the before-image: {@code {user, person, line}}. */
    Future<JsonObject> snapshot(UUID userId)
    {
        return queryOne("""
                SELECT to_jsonb(u) AS "user", to_jsonb(p) AS person,
                       (SELECT to_jsonb(rl) FROM reporting_lines rl WHERE rl.user_id = u.id AND rl.valid_to IS NULL) AS line
                FROM users u LEFT JOIN people p ON p.user_id = u.id WHERE u.id = $1 FOR UPDATE OF u
                """,
                Tuple.of(userId));
    }

    /** Edits an account from the import; resolves to {@code {version}}. */
    Future<JsonObject> updateUser(UUID id, String name, UUID roleId, String title, String department, String level, String status)
    {
        return queryOne("""
                UPDATE users SET name = $2, role_id = $3, title = $4, department = $5, level = $6, status = $7, updated_at = now(),
                       version = version + 1
                WHERE id = $1 RETURNING version
                """,
                Tuple.tuple().addValue(id).addValue(name).addValue(roleId).addValue(title).addValue(department).addValue(level)
                        .addValue(status));
    }

    Future<Integer> setCtc(UUID userId, BigDecimal ctc)
    {
        return execute("UPDATE people SET ctc = $2, updated_at = now(), version = version + 1 WHERE user_id = $1", Tuple.of(userId, ctc));
    }

    Future<JsonObject> version(UUID userId)
    {
        return queryOne("SELECT version FROM users WHERE id = $1", Tuple.of(userId));
    }

    // ------------------------------------------------------------------ undo

    /** Removes an account the import created, with its profile, lines, tokens and sessions (cascade). */
    Future<Void> removeCreated(UUID userId)
    {
        // Lines to or from the account: a report created in the same import may still point to it.
        return execute("DELETE FROM reporting_lines WHERE user_id = $1 OR manager_id = $1", Tuple.of(userId))
                .compose(n -> execute("DELETE FROM people WHERE user_id = $1", Tuple.of(userId)))
                .compose(n -> execute("DELETE FROM users WHERE id = $1", Tuple.of(userId)))
                .mapEmpty();
    }

    /** Puts an updated account, its profile and its reporting lines back as they were before the import. */
    Future<Void> restore(JsonObject before)
    {
        JsonObject user = before.getJsonObject("user");

        UUID id = UUID.fromString(user.getString("id"));

        Future<Integer> users = execute("""
                UPDATE users u SET (name, role_id, title, department, level, status) =
                       (SELECT b.name, b.role_id, b.title, b.department, b.level, b.status FROM jsonb_populate_record(NULL::users, $2::jsonb) b),
                       updated_at = now(), version = u.version + 1
                WHERE u.id = $1
                """,
                Tuple.of(id, user));

        JsonObject person = before.getJsonObject("person");

        Future<Integer> people = users.compose(n -> person == null ? Future.succeededFuture(0) : execute("""
                UPDATE people p SET (name, title, department, level, ctc) =
                       (SELECT b.name, b.title, b.department, b.level, b.ctc FROM jsonb_populate_record(NULL::people, $2::jsonb) b),
                       updated_at = now(), version = p.version + 1
                WHERE p.user_id = $1
                """,
                Tuple.of(id, person)));

        JsonObject line = before.getJsonObject("line");

        // Lines opened by the import go; the line that was open before is open again.
        return people.compose(n -> execute("DELETE FROM reporting_lines WHERE user_id = $1 AND created_at >= $2",
                        Tuple.of(id, OffsetDateTime.parse(before.getString("at")))))
                .compose(n -> line == null ? Future.succeededFuture(0) : execute("""
                        INSERT INTO reporting_lines SELECT * FROM jsonb_populate_record(NULL::reporting_lines, $1::jsonb)
                        ON CONFLICT (id) DO UPDATE SET valid_to = NULL
                        """,
                        Tuple.of(line)))
                .mapEmpty();
    }

    /** Accounts in the actor's directory scope in the template layout. */
    Future<JsonArray> export(UUID managerId)
    {
        return queryList("""
                SELECT u.name, u.email, r.name AS role, m.email AS manager_email, u.title, u.department, u.level, u.status
                FROM users u
                LEFT JOIN roles r ON r.id = u.role_id
                LEFT JOIN reporting_lines rl ON rl.user_id = u.id AND rl.valid_to IS NULL
                LEFT JOIN users m ON m.id = rl.manager_id
                WHERE u.deleted_at IS NULL AND ($1::uuid IS NULL OR rl.manager_id = $1)
                ORDER BY lower(u.name)
                """,
                Tuple.of(managerId));
    }

    /** The transaction's start time: rows the import inserts carry it as {@code created_at}. */
    Future<JsonObject> now()
    {
        return queryOne("SELECT now() AS at", Tuple.tuple());
    }
}
