package com.pulse.modules.imports.csv;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec platform/csv-import-framework: header mapping, per-cell checks and the 3-second budget (task 16.2). */
class CsvValidatorTest
{
    /** Shaped like the attendance import of spec admin/data-imports-exports. */
    private static final JsonArray ATTENDANCE = JsonArray.of(
            CsvColumns.required("employee_email", CsvColumns.EMAIL, "email"),
            CsvColumns.required("date", CsvColumns.DATE),
            CsvColumns.oneOf(CsvColumns.required("status", CsvColumns.ENUM), "O", "W", "D", "L", "S", "H", "A", "HD"),
            CsvColumns.oneOf(CsvColumns.optional("leave_type", CsvColumns.ENUM), "Casual", "Sick", "Earned", "Comp off", "Emergency"),
            CsvColumns.optional("check_in", CsvColumns.TIME),
            CsvColumns.optional("hours", CsvColumns.NUMBER),
            CsvColumns.optional("days", CsvColumns.INTEGER));

    private static JsonObject check(String text)
    {
        return CsvValidator.validate(CsvParser.parse(text.getBytes(StandardCharsets.UTF_8)), ATTENDANCE);
    }

    private static JsonObject row(JsonObject result, int index)
    {
        return result.getJsonArray("rows").getJsonObject(index);
    }

    private static JsonArray errors(JsonObject result, int index)
    {
        return row(result, index).getJsonArray("errors");
    }

    @Test
    void headersMatchIgnoringCaseSpacesAndAliases()
    {
        JsonObject result = check("Email,DATE, Status ,Leave Type\npriya@pulse.dev,2026-09-01,o,sick\n");

        assertEquals(JsonArray.of("employee_email", "date", "status", "leave_type"), result.getJsonArray("columns"));

        JsonObject values = row(result, 0).getJsonObject("values");

        assertEquals("O", values.getString("status"));

        assertEquals("Sick", values.getString("leave_type"));

        assertNull(values.getString("check_in"));
    }

    @Test
    void unknownColumnsAreIgnoredAndListed()
    {
        JsonObject result = check("employee_email,date,status,Notes,Team\na@x.io,2026-09-01,O,hello,Core\n");

        assertEquals(JsonArray.of("Notes", "Team"), result.getJsonArray("ignoredColumns"));

        assertTrue(errors(result, 0).isEmpty());
    }

    @Test
    void missingRequiredColumnStopsAndNamesIt()
    {
        CsvImportException e = assertThrows(CsvImportException.class, () -> check("employee_email,date\na@x.io,2026-09-01\n"));

        assertEquals(CsvImportException.MISSING_COLUMNS, e.code());

        assertTrue(e.getMessage().startsWith("Missing column: status."));
    }

    @Test
    void twoHeadersForOneColumnAreRefused()
    {
        CsvImportException e = assertThrows(CsvImportException.class,
                () -> check("email,employee_email,date,status\na@x.io,b@x.io,2026-09-01,O\n"));

        assertEquals(CsvImportException.DUPLICATE_COLUMN, e.code());
    }

    @Test
    void datesAcceptBothFormatsAndRejectImpossibleDays()
    {
        JsonObject result = check("employee_email,date,status\na@x.io,2026-09-01,O\na@x.io,05-09-2026,O\na@x.io,31-02-2026,O\na@x.io,09/05/2026,O\n");

        assertEquals("2026-09-01", row(result, 0).getJsonObject("values").getString("date"));

        assertEquals("2026-09-05", row(result, 1).getJsonObject("values").getString("date"));

        assertEquals("date", errors(result, 2).getJsonObject(0).getString("column"));

        assertTrue(errors(result, 3).getJsonObject(0).getString("message").contains("YYYY-MM-DD or DD-MM-YYYY"));
    }

    @Test
    void unknownStatusErrorsAndListsAllowedValues()
    {
        JsonObject result = check("employee_email,date,status\na@x.io,2026-09-01,X\n");

        JsonObject error = errors(result, 0).getJsonObject(0);

        assertEquals(2, error.getInteger("line"));

        assertEquals("status", error.getString("column"));

        assertTrue(error.getString("message").endsWith("Use one of: O, W, D, L, S, H, A, HD."));
    }

    @Test
    void cellTypesAreCheckedAndConverted()
    {
        JsonObject result = check("employee_email,date,status,check_in,hours,days\n"
                + "A@X.io,2026-09-01,O,9:05,7.5,3\n"
                + "not-an-email,2026-09-01,O,25:00,seven,1.5\n"
                + ",2026-09-01,O,,,\n");

        JsonObject good = row(result, 0).getJsonObject("values");

        assertEquals("a@x.io", good.getString("employee_email"));

        assertEquals("09:05", good.getString("check_in"));

        assertEquals(new BigDecimal("7.5"), good.getValue("hours"));

        assertEquals(3L, good.getLong("days"));

        assertEquals(4, errors(result, 1).size());

        assertEquals("employee_email", errors(result, 2).getJsonObject(0).getString("column"));

        assertTrue(errors(result, 2).getJsonObject(0).getString("message").contains("required"));
    }

    @Test
    void threeBadRowsOutOfHundredAreReportedByLine()
    {
        StringBuilder text = new StringBuilder("employee_email,date,status\n");

        for (int i = 1; i <= 100; i++)
        {
            String status = (i == 10 || i == 50 || i == 90) ? "X" : "O";

            text.append("p").append(i).append("@pulse.dev,2026-09-01,").append(status).append('\n');
        }

        JsonObject result = check(text.toString());

        assertEquals(100, result.getInteger("rowsRead"));

        assertEquals(3, result.getInteger("rowsWithErrors"));

        assertEquals(11, errors(result, 9).getJsonObject(0).getInteger("line"));
    }

    @Test
    void fiveThousandRowsAreCheckedInUnderThreeSeconds()
    {
        StringBuilder text = new StringBuilder("employee_email,date,status,leave_type,check_in,hours\n");

        for (int i = 0; i < 5_000; i++)
        {
            text.append("person").append(i).append("@pulse.dev,").append(String.format("%02d", i % 28 + 1))
                    .append("-09-2026,L,Sick,09:30,8\n");
        }

        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);

        long start = System.nanoTime();

        JsonObject result = CsvValidator.validate(CsvParser.parse(bytes), ATTENDANCE);

        long millis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(5_000, result.getInteger("rowsRead"));

        assertEquals(0, result.getInteger("rowsWithErrors"));

        assertTrue(millis < 3_000, "5,000 rows took " + millis + " ms");
    }
}
