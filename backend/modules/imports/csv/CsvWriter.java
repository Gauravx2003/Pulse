package com.pulse.modules.imports.csv;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.regex.Pattern;

/**
 * Writes templates and exports in an import type's column layout, so an exported file checks back
 * unchanged (spec platform/csv-import-framework, "Templates and round-trip exports").
 *
 * <p>Spreadsheet formula safety (spec "Spreadsheet formula safety"): a cell that starts with
 * {@code =}, {@code +}, {@code -}, {@code @}, tab or carriage return is prefixed with an apostrophe,
 * so Excel, LibreOffice and Google Sheets open it as text instead of running it. Plain numbers such
 * as {@code -5} or {@code +3.5} are left alone so they still read back as numbers.
 *
 * <p>Output: UTF-8 with BOM (so Excel detects the encoding), comma separated, CRLF line ends, fields
 * quoted when they hold a separator, quote, line break or edge spaces.
 */
public final class CsvWriter
{
    public static final String BOM = "﻿";

    private static final Pattern PLAIN_NUMBER = Pattern.compile("^[+-]?\\d+(\\.\\d+)?$");

    private CsvWriter()
    {
    }

    /**
     * @param keys column keys, used as the header row and to read each row
     * @param rows objects keyed by column key; missing keys become empty cells
     */
    public static String write(JsonArray keys, JsonArray rows)
    {
        StringBuilder out = new StringBuilder(BOM);

        appendLine(out, keys, null);

        for (Object row : rows)
        {
            appendLine(out, keys, (JsonObject) row);
        }

        return out.toString();
    }

    /** The column keys of an import type's column definitions, in order. */
    public static JsonArray keysOf(JsonArray columns)
    {
        JsonArray keys = new JsonArray();

        for (Object column : columns)
        {
            keys.add(((JsonObject) column).getString("key"));
        }

        return keys;
    }

    private static void appendLine(StringBuilder out, JsonArray keys, JsonObject row)
    {
        for (int i = 0; i < keys.size(); i++)
        {
            if (i > 0)
            {
                out.append(',');
            }

            String key = keys.getString(i);

            Object value = row == null ? key : row.getValue(key);

            out.append(cell(value));
        }

        out.append("\r\n");
    }

    /** One escaped cell: formula-neutralized, then quoted when needed. */
    static String cell(Object value)
    {
        String text = value == null ? "" : String.valueOf(value);

        if (startsDangerously(text) && !PLAIN_NUMBER.matcher(text).matches())
        {
            text = "'" + text;
        }

        boolean quote = text.indexOf(',') >= 0 || text.indexOf(';') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\t') >= 0
                || (!text.isEmpty() && (text.charAt(0) == ' ' || text.charAt(text.length() - 1) == ' '));

        if (!quote)
        {
            return text;
        }

        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private static boolean startsDangerously(String text)
    {
        if (text.isEmpty())
        {
            return false;
        }

        char first = text.charAt(0);

        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }
}
