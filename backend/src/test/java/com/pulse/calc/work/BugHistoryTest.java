package com.pulse.calc.work;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 12.2: a row of the Bugs &amp; support period table ({@code qualityHistory}): bugs in the people's
 * code by the date they were created, fixes of others' bugs by the date they were resolved, tickets by
 * the date they were opened.
 */
class BugHistoryTest
{

    @Test
    void bugsCountByCreationFixesByResolutionAndTicketsByDate()
    {
        List<JsonObject> bugs = List.of(
                bug("a", "High", "Production", "2026-09-03", null, null, false),
                bug("a", "Medium", "Staging", "2026-08-30", null, null, false),
                bug("b", "Low", "Staging", "2026-08-01", "a", "2026-09-10", true),
                bug("b", "Low", "Staging", "2026-08-01", "a", "2026-08-20", false),
                bug("a", "Low", "Staging", "2026-09-05", "a", "2026-09-06", false));

        List<JsonObject> tickets = List.of(
                new JsonObject().put("personId", "a").put("openedOn", "2026-09-16").put("hours", 3.0),
                new JsonObject().put("personId", "b").put("openedOn", "2026-09-17").put("hours", 2.0));

        JsonObject row = BugStats.periodRow(Set.of("a"), bugs, tickets, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"));

        assertEquals(2, row.getInteger("created"));
        assertEquals(1, row.getInteger("high"));
        assertEquals(1, row.getInteger("prod"));
        assertEquals(1, row.getInteger("fixed"), "a fix of one's own bug is not for others");
        assertEquals(1, row.getInteger("proactive"));
        assertEquals(1, row.getInteger("tickets"));
        assertEquals(3.0, row.getDouble("hours"), 1e-9);
    }

    private static JsonObject bug(String origin, String severity, String env, String reported, String fixer, String resolved, boolean proactive)
    {
        return new JsonObject().put("originPersonId", origin).put("severity", severity).put("environment", env).put("reportedOn", reported)
                .put("fixedById", fixer).put("resolvedOn", resolved).put("proactive", proactive);
    }
}
