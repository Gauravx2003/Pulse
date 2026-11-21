package com.pulse.modules.imports.types;

import com.pulse.platform.db.BaseRepository;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The organisation-wide holiday calendar ({@code holidays} rows with {@code location IS NULL}, V100)
 * as the Holiday calendar import reads and writes it.
 *
 * <p>The table belongs to Track 1, which has no holiday service (no task builds one), so this is the
 * only code that writes it from a CSV file. Rows added by an import carry {@code source = 'csv'} and
 * the date as {@code external_id}; renaming a seeded date keeps its source.
 */
public class HolidayRepository extends BaseRepository
{
    public HolidayRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** Organisation-wide holidays on these dates: {@code [{id, holiday_on, name, version}]}. */
    public Future<JsonArray> onDates(LocalDate[] dates)
    {
        return queryList("""
                SELECT id, holiday_on, name, version FROM holidays
                WHERE location IS NULL AND holiday_on = ANY($1)
                """,
                Tuple.of(dates));
    }

    /** The full row, locked for the rest of the transaction (the before-image of a rename). */
    public Future<JsonObject> lock(UUID id)
    {
        return queryOne("SELECT * FROM holidays WHERE id = $1 FOR UPDATE", Tuple.of(id));
    }

    /** Adds a holiday; resolves to {@code {id, version}}. */
    public Future<JsonObject> insert(LocalDate date, String name)
    {
        return queryOne("""
                INSERT INTO holidays (holiday_on, name, source, external_id) VALUES ($1, $2, 'csv', $3)
                RETURNING id, version
                """,
                Tuple.of(date, name, date.toString()));
    }

    /** Renames a holiday; resolves to {@code {version}} after the change. */
    public Future<JsonObject> rename(UUID id, String name)
    {
        return queryOne("""
                UPDATE holidays SET name = $2, updated_at = now(), version = version + 1
                WHERE id = $1 RETURNING version
                """,
                Tuple.of(id, name));
    }

    /** Every organisation-wide holiday in the import layout ({@code date, name}), oldest first. */
    public Future<JsonArray> exportAll()
    {
        return queryList("SELECT holiday_on AS date, name FROM holidays WHERE location IS NULL ORDER BY holiday_on",
                Tuple.tuple());
    }
}
