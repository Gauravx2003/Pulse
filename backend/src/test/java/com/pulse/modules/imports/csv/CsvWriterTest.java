package com.pulse.modules.imports.csv;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec platform/csv-import-framework: "Spreadsheet formula safety" and "Templates and round-trip exports". */
class CsvWriterTest
{
    @Test
    void maliciousNameOpensAsText()
    {
        // Spec scenario: a user name =HYPERLINK("http://x") is written so it opens as text.
        assertEquals("\"'=HYPERLINK(\"\"http://x\"\")\"", CsvWriter.cell("=HYPERLINK(\"http://x\")"));

        assertEquals("'+cmd", CsvWriter.cell("+cmd"));

        assertEquals("'-2+3", CsvWriter.cell("-2+3"));

        assertEquals("'@SUM(A1)", CsvWriter.cell("@SUM(A1)"));

        assertEquals("\"'\tx\"", CsvWriter.cell("\tx"));
    }

    @Test
    void plainNumbersAndOrdinaryTextAreUntouched()
    {
        assertEquals("-5", CsvWriter.cell("-5"));

        assertEquals("+3.5", CsvWriter.cell("+3.5"));

        assertEquals("Priya Sharma", CsvWriter.cell("Priya Sharma"));

        assertEquals("", CsvWriter.cell(null));

        assertEquals("\"Rao, Priya\"", CsvWriter.cell("Rao, Priya"));
    }

    @Test
    void exportChecksBackUnchanged()
    {
        JsonArray columns = JsonArray.of(
                CsvColumns.required("title", CsvColumns.TEXT),
                CsvColumns.optional("due", CsvColumns.DATE),
                CsvColumns.optional("points", CsvColumns.NUMBER));

        JsonArray rows = JsonArray.of(
                new JsonObject().put("title", "Rao, \"Priya\"\nnotes").put("due", "2026-09-01").put("points", "-2.5"),
                new JsonObject().put("title", "Plain").putNull("due").put("points", "4"));

        String csv = CsvWriter.write(CsvWriter.keysOf(columns), rows);

        assertTrue(csv.startsWith(CsvWriter.BOM + "title,due,points\r\n"));

        JsonObject checked = CsvValidator.validate(CsvParser.parse(csv.getBytes(StandardCharsets.UTF_8)), columns);

        assertEquals(0, checked.getInteger("rowsWithErrors"));

        JsonObject first = checked.getJsonArray("rows").getJsonObject(0).getJsonObject("values");

        assertEquals("Rao, \"Priya\"\nnotes", first.getString("title"));

        assertEquals("2026-09-01", first.getString("due"));

        assertEquals("-2.5", first.getValue("points").toString());
    }
}
