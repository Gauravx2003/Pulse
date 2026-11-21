package com.pulse.modules.imports.csv;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the parsed header onto an import type's columns and checks every cell (spec
 * platform/csv-import-framework, "Parsing rules"):
 * <ul>
 *   <li>Headers match column keys or aliases ignoring case and punctuation.</li>
 *   <li>A missing required column stops the check and names the column.</li>
 *   <li>Unknown columns are ignored and listed.</li>
 *   <li>Each cell is trimmed and converted by type; a bad cell is an error on that line and column.</li>
 * </ul>
 *
 * <p>Only per-cell rules live here. Rules that need several cells or the database (duplicates,
 * unknown user, overlaps) belong to each import type's own check.
 *
 * <p>Pure and synchronous: run inside {@code vertx.executeBlocking(...)}. Result shape:
 * <pre>{@code
 * {"columns": ["employee_email", ...],          // matched column keys, in file order
 *  "ignoredColumns": ["Notes"],                 // header cells that matched nothing
 *  "rows": [{"line": 2, "values": {"employee_email": "a@x.com", "date": "2026-09-01", ...},
 *            "errors": [{"line": 2, "column": "status", "message": "..."}], "warnings": []}],
 *  "rowsRead": 100, "rowsWithErrors": 3}
 * }</pre>
 */
public final class CsvValidator
{
    private static final Pattern ISO_DATE = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$");

    private static final Pattern DMY_DATE = Pattern.compile("^(\\d{1,2})-(\\d{1,2})-(\\d{4})$");

    private static final Pattern TIME_24H = Pattern.compile("^(\\d{1,2}):(\\d{2})$");

    private static final Pattern EMAIL_ADDRESS = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private static final Pattern INTEGER_VALUE = Pattern.compile("^[+-]?\\d+$");

    private CsvValidator()
    {
    }

    /**
     * @param parsed  output of {@link CsvParser#parse}
     * @param columns column definitions built with {@link CsvColumns}
     * @throws CsvImportException when a required column is missing or two headers map to one column
     */
    public static JsonObject validate(JsonObject parsed, JsonArray columns)
    {
        JsonArray headerCells = parsed.getJsonObject("header").getJsonArray("cells");

        Map<Integer, JsonObject> byPosition = mapHeader(headerCells, columns);

        JsonArray ignored = new JsonArray();

        JsonArray matched = new JsonArray();

        for (int i = 0; i < headerCells.size(); i++)
        {
            JsonObject column = byPosition.get(i);

            if (column != null)
            {
                matched.add(column.getString("key"));
            }
            else if (!headerCells.getString(i).isBlank())
            {
                ignored.add(headerCells.getString(i).trim());
            }
        }

        JsonArray rows = new JsonArray();

        int rowsWithErrors = 0;

        for (Object item : parsed.getJsonArray("rows"))
        {
            JsonObject row = checkRow((JsonObject) item, byPosition, columns);

            if (!row.getJsonArray("errors").isEmpty())
            {
                rowsWithErrors++;
            }

            rows.add(row);
        }

        return new JsonObject()
                .put("columns", matched)
                .put("ignoredColumns", ignored)
                .put("rows", rows)
                .put("rowsRead", rows.size())
                .put("rowsWithErrors", rowsWithErrors);
    }

    private static Map<Integer, JsonObject> mapHeader(JsonArray headerCells, JsonArray columns)
    {
        Map<String, JsonObject> byName = new HashMap<>();

        for (Object item : columns)
        {
            JsonObject column = (JsonObject) item;

            byName.put(column.getString("key"), column);

            for (Object alias : column.getJsonArray("aliases"))
            {
                byName.putIfAbsent((String) alias, column);
            }
        }

        Map<Integer, JsonObject> byPosition = new LinkedHashMap<>();

        Map<String, String> seen = new HashMap<>();

        for (int i = 0; i < headerCells.size(); i++)
        {
            String header = headerCells.getString(i);

            JsonObject column = byName.get(CsvColumns.normalize(header));

            if (column == null)
            {
                continue;
            }

            String key = column.getString("key");

            String earlier = seen.putIfAbsent(key, header.trim());

            if (earlier != null)
            {
                throw new CsvImportException(CsvImportException.DUPLICATE_COLUMN,
                        "Columns “" + earlier + "” and “" + header.trim() + "” both mean " + key + ". Keep only one of them.");
            }

            byPosition.put(i, column);
        }

        List<String> missing = new ArrayList<>();

        for (Object item : columns)
        {
            JsonObject column = (JsonObject) item;

            if (column.getBoolean("required") && !seen.containsKey(column.getString("key")))
            {
                missing.add(column.getString("key"));
            }
        }

        if (!missing.isEmpty())
        {
            throw new CsvImportException(CsvImportException.MISSING_COLUMNS,
                    "Missing column" + (missing.size() > 1 ? "s" : "") + ": " + String.join(", ", missing)
                            + ". The first row must hold the column names — download the template to start from the right layout.");
        }

        return byPosition;
    }

