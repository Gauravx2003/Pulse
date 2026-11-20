package com.pulse.modules.okrs;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * SQL for objectives, key results and check-ins (V210). Rows come back as JSON with snake_case keys;
 * an objective carries its key results, ordered by position, under {@code key_results}.
 */
public class OkrRepository extends BaseRepository
{

    private static final String OBJECTIVE_SELECT = """
            SELECT o.id, o.quarter, o.level, o.owner_id, o.team, o.title, o.epic_key, o.culture_value,
                   o.created_by, o.version,
                   coalesce((SELECT json_agg(json_build_object(
                                 'id', k.id, 'position', k.position, 'description', k.description,
                                 'start_value', k.start_value, 'target_value', k.target_value,
                                 'current_value', k.current_value, 'unit', k.unit, 'version', k.version)
                                 ORDER BY k.position)
                             FROM key_results k WHERE k.objective_id = o.id), '[]'::json) AS key_results
            FROM objectives o
            """;

    public OkrRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * One objective with its key results, or {@code null}.
     */
    public Future<JsonObject> findObjective(UUID id)
    {
        return queryOne(OBJECTIVE_SELECT + " WHERE o.id = $1", Tuple.of(id));
    }

    /**
     * The objective a key result belongs to, locked for the rest of the transaction, or {@code null}.
     */
    public Future<JsonObject> lockObjectiveOfKeyResult(UUID keyResultId)
    {
        return queryOne("""
                SELECT o.id, o.level, o.owner_id, o.team
                FROM objectives o JOIN key_results k ON k.objective_id = o.id
                WHERE k.id = $1
                FOR UPDATE OF o""", Tuple.of(keyResultId));
    }

    /**
     * A person's Individual objectives for a quarter, oldest first.
     */
    public Future<JsonArray> listForOwner(UUID ownerId, String quarter)
    {
        return queryList(OBJECTIVE_SELECT + " WHERE o.owner_id = $1 AND o.quarter = $2 ORDER BY o.created_at, o.id",
                Tuple.of(ownerId, quarter));
    }

    /**
     * A team's Team objectives for a quarter, oldest first.
     */
    public Future<JsonArray> listForTeam(String team, String quarter)
    {
        return queryList(OBJECTIVE_SELECT + " WHERE o.level = 'Team' AND o.team = $1 AND o.quarter = $2 "
                + "ORDER BY o.created_at, o.id", Tuple.of(team, quarter));
    }

    /**
     * Inserts an objective and resolves to its id.
     */
    public Future<UUID> insertObjective(JsonObject o, UUID createdBy)
    {
        return queryOne("""
                INSERT INTO objectives (quarter, level, owner_id, team, title, epic_key, culture_value, created_by)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                RETURNING id""",
                Tuple.tuple()
                        .addString(o.getString("quarter"))
                        .addString(o.getString("level"))
                        .addUUID(uuidOrNull(o.getString("owner_id")))
                        .addString(o.getString("team"))
                        .addString(o.getString("title"))
                        .addString(o.getString("epic_key"))
                        .addString(o.getString("culture_value"))
                        .addUUID(createdBy))
                .map(row -> UUID.fromString(row.getString("id")));
    }

    /**
     * Updates the objective's own fields when {@code version} is current; resolves to rows changed (0 = stale).
     */
    public Future<Integer> updateObjective(UUID id, int version, JsonObject o)
    {
        return execute("""
                UPDATE objectives
                SET quarter = $3, title = $4, epic_key = $5, culture_value = $6,
                    updated_at = now(), version = version + 1
                WHERE id = $1 AND version = $2""",
                Tuple.tuple()
                        .addUUID(id)
                        .addInteger(version)
                        .addString(o.getString("quarter"))
                        .addString(o.getString("title"))
                        .addString(o.getString("epic_key"))
                        .addString(o.getString("culture_value")));
    }

    /**
     * Deletes an objective; its key results and check-ins go with it.
     */
    public Future<Integer> deleteObjective(UUID id)
    {
        return execute("DELETE FROM objectives WHERE id = $1", Tuple.of(id));
    }

    public Future<Integer> insertKeyResult(UUID objectiveId, int position, JsonObject kr)
    {
        return execute("""
                INSERT INTO key_results (objective_id, position, description, start_value, target_value,
                                         current_value, unit)
                VALUES ($1, $2, $3, $4, $5, $6, $7)""",
                Tuple.tuple()
                        .addUUID(objectiveId)
                        .addShort((short) position)
                        .addString(kr.getString("description"))
                        .addBigDecimal(decimal(kr, "start_value"))
                        .addBigDecimal(decimal(kr, "target_value"))
                        .addBigDecimal(decimal(kr, "current_value"))
                        .addString(kr.getString("unit")));
    }

    public Future<Integer> updateKeyResult(UUID keyResultId, int position, JsonObject kr)
    {
        return execute("""
                UPDATE key_results
                SET position = $2, description = $3, start_value = $4, target_value = $5, current_value = $6,
                    unit = $7, updated_at = now(), version = version + 1
                WHERE id = $1""",
                Tuple.tuple()
                        .addUUID(keyResultId)
                        .addShort((short) position)
                        .addString(kr.getString("description"))
                        .addBigDecimal(decimal(kr, "start_value"))
                        .addBigDecimal(decimal(kr, "target_value"))
                        .addBigDecimal(decimal(kr, "current_value"))
                        .addString(kr.getString("unit")));
    }

    public Future<Integer> deleteKeyResult(UUID keyResultId)
    {
        return execute("DELETE FROM key_results WHERE id = $1", Tuple.of(keyResultId));
    }

    /**
     * Records a check-in and moves the key result's current value; resolves to the updated key result.
     * Run it inside a transaction: the key result stays locked from the read to the update.
     */
    public Future<JsonObject> checkIn(UUID keyResultId, BigDecimal value, String note, String via, UUID by)
    {
        return queryOne("SELECT current_value FROM key_results WHERE id = $1 FOR UPDATE", Tuple.of(keyResultId))
                .compose(previous -> execute("""
                        INSERT INTO key_result_checkins (key_result_id, previous_value, new_value, note, via, checked_in_by)
                        VALUES ($1, $2, $3, $4, $5, $6)""",
                        Tuple.tuple()
                                .addUUID(keyResultId)
                                .addBigDecimal(new BigDecimal(previous.getValue("current_value").toString()))
                                .addBigDecimal(value)
                                .addString(note)
                                .addString(via)
                                .addUUID(by)))
                .compose(logged -> queryOne("""
                        UPDATE key_results
                        SET current_value = $2, updated_at = now(), version = version + 1
                        WHERE id = $1
                        RETURNING id, objective_id, position, description, start_value, target_value,
                                  current_value, unit, version""",
                        Tuple.of(keyResultId, value)));
    }

    /**
     * Check-in history of a key result, newest first.
     */
    public Future<JsonArray> checkIns(UUID keyResultId)
    {
        return queryList("""
                SELECT id, previous_value, new_value, note, via, checked_in_by, checked_in_at
                FROM key_result_checkins WHERE key_result_id = $1
                ORDER BY checked_in_at DESC, id""", Tuple.of(keyResultId));
    }

    private static BigDecimal decimal(JsonObject kr, String key)
    {
        Object value = kr.getValue(key);

        return value == null ? null : new BigDecimal(value.toString());
    }

    private static UUID uuidOrNull(String value)
    {
        return value == null ? null : UUID.fromString(value);
    }
}
