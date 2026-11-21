package com.pulse.modules.imports.types;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/**
 * The team a Team OKR row belongs to. The CSV layout has no team column, so it is the importer's own
 * team ({@code people.department}, e.g. "Platform"); a manager without a performance profile gets
 * the one team their current direct reports belong to. Null when neither gives exactly one team.
 */
public class ActorTeamRepository extends BaseRepository
{
    public ActorTeamRepository(SqlExecutor sql)
    {
        super(sql);
    }

    public Future<String> teamOf(String userId)
    {
        return queryOne("""
                SELECT coalesce(
                    (SELECT p.department FROM people p WHERE p.user_id = $1),
                    (SELECT CASE WHEN count(DISTINCT p.department) = 1 THEN max(p.department) END
                     FROM reporting_lines rl JOIN people p ON p.user_id = rl.user_id
                     WHERE rl.manager_id = $1 AND rl.valid_to IS NULL)) AS team
                """,
                Tuple.of(UUID.fromString(userId)))
                .map(row -> row == null ? null : row.getString("team"));
    }
}
