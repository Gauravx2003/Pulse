package com.pulse.platform.shell;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.List;
import java.util.UUID;

/** Reads behind the app shell (task 6.4): the person selector, the top-bar sync status and the demo cards. */
public final class ShellRepository extends BaseRepository
{
    private static final String PERSON_COLUMNS = "p.id, p.name, p.title, p.department";

    public ShellRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * Every person, by name. Deactivated people are left out unless {@code withDeactivated}
     * (spec platform/privacy-retention: only {@code users.all} holders see them).
     */
    public Future<JsonArray> allPeople(boolean withDeactivated)
    {
        return queryList("SELECT " + PERSON_COLUMNS + " FROM people p LEFT JOIN users u ON u.id = p.user_id "
                + "WHERE ($1 OR u.status IS DISTINCT FROM 'Deactivated') ORDER BY p.name, p.id",
                Tuple.of(withDeactivated));
    }

    /** The people of {@code ids}, by name; the ids come from the policy scope, never from the request. */
    public Future<JsonArray> people(List<UUID> ids, boolean withDeactivated)
    {
        return queryList("SELECT " + PERSON_COLUMNS + " FROM people p LEFT JOIN users u ON u.id = p.user_id "
                + "WHERE p.id = ANY($1) AND ($2 OR u.status IS DISTINCT FROM 'Deactivated') ORDER BY p.name, p.id",
                Tuple.of(ids.toArray(UUID[]::new), withDeactivated));
    }

    /** The newest run of {@code tool}, finished or still running, or null when it never synced. */
    public Future<JsonObject> latestSync(String tool)
    {
        return queryOne("SELECT tool, outcome, started_at, finished_at, simulated FROM sync_log WHERE tool = $1 "
                + "ORDER BY started_at DESC LIMIT 1", Tuple.of(tool));
    }

    /** The newest successful run of {@code tool}, or null. */
    public Future<JsonObject> latestSuccess(String tool)
    {
        return queryOne("SELECT finished_at FROM sync_log WHERE tool = $1 AND outcome = 'success' "
                + "ORDER BY started_at DESC LIMIT 1", Tuple.of(tool));
    }

    /** Active accounts of {@code emails} with their role name, in the order of {@code emails}. */
    public Future<JsonArray> accounts(List<String> emails)
    {
        return queryList("SELECT u.email, u.name, r.name AS role FROM users u JOIN roles r ON r.id = u.role_id "
                + "WHERE lower(u.email) = ANY($1) AND u.status = 'Active' "
                + "ORDER BY array_position($1, lower(u.email))", Tuple.of(emails.toArray(String[]::new)));
    }
}
