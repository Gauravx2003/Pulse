package com.pulse.modules.rules;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.List;
import java.util.UUID;

/**
 * SQL of Rules &amp; thresholds (task 5.2). The rule set in force is the newest row of
 * {@code rule_versions} (read by CalcService); {@code rules} keeps the current value of each top-level
 * rule key with who changed it last. Versions are only ever inserted.
 */
final class RulesRepository extends BaseRepository
{
    private static final String VERSION_COLUMNS = "v.version, v.created_at AS \"at\", v.created_by AS \"actorId\", "
            + "u.name AS \"actorName\", v.reason";

    RulesRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** {@code {version, rules, at}} of the rule set in force; null before the base seed. */
    Future<JsonObject> current()
    {
        return queryOne("SELECT version, rule_set AS rules, created_at AS \"at\" FROM rule_versions "
                + "ORDER BY version DESC LIMIT 1", Tuple.tuple());
    }

    /** Inserts the next rule version; a concurrent save of the same number fails with a unique violation. */
    Future<JsonObject> insertVersion(int version, JsonObject ruleSet, JsonObject diff, String actorId, String reason)
    {
        return queryOne("INSERT INTO rule_versions (version, rule_set, diff, created_by, reason) "
                        + "VALUES ($1, $2, $3, $4, $5) RETURNING version, created_at AS \"at\"",
                Tuple.of(version, ruleSet, diff, actorId == null ? null : UUID.fromString(actorId), reason));
    }

    /** Stores the current value of a top-level rule key and who changed it. */
    Future<Integer> upsertRule(String key, String group, Object value, String actorId)
    {
        return execute("INSERT INTO rules AS r (key, rule_group, value, updated_by) VALUES ($1, $2, $3::jsonb, $4) "
                        + "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now(), "
                        + "updated_by = EXCLUDED.updated_by, version = r.version + 1",
                Tuple.of(key, group, Json.encode(value),
                        actorId == null ? null : UUID.fromString(actorId)));
    }

    /** The newest version whose diff touches one of {@code paths}, with its actor; null when none has. */
    Future<JsonObject> lastChange(List<String> paths)
    {
        return queryOne("SELECT " + VERSION_COLUMNS + " FROM rule_versions v LEFT JOIN users u ON u.id = v.created_by "
                + "WHERE v.diff ?| $1::text[] ORDER BY v.version DESC LIMIT 1", Tuple.of(paths.toArray(String[]::new)));
    }

    /** The newest versions whose diff touches one of {@code paths}, with actor and diff. */
    Future<JsonArray> history(List<String> paths, int limit)
    {
        return queryList("SELECT " + VERSION_COLUMNS + ", v.diff FROM rule_versions v "
                        + "LEFT JOIN users u ON u.id = v.created_by WHERE v.diff ?| $1::text[] "
                        + "ORDER BY v.version DESC LIMIT $2",
                Tuple.of(paths.toArray(String[]::new), limit));
    }
}
