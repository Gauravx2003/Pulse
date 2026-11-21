package com.pulse.modules.imports.types;

import com.pulse.modules.imports.csv.CsvColumns;
import com.pulse.modules.imports.type.ImportType;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Holiday calendar import (spec admin/data-imports-exports, "Holiday calendar import"; task 16.3).
 *
 * <ul>
 *   <li>Columns {@code date} and {@code name}, for holders of {@code attendance.upload} or
 *       {@code rules.manage}.</li>
 *   <li>A date twice in the file is an error; a weekend or past date is a warning; the same name as
 *       the calendar already has is a skipped row; a different name renames the date.</li>
 * </ul>
 */
public class HolidaysImportType implements ImportType
{
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    @Override
    public String id()
    {
        return "holidays";
    }

    @Override
    public String name()
    {
        return "Holiday calendar";
    }

    @Override
    public String feeds()
    {
        return "Working days everywhere: capacity, effort, daily updates and attendance";
    }

    @Override
    public List<String> permissions()
    {
        return List.of("attendance.upload", "rules.manage");
    }

    @Override
    public JsonArray columns()
    {
        return JsonArray.of(
                CsvColumns.required("date", CsvColumns.DATE),
                CsvColumns.required("name", CsvColumns.TEXT, "holiday", "holiday_name", "occasion"));
    }

    @Override
    public JsonArray modes()
    {
        return JsonArray.of(new JsonObject().put("key", "upsert").put("label", "Add new and rename existing dates"));
    }

    @Override
    public JsonObject templateExample()
    {
        return new JsonObject().put("date", "2026-12-25").put("name", "Christmas");
    }

    @Override
    public List<String> targetTables()
    {
        return List.of("holidays");
    }

    @Override
    public Future<JsonArray> check(JsonArray rows, String mode, JsonObject actor, SqlExecutor sql, PolicyService policy)
    {
        Set<LocalDate> dates = new LinkedHashSet<>();

        for (Object item : rows)
        {
            String date = ((JsonObject) item).getJsonObject("values").getString("date");

            if (date != null)
            {
                dates.add(LocalDate.parse(date));
            }
        }

        return new HolidayRepository(sql).onDates(dates.toArray(new LocalDate[0])).map(stored ->
        {
            Map<String, JsonObject> existing = new HashMap<>();

            for (Object item : stored)
            {
                existing.put(((JsonObject) item).getString("holiday_on"), (JsonObject) item);
            }

            LocalDate today = LocalDate.now(PolicyService.ZONE);

            Map<String, Integer> firstLine = new HashMap<>();

            for (Object item : rows)
            {
                checkRow((JsonObject) item, existing, firstLine, today);
            }

            return rows;
        });
    }

    private static void checkRow(JsonObject row, Map<String, JsonObject> existing, Map<String, Integer> firstLine, LocalDate today)
    {
        if (!row.getJsonArray("errors").isEmpty())
        {
            return;
        }

        int line = row.getInteger("line");

        JsonObject values = row.getJsonObject("values");

        String iso = values.getString("date");

        Integer earlier = firstLine.putIfAbsent(iso, line);

        if (earlier != null)
        {
            row.getJsonArray("errors").add(message(line, "date", "Same date as line " + earlier + "."));

            return;
        }

        LocalDate date = LocalDate.parse(iso);

        JsonObject current = existing.get(iso);

        boolean weekend = date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY;

        if (weekend)
        {
            String day = date.getDayOfWeek() == DayOfWeek.SATURDAY ? "Saturday" : "Sunday";

            row.getJsonArray("warnings").add(message(line, "date",
                    DISPLAY.format(date) + " is a " + day + " — kept, no effect on working days."));
        }
        else if (current == null && !date.isAfter(today))
        {
            row.getJsonArray("warnings").add(message(line, "date",
                    "Past date — capacity and attendance for " + DISPLAY.format(date) + " are recalculated."));
        }

        if (current == null)
        {
            row.put("action", "new");
        }
        else if (current.getString("name").equals(values.getString("name")))
        {
            row.put("action", "unchanged");
        }
        else
        {
            row.put("action", "update").put("targetId", current.getString("id"));
        }
    }

    @Override
    public Future<JsonObject> commit(JsonArray rows, String mode, JsonObject actor, SqlExecutor tx, PolicyService policy)
    {
        HolidayRepository holidays = new HolidayRepository(tx);

        JsonArray changes = new JsonArray();

        Future<Void> chain = Future.succeededFuture();

        for (Object item : rows)
        {
            JsonObject row = (JsonObject) item;

            JsonObject values = row.getJsonObject("values");

            LocalDate date = LocalDate.parse(values.getString("date"));

            String name = values.getString("name");

            if ("new".equals(row.getString("action")))
            {
                chain = chain.compose(v -> holidays.insert(date, name).map(added ->
                {
                    changes.add(change("insert", added.getString("id"), null, added.getInteger("version")));

                    return null;
                }));
            }
            else
            {
                UUID id = UUID.fromString(row.getString("targetId"));

                chain = chain.compose(v -> holidays.lock(id).compose(before -> holidays.rename(id, name).map(after ->
                {
                    changes.add(change("update", id.toString(), before, after.getInteger("version")));

                    return null;
                })));
            }
        }

        return chain.map(v -> new JsonObject()
                .put("imported", rows.size())
                .put("changes", changes)
                .put("notes", JsonArray.of("Working days are recalculated with the new calendar.")));
    }

    @Override
    public Future<JsonArray> exportRows(JsonObject actor, JsonObject query, SqlExecutor sql, PolicyService policy)
    {
        return new HolidayRepository(sql).exportAll();
    }

    private static JsonObject change(String action, String id, JsonObject before, Integer afterVersion)
    {
        return new JsonObject()
                .put("table", "holidays")
                .put("id", id)
                .put("action", action)
                .put("before", before)
                .put("afterVersion", afterVersion);
    }

    private static JsonObject message(int line, String column, String text)
    {
        return new JsonObject().put("line", line).put("column", column).put("message", text);
    }
}
