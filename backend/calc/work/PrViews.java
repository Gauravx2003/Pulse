package com.pulse.calc.work;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pull request views and rewrite suggestions (task 10.2, spec work/pull-requests): the Person tab's PR
 * list with a dot per check, the pass share of each check, the person's own comment mix with a
 * suggested rewrite for each comment below the standard, and the reviews they gave. The title rewrite
 * is the seeded suggestion when it meets the standard, otherwise one built from the title; the comment
 * rewrite is the prototype's {@code cmtFix}.
 */
public final class PrViews
{

    /**
     * The work item placeholder of a title rewrite when the PR links none.
     */
    public static final String NO_WORK_ITEM = "WORKITEM";

    private static final Pattern PREFIX = Pattern.compile(
            "^\\s*(?:[A-Z]+-\\d+\\s+)?(?:(feat|fix|refactor|perf|test|docs|chore)(?:\\([a-z0-9-]+\\))?:\\s*)?(.*)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern FIX = Pattern.compile("\\b(fix|fixed|fixes|bug|crash|error|broken|wrong|fail)", Pattern.CASE_INSENSITIVE);

    private static final Pattern FEAT = Pattern.compile("\\b(add|adds|added|new|implement|support|introduce|enable)", Pattern.CASE_INSENSITIVE);

    private static final Pattern REFACTOR = Pattern.compile("\\b(refactor|clean|rename|move|extract|simplif)", Pattern.CASE_INSENSITIVE);

    private static final Pattern PERF = Pattern.compile("\\b(perf|faster|speed|slow|latency|cache)", Pattern.CASE_INSENSITIVE);

    private static final Pattern TEST = Pattern.compile("\\btests?\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DOCS = Pattern.compile("\\b(doc|docs|readme|comment)", Pattern.CASE_INSENSITIVE);

    private static final Pattern LGTM = Pattern.compile("^lgtm|^ok$", Pattern.CASE_INSENSITIVE);

    private static final Pattern QUESTION = Pattern.compile("why|\\?\\?", Pattern.CASE_INSENSITIVE);

    private static final Pattern NIT = Pattern.compile("^nit", Pattern.CASE_INSENSITIVE);

    private static final Pattern NEEDS_WHY = Pattern.compile("^(issue|suggestion|todo):\\s*(.*)$", Pattern.CASE_INSENSITIVE);

    private static final int MIN_SUMMARY = 9;

    private static final int MAX_SUMMARY = 71;

    private PrViews()
    {
    }

    /**
     * A title that meets the standard for one that does not, or null when it already does: the seeded
     * suggestion when it meets the standard, otherwise {@code WORKITEM type: summary} with the linked
     * work item (or {@value #NO_WORK_ITEM}), a type read from the words of the title, and the summary
     * without a full stop, padded or cut to 10–72 characters.
     */
    public static String titleRewrite(String title, String workItemKey, String suggested, JsonObject rules)
    {
        String text = title == null ? "" : title;

        if (PrStandard.titleOk(text, rules))
        {
            return null;
        }

        if (suggested != null && PrStandard.titleOk(suggested, rules))
        {
            return suggested;
        }

        Matcher m = PREFIX.matcher(text);

        String type = m.matches() && m.group(1) != null ? m.group(1).toLowerCase(Locale.ROOT) : null;

        String summary = (m.matches() ? m.group(2) : text).strip().replaceAll("[.\\s]+$", "");

        if (type == null)
        {
            type = FIX.matcher(summary).find() ? "fix" : FEAT.matcher(summary).find() ? "feat"
                    : REFACTOR.matcher(summary).find() ? "refactor" : PERF.matcher(summary).find() ? "perf"
                    : TEST.matcher(summary).find() ? "test" : DOCS.matcher(summary).find() ? "docs" : "chore";
        }

        if (summary.length() < MIN_SUMMARY)
        {
            summary = (summary.isEmpty() ? "" : summary + " ") + "<what changed and why>";
        }

        if (summary.length() > MAX_SUMMARY)
        {
            summary = summary.substring(0, MAX_SUMMARY).strip().replaceAll("[.\\s]+$", "");
        }

        String key = workItemKey == null || workItemKey.isBlank() ? NO_WORK_ITEM : workItemKey;

        return key + " " + type + ": " + summary;
    }

    /**
     * {@code cmtFix}: a rewrite for a comment below the standard, or null for a Standard one.
     */
    public static String commentRewrite(String text, JsonObject rules)
    {
        String body = text == null ? "" : text;

        if (PrStandard.STANDARD.equals(PrStandard.commentClass(body, rules).getString(1)))
        {
            return null;
        }

        if (LGTM.matcher(body).find())
        {
            return "praise: <what was done well> — or approve without a comment";
        }

        if (QUESTION.matcher(body).find())
        {
            return "question: <what is unclear and why it matters>";
        }

        if (NIT.matcher(body).find())
        {
            return "nitpick (non-blocking): <the change and why>";
        }

        Matcher m = NEEDS_WHY.matcher(body);

        if (m.matches())
        {
            return m.group(1).toLowerCase(Locale.ROOT) + ": " + m.group(2) + " — because <reason / impact>";
        }

        return "issue: <what is wrong> because <impact>, or suggestion: <change> because <reason>";
    }

    /**
     * The Person tab of one person over the period's PRs (authored or reviewed):
     * {@code {prs, checkShare, comments: {classes, items}, reviews}}. {@code prs} are the person's own PRs,
     * oldest first, each with its checks as {@code {k, l, state}}, score and a title rewrite when its
     * title fails; {@code checkShare} is each check's pass share over the judged ones.
     */
    public static JsonObject person(String personId, List<JsonObject> prs, JsonObject rules)
    {
        JsonArray mine = new JsonArray();

        Map<String, int[]> share = new LinkedHashMap<>();

        Map<String, String> labels = new LinkedHashMap<>();

        JsonObject classes = new JsonObject();

        JsonArray comments = new JsonArray();

        JsonArray reviews = new JsonArray();

        for (JsonObject pr : prs)
        {
            if (personId.equals(pr.getString("authorId")))
            {
                JsonArray dots = new JsonArray();

                for (Object item : PrStandard.checks(pr, rules))
                {
                    JsonObject check = (JsonObject) item;

                    Boolean ok = check.getBoolean("ok");

                    String key = check.getString("k");

                    labels.putIfAbsent(key, check.getString("l"));

                    int[] counts = share.computeIfAbsent(key, k -> new int[2]);

                    if (ok != null)
                    {
                        counts[1]++;

                        counts[0] += ok ? 1 : 0;
                    }

                    dots.add(new JsonObject().put("k", key).put("l", check.getString("l"))
                            .put("state", ok == null ? "pending" : ok ? "pass" : "fail"));
                }

                mine.add(row(pr)
                        .put("checks", dots)
                        .put("score", PrStandard.score(pr, rules))
                        .put("suggestedTitle", titleRewrite(pr.getString("title"), pr.getString("workItemKey"),
                                pr.getString("suggestedTitle"), rules)));
            }

            for (Object item : pr.getJsonArray("reviews", new JsonArray()))
            {
                JsonObject review = (JsonObject) item;

                if (!personId.equals(review.getString("reviewerId")))
                {
                    continue;
                }

                JsonArray written = review.getJsonArray("comments", new JsonArray());

                for (Object c : written)
                {
                    String body = ((JsonObject) c).getString("body", "");

                    String label = PrStandard.commentClass(body, rules).getString(1);

                    classes.put(label, classes.getInteger(label, 0) + 1);

                    comments.add(new JsonObject()
                            .put("prId", pr.getString("id"))
                            .put("number", pr.getString("number"))
                            .put("body", body)
                            .put("class", label)
                            .put("rewrite", commentRewrite(body, rules)));
                }

                reviews.add(row(pr)
                        .put("authorId", pr.getString("authorId"))
                        .put("vote", review.getString("vote"))
                        .put("comments", written.size()));
            }
        }

        JsonArray checkShare = new JsonArray();

        share.forEach((key, counts) -> checkShare.add(new JsonObject()
                .put("k", key)
                .put("l", labels.get(key))
                .put("passed", counts[0])
                .put("judged", counts[1])
                .put("pct", counts[1] == 0 ? null : (Integer) (int) Math.round(counts[0] * 100.0 / counts[1]))));

        return new JsonObject()
                .put("prs", mine)
                .put("checkShare", checkShare)
                .put("comments", new JsonObject().put("classes", classes).put("items", comments))
                .put("reviews", reviews);
    }

    private static JsonObject row(JsonObject pr)
    {
        return new JsonObject()
                .put("id", pr.getString("id"))
                .put("number", pr.getString("number"))
                .put("tool", pr.getString("tool"))
                .put("repo", pr.getString("repo"))
                .put("title", pr.getString("title"))
                .put("status", pr.getString("status"))
                .put("openedAt", pr.getString("openedAt"))
                .put("linesChanged", pr.getValue("linesChanged"))
                .put("url", pr.getString("url"));
    }
}
