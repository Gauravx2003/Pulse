package com.pulse.modules.bugs;

import com.pulse.calc.work.BugAttribution;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Task 12.1, spec work/bugs-support "Attribution": a Jira caused-by link wins over blame on the fixing
 * PR's diff, in the rule and in what the seed mapper writes.
 */
class BugAttributionTest
{

    @Test
    void theCausedByLinkWinsWhenBlameDiffers()
    {
        JsonObject result = BugAttribution.resolve("am", "ps");

        assertEquals("am", result.getString("person"));
        assertEquals(BugAttribution.CAUSED_BY_LINK, result.getString("method"));
    }

    @Test
    void blameCountsWithoutALinkAndNothingWithoutEither()
    {
        assertEquals("ps", BugAttribution.resolve(null, "ps").getString("person"));
        assertEquals(BugAttribution.BLAME, BugAttribution.resolve(" ", "ps").getString("method"));
        assertNull(BugAttribution.resolve(null, null).getValue("person"));
    }

    @Test
    void theSeedMapperRecordsBothResultsAndTheWinner()
    {
        JsonArray rows = BugsModule.bugs(new JsonArray()
                .add(bug("BUG-1").put("origin", "ps").put("causedBy", "am"))
                .add(bug("BUG-2").put("origin", "ps")));

        JsonObject linked = rows.getJsonObject(0);

        assertEquals("am", linked.getJsonObject("origin_person_id").getString("external_id"));
        assertEquals("caused_by_link", linked.getString("attribution_method"));
        assertEquals("am", linked.getJsonObject("caused_by_person_id").getString("external_id"));
        assertEquals("ps", linked.getJsonObject("blame_person_id").getString("external_id"));

        JsonObject blamed = rows.getJsonObject(1);

        assertEquals("ps", blamed.getJsonObject("origin_person_id").getString("external_id"));
        assertEquals("blame", blamed.getString("attribution_method"));
        assertNull(blamed.getValue("caused_by_person_id"));
    }

    private static JsonObject bug(String key)
    {
        return new JsonObject().put("key", key).put("title", key).put("sev", "High").put("status", "Open").put("env", "Staging")
                .put("originRef", "PR #1").put("found", "QA").put("created", "2026-09-01");
    }
}