    private static JsonObject checkRow(JsonObject record, Map<Integer, JsonObject> byPosition, JsonArray columns)
    {
        int line = record.getInteger("line");

        JsonArray cells = record.getJsonArray("cells");

        JsonObject values = new JsonObject();

        JsonArray errors = new JsonArray();

        for (Object item : columns)
        {
            values.putNull(((JsonObject) item).getString("key"));
        }

        for (Map.Entry<Integer, JsonObject> entry : byPosition.entrySet())
        {
            JsonObject column = entry.getValue();

            String key = column.getString("key");

            String raw = entry.getKey() < cells.size() ? cells.getString(entry.getKey()).trim() : "";

            if (raw.isEmpty())
            {
                if (column.getBoolean("required") && !column.getBoolean("allowEmpty", false))
                {
                    errors.add(error(line, key, key + " is empty. This column is required."));
                }

                continue;
            }

            try
            {
                values.put(key, convert(raw, column));
            }
            catch (IllegalArgumentException e)
            {
                errors.add(error(line, key, e.getMessage()));
            }
        }

        return new JsonObject()
                .put("line", line)
                .put("values", values)
                .put("errors", errors)
                .put("warnings", new JsonArray());
    }

    /** The converted value, or IllegalArgumentException carrying the message for the user. */
    private static Object convert(String raw, JsonObject column)
    {
        String key = column.getString("key");

        switch (column.getString("type"))
        {
            case CsvColumns.EMAIL:
            {
                if (!EMAIL_ADDRESS.matcher(raw).matches())
                {
                    throw new IllegalArgumentException(key + " “" + raw + "” is not a valid email address.");
                }

                return raw.toLowerCase(Locale.ROOT);
            }
            case CsvColumns.DATE:
            {
                String date = parseDate(raw);

                if (date == null)
                {
                    throw new IllegalArgumentException(key + " “" + raw + "” is not a valid date. Use YYYY-MM-DD or DD-MM-YYYY.");
                }

                return date;
            }
            case CsvColumns.TIME:
            {
                Matcher m = TIME_24H.matcher(raw);

                if (!m.matches() || Integer.parseInt(m.group(1)) > 23 || Integer.parseInt(m.group(2)) > 59)
                {
                    throw new IllegalArgumentException(key + " “" + raw + "” is not a time. Use HH:MM in 24-hour format.");
                }

                return String.format("%02d:%s", Integer.parseInt(m.group(1)), m.group(2));
            }
            case CsvColumns.NUMBER:
            {
                try
                {
                    return new BigDecimal(raw);
                }
                catch (NumberFormatException e)
                {
                    throw new IllegalArgumentException(key + " “" + raw + "” is not a number.");
                }
            }
            case CsvColumns.INTEGER:
            {
                if (!INTEGER_VALUE.matcher(raw).matches() || raw.length() > 18)
                {
                    throw new IllegalArgumentException(key + " “" + raw + "” is not a whole number.");
                }

                return Long.parseLong(raw);
            }
            case CsvColumns.ENUM:
            {
                JsonArray allowed = column.getJsonArray("values");

                for (Object option : allowed)
                {
                    if (((String) option).equalsIgnoreCase(raw))
                    {
                        return option;
                    }
                }

                List<String> names = new ArrayList<>();

                for (Object option : allowed)
                {
                    names.add((String) option);
                }

                throw new IllegalArgumentException(key + " “" + raw + "” is not allowed. Use one of: " + String.join(", ", names) + ".");
            }
            default:
            {
                return raw;
            }
        }
    }

    /** YYYY-MM-DD or DD-MM-YYYY to ISO YYYY-MM-DD; null when it is not a real calendar date. */
    static String parseDate(String raw)
    {
        int year;

        int month;

        int day;

        Matcher iso = ISO_DATE.matcher(raw);

        Matcher dmy = DMY_DATE.matcher(raw);

        if (iso.matches())
        {
            year = Integer.parseInt(iso.group(1));

            month = Integer.parseInt(iso.group(2));

            day = Integer.parseInt(iso.group(3));
        }
        else if (dmy.matches())
        {
            day = Integer.parseInt(dmy.group(1));

            month = Integer.parseInt(dmy.group(2));

            year = Integer.parseInt(dmy.group(3));
        }
        else
        {
            return null;
        }

        try
        {
            return LocalDate.of(year, month, day).toString();
        }
        catch (DateTimeException e)
        {
            return null;
        }
    }

    private static JsonObject error(int line, String column, String message)
    {
        return new JsonObject()
                .put("line", line)
                .put("column", column)
                .put("message", message);
    }
}
