package com.pulse.modules.imports.csv;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Locale;

/**
 * Builds column definitions for {@link CsvValidator}. A definition is a plain {@link JsonObject}
 * (no DTO classes, openspec/config.yaml):
 * <pre>{@code {"key": "status", "type": "enum", "required": true, "aliases": ["state"], "values": ["O", "W", "L"]}}</pre>
 *
 * <p>Example, part of the attendance layout:
 * <pre>{@code
 * JsonArray columns = JsonArray.of(
 *         CsvColumns.required("employee_email", CsvColumns.EMAIL, "email"),
 *         CsvColumns.required("date", CsvColumns.DATE),
 *         CsvColumns.oneOf(CsvColumns.required("status", CsvColumns.ENUM), "O", "W", "D", "L", "S", "H", "A", "HD"),
 *         CsvColumns.optional("check_in", CsvColumns.TIME));
 * }</pre>
 */
public final class CsvColumns
{
    /** Any text, trimmed. */
    public static final String TEXT = "text";

    /** An email address, trimmed and lower-cased. */
    public static final String EMAIL = "email";

    /** YYYY-MM-DD or DD-MM-YYYY, normalized to YYYY-MM-DD. */
    public static final String DATE = "date";

    /** HH:MM (24-hour), normalized to two-digit hours. */
    public static final String TIME = "time";

    /** A decimal number, as {@link java.math.BigDecimal}. */
    public static final String NUMBER = "number";

    /** A whole number, as {@link Long}. */
    public static final String INTEGER = "integer";

    /** One of a fixed list ({@code values}), matched ignoring case and returned as listed. */
    public static final String ENUM = "enum";

    private CsvColumns()
    {
    }

    public static JsonObject required(String key, String type, String... aliases)
    {
        return column(key, type, true, aliases);
    }

    public static JsonObject optional(String key, String type, String... aliases)
    {
        return column(key, type, false, aliases);
    }

    /** Sets the allowed values of an {@link #ENUM} column and returns the same definition. */
    public static JsonObject oneOf(JsonObject column, String... values)
    {
        return column.put("values", JsonArray.of((Object[]) values));
    }

    /**
     * Header normalization shared by keys, aliases and file headers: trimmed, lower-case, runs of
     * anything other than letters and digits become one underscore ("Employee Email" -> employee_email).
     */
    public static String normalize(String header)
    {
        String key = header == null ? "" : header.trim().toLowerCase(Locale.ROOT);

        key = key.replaceAll("[^a-z0-9]+", "_");

        return key.replaceAll("^_+|_+$", "");
    }

    private static JsonObject column(String key, String type, boolean required, String... aliases)
    {
        JsonArray aliasList = new JsonArray();

        for (String alias : aliases)
        {
            aliasList.add(normalize(alias));
        }

        return new JsonObject()
                .put("key", normalize(key))
                .put("type", type)
                .put("required", required)
                .put("aliases", aliasList)
                .put("values", new JsonArray());
    }
}
