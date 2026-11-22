package com.pulse.platform.policy;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.UUID;

/** Reporting-line and person lookups behind {@link PolicyService}. */
final class PolicyRepository extends BaseRepository
{
    PolicyRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** {@code [{person_id}]} of the people whose current line (on {@code today}) reports to the manager. */
    Future<JsonArray> directReports(String managerUserId, LocalDate today)
    {
        return queryList("SELECT p.id AS person_id FROM reporting_lines rl JOIN people p ON p.user_id = rl.user_id "
                        + "WHERE rl.manager_id = $1 AND rl.valid_from <= $2 AND (rl.valid_to IS NULL OR rl.valid_to > $2)",
                Tuple.of(UUID.fromString(managerUserId), today));
    }

    /**
     * {@code [{user_id}]} of the accounts whose current line (on {@code today}) reports to the manager,
     * without deactivated and deleted accounts (Users directory of a users.team holder).
     */
    Future<JsonArray> directReportUsers(String managerUserId, LocalDate today)
    {
        return queryList("SELECT u.id AS user_id FROM reporting_lines rl JOIN users u ON u.id = rl.user_id "
                        + "WHERE rl.manager_id = $1 AND rl.valid_from <= $2 AND (rl.valid_to IS NULL OR rl.valid_to > $2) "
                        + "AND u.status <> 'Deactivated' AND u.deleted_at IS NULL",
                Tuple.of(UUID.fromString(managerUserId), today));
    }

    /** {@code [{person_id}]} returned by a module's visibility relation for the viewer's person id. */
    Future<JsonArray> related(String relationSql, String viewerPersonId)
    {
        return queryList(relationSql, Tuple.of(UUID.fromString(viewerPersonId)));
    }

    /** The person row's id, or null when it does not exist. */
    Future<JsonObject> person(UUID personId)
    {
        return queryOne("SELECT id FROM people WHERE id = $1", Tuple.of(personId));
    }
}
