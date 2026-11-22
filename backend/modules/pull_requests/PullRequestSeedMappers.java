package com.pulse.modules.pull_requests;

import com.pulse.modules.work.WorkSeedMappers;
import com.pulse.platform.ingest.SeedFiles;
import com.pulse.platform.ingest.SeedMapper;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

/**
 * Seed mappers of the pull request tables (task 7.2): the prototype's PRS from
 * {@code seed/extracted/pull_requests.json}, each PR to {@code pull_requests} under its tool (TFS or
 * GitHub), its reviewers to {@code pr_reviews} and each reviewer's comments to {@code pr_comments}.
 * Reviews and comments keep the prototype's order through their timestamps.
 */
public final class PullRequestSeedMappers
{

    private static final String IST = "+05:30";

    private PullRequestSeedMappers()
    {
    }

    public static List<SeedMapper> all()
    {
        return List.of(
                WorkSeedMappers.mapper("tfs", "pull_requests", 240, files -> pullRequests(files, "TFS")),
                WorkSeedMappers.mapper("gh", "pull_requests", 240, files -> pullRequests(files, "GitHub")),
                WorkSeedMappers.mapper("tfs", "pr_reviews", 250, files -> reviews(files, "TFS")),
                WorkSeedMappers.mapper("gh", "pr_reviews", 250, files -> reviews(files, "GitHub")),
                WorkSeedMappers.mapper("tfs", "pr_comments", 260, files -> comments(files, "TFS")),
                WorkSeedMappers.mapper("gh", "pr_comments", 260, files -> comments(files, "GitHub")));
    }

    static JsonArray pullRequests(SeedFiles files, String tool)
    {
        JsonArray out = new JsonArray();

        for (Object item : files.array("pull_requests"))
        {
            JsonObject x = (JsonObject) item;

            if (!tool.equals(x.getString("src")))
            {
                continue;
            }

            JsonObject desc = x.getJsonObject("desc", new JsonObject());

            String workItem = x.getString("item");

            out.add(new JsonObject()
                    .put("external_id", x.getString("id"))
                    .put("number", x.getString("id"))
                    .put("tool", tool)
                    .put("repo", x.getString("repo"))
                    .put("title", x.getString("title"))
                    .put("suggested_title", x.getString("sugg"))
                    .put("author_id", SeedMapper.ref("people", x.getString("pid")))
                    .put("work_item_key", workItem == null || workItem.isEmpty() ? null : workItem)
                    .put("status", x.getString("status"))
                    .put("opened_at", x.getString("created") + "T10:00:00" + IST)
                    .put("merged_at", x.getString("merged") == null ? null : x.getString("merged") + "T18:00:00" + IST)
                    .put("lines_changed", x.getInteger("lines"))
                    .put("files_changed", x.getInteger("files"))
                    .put("has_summary", desc.getBoolean("summary", false))
                    .put("has_testing", desc.getBoolean("tested", false))
                    .put("has_risk", desc.getBoolean("risk", false))
                    .put("build_passed", x.getBoolean("build"))
                    .put("first_review_hours", x.getValue("firstRev")));
        }

        return out;
    }

    static JsonArray reviews(SeedFiles files, String tool)
    {
        JsonArray out = new JsonArray();

        for (Object item : files.array("pull_requests"))
        {
            JsonObject x = (JsonObject) item;

            if (!tool.equals(x.getString("src")))
            {
                continue;
            }

            JsonArray reviewers = x.getJsonArray("reviewers", new JsonArray());

            for (int j = 0; j < reviewers.size(); j++)
            {
                JsonObject r = reviewers.getJsonObject(j);

                out.add(new JsonObject()
                        .put("external_id", x.getString("id") + "|" + j)
                        .put("pull_request_id", SeedMapper.ref("pull_requests", x.getString("id")))
                        .put("reviewer_id", SeedMapper.ref("people", r.getString("pid")))
                        .put("vote", r.getString("vote"))
                        .put("reviewed_at", x.getString("created") + "T11:" + two(j) + ":00" + IST));
            }
        }

        return out;
    }

    static JsonArray comments(SeedFiles files, String tool)
    {
        JsonArray out = new JsonArray();

        for (Object item : files.array("pull_requests"))
        {
            JsonObject x = (JsonObject) item;

            if (!tool.equals(x.getString("src")))
            {
                continue;
            }

            JsonArray reviewers = x.getJsonArray("reviewers", new JsonArray());

            for (int j = 0; j < reviewers.size(); j++)
            {
                JsonObject r = reviewers.getJsonObject(j);

                JsonArray comments = r.getJsonArray("comments", new JsonArray());

                for (int k = 0; k < comments.size(); k++)
                {
                    JsonObject c = comments.getJsonObject(k);

                    out.add(new JsonObject()
                            .put("external_id", x.getString("id") + "|" + j + "|" + k)
                            .put("pull_request_id", SeedMapper.ref("pull_requests", x.getString("id")))
                            .put("review_id", SeedMapper.ref("pr_reviews", x.getString("id") + "|" + j))
                            .put("author_id", SeedMapper.ref("people", r.getString("pid")))
                            .put("body", c.getString("text"))
                            .put("is_resolved", c.getBoolean("resolved", false))
                            .put("commented_at", c.getString("at") + "T12:" + two(j) + ":" + two(k) + IST));
                }
            }
        }

        return out;
    }

    private static String two(int n)
    {
        return n < 10 ? "0" + n : String.valueOf(n);
    }
}
