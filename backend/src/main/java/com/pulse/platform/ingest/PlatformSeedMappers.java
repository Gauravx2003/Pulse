package com.pulse.platform.ingest;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.function.Function;

/**
 * Seed mappers of the Track 1 ingest tables: sprints and releases (Jira) and holidays (HRMS). They load
 * the same values as the base seed {@code S110__demo_org.sql}, keyed by the same prototype ids, so
 * re-ingesting them changes nothing but {@code synced_at}.
 *
 * <p>{@code people} is deliberately not re-ingested: it is the user directory, edited in the app
 * (task 5.1), and a deleted user's profile is anonymized with its external id removed, so re-reading
 * the seed would bring the deleted person back (spec platform/privacy-retention).
 */
public final class PlatformSeedMappers
{
    private PlatformSeedMappers()
    {
    }

    public static List<SeedMapper> all()
    {
        return List.of(
                mapper("jira", "sprints", 10, "sprints", s -> new JsonObject()
                        .put("external_id", s.getString("id"))
                        .put("key", s.getString("id"))
                        .put("number", s.getInteger("n"))
                        .put("starts_on", s.getString("from"))
                        .put("work_ends_on", s.getString("end"))
                        .put("ends_on", s.getString("to"))),
                mapper("jira", "releases", 20, "releases", r -> new JsonObject()
                        .put("external_id", r.getString("id"))
                        .put("key", r.getString("id"))
                        .put("name", r.getString("name"))
                        .put("starts_on", r.getString("from"))
                        .put("ends_on", r.getString("to"))
                        .put("release_date", r.getString("rel"))
                        .put("status", r.getString("status"))),
                mapper("hr", "holidays", 30, "holidays", h -> new JsonObject()
                        .put("external_id", h.getString("date"))
                        .put("holiday_on", h.getString("date"))
                        .put("name", h.getString("name"))));
    }

    /** A mapper that turns each row of {@code seed/extracted/<file>.json} into one record. */
    public static SeedMapper mapper(String tool, String entity, int order, String file,
                                    Function<JsonObject, JsonObject> row)
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
                JsonArray out = new JsonArray();

                files.array(file).forEach(o -> out.add(row.apply((JsonObject) o)));

                return out;
            }
        };
    }
}
