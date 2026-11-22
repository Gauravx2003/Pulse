package com.pulse.modules.imports.types;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/**
 * The team a Team OKR row belongs to. The CSV layout has no team column, so, in order:
 * <ol>
 *   <li>the importer's own team ({@code people.department}, e.g. "Platform");</li>
 *   <li>else the one team their current direct reports belong to (a manager without a profile);</li>
 *   <li>else, for a holder of {@code all.view} (HR / Admin, who may have neither), the organisation's
 *       one team when people belong to a single team.</li>
 * </ol>
 * Confirmed with Track 2 (Romil), who owns the OKR port.
 */
public class ActorTeamRepository extends BaseRepository
{
    /** No team at any step. */
    public static final String NO_TEAM = "noTeam";

    /** The step that decides found more than one team, so the row's team is ambiguous. */
    public static final String SEVERAL_TEAMS = "severalTeams";

    public ActorTeamRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * Resolves to {@code {team}} or, when there is no single team, {@code {team: null, reason, teams}}
     * where {@code reason} is {@link #NO_TEAM} or {@link #SEVERAL_TEAMS} and {@code teams} lists the
     * candidates of the deciding step.
     */
    public Future<JsonObject> teamOf(String userId, boolean allView)
    {
        return queryOne("""
                SELECT (SELECT p.department FROM people p WHERE p.user_id = $1) AS own,
                       (SELECT coalesce(json_agg(DISTINCT p.department), '[]'::json)
                        FROM reporting_lines rl JOIN people p ON p.user_id = rl.user_id
                        WHERE rl.manager_id = $1 AND rl.valid_to IS NULL AND p.department IS NOT NULL) AS reports,
                       (SELECT coalesce(json_agg(DISTINCT p.department), '[]'::json)
                        FROM people p WHERE p.department IS NOT NULL) AS organisation
                """,
                Tuple.of(UUID.fromString(userId)))
                .map(row -> resolve(row.getString("own"), row.getJsonArray("reports"), row.getJsonArray("organisation"), allView));
    }

    /** The rule above over the three candidate lists (pure, for tests). */
    static JsonObject resolve(String own, JsonArray reports, JsonArray organisation, boolean allView)
    {
        if (own != null && !own.isBlank())
        {
            return new JsonObject().put("team", own);
        }

        if (reports.size() == 1)
        {
            return new JsonObject().put("team", reports.getString(0));
        }

        if (reports.size() > 1)
        {
            return new JsonObject().putNull("team").put("reason", SEVERAL_TEAMS).put("teams", reports);
        }

        if (allView && organisation.size() == 1)
        {
            return new JsonObject().put("team", organisation.getString(0));
        }

        if (allView && organisation.size() > 1)
        {
            return new JsonObject().putNull("team").put("reason", SEVERAL_TEAMS).put("teams", organisation);
        }

        return new JsonObject().putNull("team").put("reason", NO_TEAM).put("teams", new JsonArray());
    }
}
