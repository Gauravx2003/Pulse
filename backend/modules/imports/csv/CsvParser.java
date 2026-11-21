package com.pulse.modules.imports.csv;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Turns the raw bytes of an uploaded file into header and data rows (spec
 * platform/csv-import-framework, "Parsing rules" and "Size and type limits").
 *
 * <ul>
 *   <li>UTF-8 with or without BOM; anything that is not valid UTF-8 text is refused with the
 *       "Save as CSV UTF-8" tip (this also catches Excel .xlsx/.xls files).</li>
 *   <li>Separator detected from the header row: comma, semicolon or tab.</li>
 *   <li>Quoted fields with doubled quotes and line breaks inside quotes; CRLF, LF or CR line ends.</li>
 *   <li>Blank lines are skipped; every row keeps the file line number it starts on.</li>
 *   <li>Limits: 5 MB and 5,000 data rows.</li>
 * </ul>
 *
 * <p>Pure and synchronous: callers run it inside {@code vertx.executeBlocking(...)} (design.md,
 * Vert.x & Java Coding Standards 2). Result shape:
 * <pre>{@code {"delimiter": ",", "header": {"line": 1, "cells": [...]}, "rows": [{"line": 2, "cells": [...]}, ...]}}</pre>
 */
public final class CsvParser
{
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    public static final int MAX_DATA_ROWS = 5_000;

    static final String SAVE_AS_TIP = "In Excel use File → Save as → CSV UTF-8.";

    private static final char QUOTE = '"';

    private CsvParser()
    {
    }

    public static JsonObject parse(byte[] bytes)
    {
        return parse(bytes, null);
    }

    /**
     * @param bytes    the uploaded file
     * @param fileName the original file name, used only in messages (may be null)
     */
    public static JsonObject parse(byte[] bytes, String fileName)
    {
        if (bytes == null || bytes.length == 0)
        {
            throw new CsvImportException(CsvImportException.EMPTY_FILE, "The file is empty.");
        }

        if (bytes.length > MAX_BYTES)
        {
            throw new CsvImportException(CsvImportException.FILE_TOO_LARGE,
                    "The file is larger than 5 MB. Split it into smaller files and upload each one.");
        }

        String text = decode(bytes, fileName);

        if (!text.isEmpty() && text.charAt(0) == '﻿')
        {
            text = text.substring(1);
        }

        char delimiter = detectDelimiter(text);

        JsonArray records = split(text, delimiter);

        if (records.isEmpty())
        {
            throw new CsvImportException(CsvImportException.EMPTY_FILE, "The file is empty.");
        }

        if (records.size() < 2)
        {
            throw new CsvImportException(CsvImportException.NO_DATA_ROWS,
                    "The file has a header row but no data rows.");
        }

        if (records.size() - 1 > MAX_DATA_ROWS)
        {
            throw new CsvImportException(CsvImportException.TOO_MANY_ROWS,
                    "Up to 5,000 rows per file — split the file.");
        }

        JsonObject header = records.getJsonObject(0);

        records.remove(0);

        return new JsonObject()
                .put("delimiter", String.valueOf(delimiter))
                .put("header", header)
                .put("rows", records);
    }

    /** Strict UTF-8: a binary file or a file saved in another encoding is refused, not garbled. */
    private static String decode(byte[] bytes, String fileName)
    {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);

        String text;

        try
        {
            text = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        }
        catch (CharacterCodingException e)
        {
            throw notCsv(fileName);
        }

        // Valid UTF-8 can still be binary (zip-based .xlsx starts with "PK", and NUL bytes never
        // appear in text files).
        if (text.indexOf('\u0000') >= 0 || text.startsWith("PK\u0003\u0004"))
        {
            throw notCsv(fileName);
        }

        return text;
    }

    private static CsvImportException notCsv(String fileName)
    {
        String name = fileName == null || fileName.isBlank() ? "This file" : "“" + fileName + "”";

        return new CsvImportException(CsvImportException.NOT_CSV, name + " is not a CSV file. " + SAVE_AS_TIP);
    }

    /** The separator that occurs most often outside quotes in the first record; comma on a tie. */
    static char detectDelimiter(String text)
    {
        int commas = 0;

        int semicolons = 0;

        int tabs = 0;

        boolean quoted = false;

        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);

            if (c == QUOTE)
            {
                quoted = !quoted;
            }
            else if (!quoted && (c == '\n' || c == '\r'))
            {
                break;
            }
            else if (!quoted)
            {
                if (c == ',')
                {
                    commas++;
                }
                else if (c == ';')
                {
                    semicolons++;
                }
                else if (c == '\t')
                {
                    tabs++;
                }
            }
        }

        if (semicolons > commas && semicolons >= tabs)
        {
            return ';';
        }

        if (tabs > commas && tabs > semicolons)
        {
            return '\t';
        }

        return ',';
    }

    /** RFC 4180 style split into {line, cells} records, skipping records whose cells are all blank. */
    private static JsonArray split(String text, char delimiter)
    {
        JsonArray records = new JsonArray();

        JsonArray cells = new JsonArray();

        StringBuilder field = new StringBuilder();

        boolean quoted = false;

        boolean fieldStarted = false;

        int line = 1;

        int recordStart = 1;

        int quoteOpenedOn = 0;

        int length = text.length();

        for (int i = 0; i < length; i++)
        {
            char c = text.charAt(i);

            if (quoted)
            {
                if (c == QUOTE)
                {
                    if (i + 1 < length && text.charAt(i + 1) == QUOTE)
                    {
                        field.append(QUOTE);

                        i++;
                    }
                    else
                    {
                        quoted = false;
                    }
                }
                else
                {
                    if (c == '\n' || (c == '\r' && !(i + 1 < length && text.charAt(i + 1) == '\n')))
                    {
                        line++;
                    }

                    field.append(c);
                }

                continue;
            }

            if (c == QUOTE && !fieldStarted)
            {
                quoted = true;

                fieldStarted = true;

                quoteOpenedOn = line;
            }
            else if (c == delimiter)
            {
                cells.add(field.toString());

                field.setLength(0);

                fieldStarted = false;
            }
            else if (c == '\n' || c == '\r')
            {
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n')
                {
                    i++;
                }

                cells.add(field.toString());

                addRecord(records, recordStart, cells);

                cells = new JsonArray();

                field.setLength(0);

                fieldStarted = false;

                line++;

                recordStart = line;
            }
            else
            {
                field.append(c);

                fieldStarted = true;
            }
        }

        if (quoted)
        {
            throw new CsvImportException(CsvImportException.UNTERMINATED_QUOTE,
                    "Line " + quoteOpenedOn + " opens a quoted value that is never closed. Check the quote marks in that row.");
        }

        if (fieldStarted || field.length() > 0 || !cells.isEmpty())
        {
            cells.add(field.toString());

            addRecord(records, recordStart, cells);
        }

        return records;
    }

    private static void addRecord(JsonArray records, int line, JsonArray cells)
    {
        for (int i = 0; i < cells.size(); i++)
        {
            if (!cells.getString(i).isBlank())
            {
                records.add(new JsonObject().put("line", line).put("cells", cells));

                return;
            }
        }
    }
}
