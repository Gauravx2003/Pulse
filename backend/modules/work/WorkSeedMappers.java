package com.pulse.modules.work;

import com.pulse.platform.ingest.SeedFiles;
import com.pulse.platform.ingest.SeedMapper;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Seed mappers of the Track 2 work tables (task 7.2): Jira issues (the active sprint's and the history),
 * their activity, worklogs and commit days, from {@code seed/extracted/}. {@code SeedConnector} writes
 * them through {@code IngestService} while {@code PULSE_DEMO_SEED=true}; nothing is inserted directly.
 *
 * <p>The activity of an active-sprint issue is rebuilt the way the prototype's issue drawer shows it
 * ({@code openTask}, app.js ~2722): the assignee's last activity, the move to In Progress, the sprint
 * planning and a bot edit, the last two not counted as activity.
 */
public final class WorkSeedMappers
{

    /**
     * Seeded data is in India time.
     */
    static final String IST = "+05:30";

    private WorkSeedMappers()
    {
    }

    public static List<SeedMapper> all()
    {
        return List.of(
                mapper("jira", "issues", 210, WorkSeedMappers::issues),
                mapper("jira", "issue_events", 220, WorkSeedMappers::events),
                mapper("jira", "worklogs", 230, files -> rows(files.array("worklogs"), w -> new JsonObject()
                        .put("external_id", w.getString("person") + "|" + w.getString("date"))
                        .put("person_id", SeedMapper.ref("people", w.getString("person")))
                        .put("work_date", w.getString("date"))
                        .put("hours", w.getValue("hours")))),
                mapper("gh", "commit_days", 230, files -> rows(files.array("commit_days"), c -> new JsonObject()
                        .put("external_id", c.getString("person") + "|" + c.getString("date"))
                        .put("person_id", SeedMapper.ref("people", c.getString("person")))
                        .put("commit_date", c.getString("date"))
                        .put("commit_count", 1))));
    }

    /**
     * The active sprint's issues ({@code issues.json}, prototype PEOPLE[].tasks) and the closed sprints'
     * issues ({@code issue_history.json}, prototype HIST); a history issue carried over is left In
     * Progress with {@code carried_over}.
     */
    static JsonArray issues(SeedFiles files)
    {
        JsonArray out = new JsonArray();

        for (Object item : files.array("issues"))
        {
            JsonObject t = (JsonObject) item;

            boolean done = "Done".equals(t.getString("status"));

            out.add(new JsonObject()
                    .put("external_id", t.getString("key"))
                    .put("key", t.getString("key"))
                    .put("summary", t.getString("summary"))
                    .put("issue_type", t.getString("type", "Story"))
                    .put("status", t.getString("status"))
                    .put("assignee_id", SeedMapper.ref("people", t.getString("person")))
                    .put("sprint_id", SeedMapper.ref("sprints", t.getString("sprint")))
                    .put("release_id", SeedMapper.ref("releases", t.getString("rel")))
                    .put("story_points", t.getValue("sp"))
                    .put("estimate_hours", t.getValue("orig"))
                    .put("spent_hours", t.getValue("spent"))
                    .put("remaining_hours", t.getValue("rem"))
                    .put("due_on", t.getString("due"))
                    .put("started_on", t.getString("started"))
                    .put("resolved_on", done ? t.getString("last").substring(0, 10) : null)
                    .put("carried_over", false)
                    .put("reopened", false)
                    .put("was_stale", false));
        }

        for (Object item : files.array("issue_history"))
        {
            JsonObject h = (JsonObject) item;

            boolean carried = "Carried over".equals(h.getString("status"));

            out.add(new JsonObject()
                    .put("external_id", h.getString("key"))
                    .put("key", h.getString("key"))
                    .put("summary", h.getString("summary"))
                    .put("issue_type", h.getString("type", "Story"))
                    .put("status", carried ? "In Progress" : "Done")
                    .put("assignee_id", SeedMapper.ref("people", h.getString("pid")))
                    .put("sprint_id", SeedMapper.ref("sprints", h.getString("sprint")))
                    .put("release_id", SeedMapper.ref("releases", h.getString("rel")))
                    .put("story_points", h.getValue("sp"))
                    .put("estimate_hours", h.getValue("orig"))
                    .put("spent_hours", h.getValue("spent"))
                    .put("remaining_hours", 0)
                    .put("started_on", h.getString("started"))
                    .put("resolved_on", h.getString("resolved"))
                    .put("carried_over", carried)
                    .put("reopened", h.getBoolean("reopened", false))
                    .put("was_stale", h.getBoolean("stale", false)));
        }

        return out;
    }

    /**
     * The activity of each active-sprint issue, as the prototype's drawer builds it.
     */
    static JsonArray events(SeedFiles files)
    {
        Map<String, String> names = new HashMap<>();

        for (Object item : files.array("people"))
        {
            JsonObject p = (JsonObject) item;

            names.put(p.getString("id"), p.getString("name"));
        }

        JsonArray out = new JsonArray();

        for (Object item : files.array("issues"))
        {
            JsonObject t = (JsonObject) item;

            String key = t.getString("key");

            String person = t.getString("person");

            String last = t.getString("last");

            String what = t.getString("lastWhat");

            out.add(event(key + "|last", key, last, kindOf(what), person, names.get(person), false, what));

            String started = t.getString("started");

            if (started != null && started.compareTo(last.substring(0, 10)) < 0)
            {
                out.add(event(key + "|start", key, started + "T10:00", "transition", person, names.get(person), false,
                        "Status → In Progress").put("field", "status").put("to_value", "In Progress"));
            }

            String edited = last.substring(0, 10).compareTo("2026-09-22") > 0 ? "2026-09-22T09:30" : "2026-09-21T09:30";

            out.add(event(key + "|bot", key, edited, "field_change", null, "Jira Automation", true, "Field “Sprint” edited")
                    .put("field", "Sprint"));

            out.add(event(key + "|planned", key, "2026-09-21T09:00", "sprint_change", null, "Rahul Nair", false,
                    "Added to Sprint 43").put("field", "Sprint").put("to_value", t.getString("sprint")));
        }

        return out;
    }

    /**
     * The event kind of the prototype's "last activity" label: planning labels (created, assigned) are not
     * activity; the rest is work by the assignee.
     */
    static String kindOf(String what)
    {
        if (what.startsWith("Worklog"))
        {
            return "worklog";
        }

        if (what.startsWith("Status"))
        {
            return "transition";
        }

        if (what.startsWith("PR "))
        {
            return "pull_request";
        }

        if (what.startsWith("Commit"))
        {
            return "commit";
        }

        if (what.equals("Created"))
        {
            return "created";
        }

        if (what.equals("Assigned"))
        {
            return "assignee_change";
        }

        // Comments and document edits linked to the issue.
        return "comment";
    }

    private static JsonObject event(String id, String issue, String at, String kind, String actor, String actorName,
                                    boolean bot, String summary)
    {
        return new JsonObject()
                .put("external_id", id)
                .put("issue_id", SeedMapper.ref("issues", issue))
                .put("occurred_at", at + ":00" + IST)
                .put("kind", kind)
                .put("actor_id", SeedMapper.ref("people", actor))
                .put("actor_name", actorName)
                .put("is_bot", bot)
                .put("summary", summary);
    }

    static JsonArray rows(JsonArray source, Function<JsonObject, JsonObject> row)
    {
        JsonArray out = new JsonArray();

        source.forEach(o -> out.add(row.apply((JsonObject) o)));

        return out;
    }

    /**
     * A mapper whose records are built from the seed files by {@code map}.
     */
    public static SeedMapper mapper(String tool, String entity, int order, Function<SeedFiles, JsonArray> map)
    {
        return new SeedMapper()
        {
            @Override
            public String tool()
            {
                return tool;
            }

            @Override
            public String entity()
            {
                return entity;
            }

            @Override
            public int order()
            {
                return order;
            }

            @Override
            public JsonArray map(SeedFiles files)
            {
                return map.apply(files);
            }
        };
    }
}
