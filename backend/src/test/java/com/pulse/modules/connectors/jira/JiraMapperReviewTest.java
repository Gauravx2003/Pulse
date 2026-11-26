package com.pulse.modules.connectors.jira;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Task 25.4 (Track 2's review of the Jira mapping, change pulse-v2-live-connectors): the changelog values
 * the Effort detection reads are stored as the seed stores them. A sprint change holds the key of the
 * issue's last sprint, and Jira sprints are keyed {@code J<id>}, never equal to a seeded {@code S<n>}. An
 * assignee change holds the Jira usernames, which V294 resolves to people. Any other field keeps Jira's
 * display text.
 */
class JiraMapperReviewTest
{

    @Test
    void sprintKeysAreApartFromTheSeed()
    {
        assertEquals("J43", JiraMapper.sprintKey("43"));

        JsonObject record = JiraMapper.sprintRecord(new JsonObject().put("id", "43").put("startDate", "2026-11-23T10:00:00.000+0530")
                .put("endDate", "2026-12-04T19:00:00.000+0530"));

        assertEquals("J43", record.getString("key"));
        assertEquals(43, record.getInteger("number"), "the screens show the Jira number");
    }

    @Test
    void aSprintChangeHoldsTheKeyOfTheLastSprint()
    {
        JsonArray events = JiraMapper.events(hit(new JsonObject().put("field", "Sprint").put("from", "146").put("fromString", "Sprint 146")
                .put("to", "146,147").put("toString", "Sprint 146,Sprint 147")), true);

        JsonObject change = change(events);

        assertEquals("sprint_change", change.getString("kind"));
        assertEquals("J146", change.getString("from_value"));
        assertEquals("J147", change.getString("to_value"), "the sprint the issue was added to");
        assertEquals("Sprint → Sprint 146,Sprint 147", change.getString("summary"), "names stay in the summary");

        JsonObject removed = change(JiraMapper.events(hit(new JsonObject().put("field", "Sprint").put("from", "147")
                .put("to", null).put("toString", null)), true));

        assertNull(removed.getValue("to_value"));
    }

    @Test
    void anAssigneeChangeHoldsTheUsernames()
    {
        JsonObject change = change(JiraMapper.events(hit(new JsonObject().put("field", "assignee").put("from", "dev.user2")
                .put("fromString", "Dev User 2").put("to", "dev.user1").put("toString", "Dev User 1")), true));

        assertEquals("assignee_change", change.getString("kind"));
        assertEquals("dev.user2", change.getString("from_value"));
        assertEquals("dev.user1", change.getString("to_value"));
        assertEquals("Assigned to Dev User 1", change.getString("summary"));
    }

    @Test
    void anyOtherFieldKeepsTheDisplayText()
    {
        JsonObject change = change(JiraMapper.events(hit(new JsonObject().put("field", "status").put("from", "1")
                .put("fromString", "Open").put("to", "3").put("toString", "In Progress")), true));

        assertEquals("transition", change.getString("kind"));
        assertEquals("Open", change.getString("from_value"));
        assertEquals("In Progress", change.getString("to_value"));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject hit(JsonObject item)
    {
        return new JsonObject()
                .put("key", "PROJ-1")
                .put("fields", new JsonObject())
                .put("changelog", new JsonObject().put("histories", new JsonArray().add(new JsonObject()
                        .put("id", "900")
                        .put("created", "2026-11-25T11:00:00.000+0530")
                        .put("author", new JsonObject().put("name", "dev.user2").put("displayName", "Dev User 2"))
                        .put("items", new JsonArray().add(item)))));
    }

    private static JsonObject change(JsonArray events)
    {
        assertEquals(1, events.size(), events.encode());

        return events.getJsonObject(0);
    }
}
