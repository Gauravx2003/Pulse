package com.pulse.modules.imports.csv;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec platform/csv-import-framework: "Parsing rules" and "Size and type limits" (task 16.2). */
class CsvParserTest
{
    private static JsonObject parse(String text)
    {
        return CsvParser.parse(text.getBytes(StandardCharsets.UTF_8), "test.csv");
    }

    private static JsonArray cells(JsonObject parsed, int row)
    {
        return parsed.getJsonArray("rows").getJsonObject(row).getJsonArray("cells");
    }

    private static String failureCode(byte[] bytes)
    {
        return assertThrows(CsvImportException.class, () -> CsvParser.parse(bytes, "test.csv")).code();
    }

    @Test
    void semicolonFileWithBomParses()
    {
        JsonObject parsed = parse("﻿employee_email;date;status\r\npriya@pulse.dev;2026-09-01;O\r\n");

        assertEquals(";", parsed.getString("delimiter"));

        assertEquals(JsonArray.of("employee_email", "date", "status"), parsed.getJsonObject("header").getJsonArray("cells"));

        assertEquals(JsonArray.of("priya@pulse.dev", "2026-09-01", "O"), cells(parsed, 0));
    }

    @Test
    void commaAndTabSeparatorsAreDetected()
    {
        assertEquals(",", parse("a,b\n1,2\n").getString("delimiter"));

        JsonObject tabbed = parse("a\tb\n1\t2\n");

        assertEquals("\t", tabbed.getString("delimiter"));

        assertEquals(JsonArray.of("1", "2"), cells(tabbed, 0));
    }

    @Test
    void quotedFieldsKeepSeparatorsQuotesAndLineBreaks()
    {
        JsonObject parsed = parse("name,note,city\n\"Rao, Priya\",\"said \"\"hi\"\"\nthen left\",Pune\nArjun,x,Delhi\n");

        assertEquals(JsonArray.of("Rao, Priya", "said \"hi\"\nthen left", "Pune"), cells(parsed, 0));

        assertEquals(2, parsed.getJsonArray("rows").getJsonObject(0).getInteger("line"));

        // The quoted line break moves the next record to file line 4.
        assertEquals(4, parsed.getJsonArray("rows").getJsonObject(1).getInteger("line"));
    }

    @Test
    void blankLinesAreSkippedButLineNumbersStayTrue()
    {
        JsonObject parsed = parse("a,b\n\n1,2\n , \n3,4");

        assertEquals(2, parsed.getJsonArray("rows").size());

        assertEquals(3, parsed.getJsonArray("rows").getJsonObject(0).getInteger("line"));

        assertEquals(5, parsed.getJsonArray("rows").getJsonObject(1).getInteger("line"));
    }

    @Test
    void emptyAndHeaderOnlyFilesAreRefused()
    {
        assertEquals(CsvImportException.EMPTY_FILE, failureCode(new byte[0]));

        assertEquals(CsvImportException.EMPTY_FILE, failureCode("\n\n".getBytes(StandardCharsets.UTF_8)));

        assertEquals(CsvImportException.NO_DATA_ROWS, failureCode("a,b\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void fiveThousandRowsIsTheLimit()
    {
        StringBuilder text = new StringBuilder("a,b\n");

        for (int i = 0; i < CsvParser.MAX_DATA_ROWS; i++)
        {
            text.append(i).append(",x\n");
        }

        assertEquals(5_000, parse(text.toString()).getJsonArray("rows").size());

        text.append("5000,x\n");

        CsvImportException e = assertThrows(CsvImportException.class, () -> parse(text.toString()));

        assertEquals(CsvImportException.TOO_MANY_ROWS, e.code());

        assertTrue(e.getMessage().contains("5,000"));
    }

    @Test
    void oversizedFileIsRefused()
    {
        byte[] sixMegabytes = new byte[6 * 1024 * 1024];

        java.util.Arrays.fill(sixMegabytes, (byte) 'a');

        assertEquals(CsvImportException.FILE_TOO_LARGE, failureCode(sixMegabytes));
    }

    @Test
    void nonCsvFilesGetTheSaveAsTip()
    {
        byte[] latin1 = "name\nJosé\n".getBytes(StandardCharsets.ISO_8859_1);

        CsvImportException e = assertThrows(CsvImportException.class, () -> CsvParser.parse(latin1, "people.csv"));

        assertEquals(CsvImportException.NOT_CSV, e.code());

        assertTrue(e.getMessage().contains("CSV UTF-8"));

        byte[] xlsx = {'P', 'K', 3, 4, 20, 0, 6, 0};

        assertEquals(CsvImportException.NOT_CSV, failureCode(xlsx));
    }

    @Test
    void unclosedQuoteNamesTheLine()
    {
        CsvImportException e = assertThrows(CsvImportException.class, () -> parse("a,b\n1,2\n3,\"oops\n"));

        assertEquals(CsvImportException.UNTERMINATED_QUOTE, e.code());

        assertTrue(e.getMessage().startsWith("Line 3"));
    }
}
