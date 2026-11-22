package com.pulse.modules.bugs;

import com.pulse.modules.work.WorkSeedMappers;
import com.pulse.platform.ingest.SeedMapper;
import com.pulse.platform.module.PulseModule;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * Track 2 bugs and support module (task group 12). Its tables are part of the Track 2 data layer
 * ({@code work} module, V200); it seeds the prototype's bugs and support tickets through
 * {@code IngestService} (task 7.2), both under the Jira tile: in the demo the support desk is Jira Service
 * Management, as the Zendesk tile is not connected. The API arrives with task 12.1.
 */
public class BugsModule implements PulseModule
{

    @Override
    public String id()
    {
        return "bugs";
    }

    @Override
    public List<SeedMapper> seedMappers()
    {
        return List.of(
                WorkSeedMappers.mapper("jira", "bugs", 270, files -> bugs(files.array("bugs"))),
                WorkSeedMappers.mapper("jira", "support_tickets", 280, files -> tickets(files.array("support_tickets"))));
    }

    static JsonArray bugs(JsonArray rows)
    {
        JsonArray out = new JsonArray();

        for (Object item : rows)
        {
            JsonObject b = (JsonObject) item;

            out.add(new JsonObject()
                    .put("external_id", b.getString("key"))
                    .put("key", b.getString("key"))
                    .put("title", b.getString("title"))
                    .put("severity", b.getString("sev"))
                    .put("status", b.getString("status"))
                    .put("environment", b.getString("env"))
                    .put("origin_person_id", SeedMapper.ref("people", b.getString("origin")))
                    .put("origin_ref", b.getString("originRef"))
                    .put("found_by", b.getString("found"))
                    .put("assignee_id", SeedMapper.ref("people", b.getString("assignee")))
                    .put("fixed_by_id", SeedMapper.ref("people", b.getString("fixedBy")))
                    .put("proactive", b.getBoolean("proactive", false))
                    .put("reported_on", b.getString("created"))
                    .put("resolved_on", b.getString("resolved")));
        }

        return out;
    }

    static JsonArray tickets(JsonArray rows)
    {
        JsonArray out = new JsonArray();

        for (Object item : rows)
        {
            JsonObject t = (JsonObject) item;

            out.add(new JsonObject()
                    .put("external_id", t.getString("key"))
                    .put("key", t.getString("key"))
                    .put("title", t.getString("title"))
                    .put("customer", t.getString("cust"))
                    .put("priority", t.getString("pri"))
                    .put("person_id", SeedMapper.ref("people", t.getString("pid")))
                    .put("how_taken", t.getString("how"))
                    .put("hours", t.getValue("hours"))
                    .put("status", t.getString("status"))
                    .put("opened_on", t.getString("date"))
                    .put("csat", t.getValue("csat")));
        }

        return out;
    }
}
