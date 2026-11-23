package com.pulse.calc.work;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The PR standard and the review comment standard (task 10.1, spec work/pull-requests, reference
 * section 7.3), ported from the prototype's {@code titleRe}, {@code cmtClass}, {@code prChecks},
 * {@code prScore}, {@code prStats} and the aggregation of {@code prTeam}, {@code checkBars} and
 * {@code cmtBreak} (app.js ~1524-1590). Pure functions over plain data.
 *
 * <p>A pull request is {@code {id, authorId, title, suggestedTitle, workItemKey, status, openedAt,
 * linesChanged, hasSummary, hasTesting, hasRisk, buildPassed, firstReviewHours, reviews: [{reviewerId,
 * vote, comments: [{body, isResolved}]}]}}; {@code buildPassed} null means no build result yet. Rules
 * are {@code prTitle}, {@code prMaxLines}, {@code prMinAppr}, {@code prCmtMin}, {@code prItem},
 * {@code prDesc} and {@code prThreads}.
 */
public final class PrStandard
{

    /**
     * The title rule used when the configured pattern is not a valid regular expression.
     */
    public static final String DEFAULT_TITLE = "^[A-Z]+-\\d+ (feat|fix|refactor|perf|test|docs|chore)(\\([a-z0-9-]+\\))?: \\S.{8,70}[^.]$";

    /**
     * An approval without comments on a PR bigger than this is a rubber-stamp.
     */
    public static final int RUBBER_STAMP_LINES = 200;

    public static final String STANDARD = "Standard";

    /**
     * An issue, suggestion or todo shorter than {@code prCmtMin} after its label (spec work/pull-requests
     * "Short issue comment"; the prototype calls it "Needs a reason").
     */
    public static final String TOO_SHORT = "Too short";

    private static final Pattern LABELLED = Pattern.compile(
            "^(praise|suggestion|issue|question|nitpick|todo|thought|chore)(\\s*\\((blocking|non-blocking|if-minor)\\))?:\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NEEDS_LENGTH = Pattern.compile("^(issue|suggestion|todo)$", Pattern.CASE_INSENSITIVE);

    private static final Pattern BLOCKING = Pattern.compile("^(issue|todo)|\\(blocking\\)", Pattern.CASE_INSENSITIVE);

    private PrStandard()
    {
    }

    /**
     * {@code titleRe}: the configured title rule, or the default when it does not compile.
     */
    public static Pattern titleRule(JsonObject rules)
    {
        try
        {
            return Pattern.compile(rules.getString("prTitle", DEFAULT_TITLE));
        }
        catch (PatternSyntaxException e)
        {
            return Pattern.compile(DEFAULT_TITLE);
        }
    }

    public static boolean titleOk(String title, JsonObject rules)
    {
        return title != null && titleRule(rules).matcher(title).find();
    }

    /**
     * {@code cmtClass}: [tone, class] of a review comment: Standard, No label, Too short · no label, or
     * Too short (an issue, suggestion or todo shorter than {@code prCmtMin} after its label). Deviation:
     * the prototype names the last class "Needs a reason"; the spec's scenario and the product decision
     * name it "Too short".
     */
    public static JsonArray commentClass(String text, JsonObject rules)
    {
        int min = rules.getInteger("prCmtMin", 20);

        Matcher m = LABELLED.matcher(text);

        if (!m.matches())
        {
            return text.length() < min
                    ? new JsonArray().add("danger").add("Too short · no label")
                    : new JsonArray().add("accent").add("No label");
        }

        if (NEEDS_LENGTH.matcher(m.group(1)).matches() && m.group(4).length() < min)
        {
            return new JsonArray().add("accent").add(TOO_SHORT);
        }

        return new JsonArray().add("ok").add(STANDARD);
    }

    /**
     * {@code prChecks}: one {@code {k, l, ok, why}} per check that is switched on. {@code ok} is null
     * (Pending) when the check cannot be judged yet: approvals and threads of an open PR, and its build
     * until a result exists.
     */
    public static JsonArray checks(JsonObject pr, JsonObject rules)
    {
        boolean active = "Active".equals(pr.getString("status"));

        int approvals = approvals(pr);

        int openBlocking = 0;

        for (JsonObject comment : comments(pr))
        {
            if (!comment.getBoolean("isResolved", false) && BLOCKING.matcher(comment.getString("body", "")).find())
            {
                openBlocking++;
            }
        }

        List<String> missing = new ArrayList<>();

        if (!pr.getBoolean("hasSummary", false))
        {
            missing.add("Summary");
        }

        if (!pr.getBoolean("hasTesting", false))
        {
            missing.add("How it was tested");
        }

        if (!pr.getBoolean("hasRisk", false))
        {
            missing.add("Risk / rollback");
        }

        int maxLines = rules.getInteger("prMaxLines", 400);

        int minApprovals = rules.getInteger("prMinAppr", 2);

        int lines = pr.getInteger("linesChanged", 0);

        Boolean build = pr.getBoolean("buildPassed");

        JsonArray out = new JsonArray();

        out.add(check("title", "Title follows the standard", titleOk(pr.getString("title"), rules),
                "Suggested: " + pr.getString("suggestedTitle")));

        if (rules.getBoolean("prItem", true))
        {
            String item = pr.getString("workItemKey");

            out.add(check("item", "Linked work item", item != null && !item.isEmpty(),
                    "Link the Jira / TFS work item so the PR counts toward the task"));
        }

        if (rules.getBoolean("prDesc", true))
        {
            out.add(check("desc", "Description template complete", missing.isEmpty(), "Missing: " + String.join(", ", missing)));
        }

        out.add(check("size", "Size ≤ " + maxLines + " lines", lines <= maxLines, indian(lines) + " lines — split into smaller PRs"));

        out.add(check("appr", "≥ " + minApprovals + " approval" + (minApprovals > 1 ? "s" : ""),
                active ? null : approvals >= minApprovals,
                active ? "In review" : approvals + " approval" + (approvals == 1 ? "" : "s") + " before merge"));

        out.add(check("build", "Build / pipeline passed",
                active && (build == null || build) ? null : Boolean.TRUE.equals(build), "Merged with a failed or skipped build"));

        if (rules.getBoolean("prThreads", true))
        {
            out.add(check("threads", "Blocking threads resolved before merge", active ? null : openBlocking == 0,
                    openBlocking + " blocking thread" + (openBlocking == 1 ? "" : "s") + " still open at merge"));
        }

        return out;
    }

    /**
     * {@code prScore}: passed / judged checks (Pending left out); 1 when nothing can be judged.
     */
    public static double score(JsonObject pr, JsonObject rules)
    {
        int judged = 0;

        int passed = 0;

        for (Object item : checks(pr, rules))
        {
            Boolean ok = ((JsonObject) item).getBoolean("ok");

            if (ok != null)
            {
                judged++;

                if (ok)
                {
                    passed++;
                }
            }
        }

        return judged == 0 ? 1 : (double) passed / judged;
    }

    /**
     * {@code prStats} of one person over PRs opened between {@code from} and {@code to} (null for All):
     * {@code {mine, merged, active, comp, title, size, big, rv, cm, std, stdPct, stamps, firstRev}} where
     * {@code mine} and {@code rv} (PRs they reviewed) are PR ids and {@code cm} the comments they wrote.
     * Abandoned PRs are not theirs to count but still count as reviewed.
     */
    public static JsonObject person(String personId, Collection<JsonObject> prs, LocalDate from, LocalDate to, JsonObject rules)
    {
        List<JsonObject> mine = new ArrayList<>();

        JsonArray reviewed = new JsonArray();

        JsonArray written = new JsonArray();

        int stamps = 0;

        for (JsonObject pr : prs)
        {
            if (!within(pr, from, to))
            {
                continue;
            }

            if (personId.equals(pr.getString("authorId")) && !"Abandoned".equals(pr.getString("status")))
            {
                mine.add(pr);
            }

            for (Object item : pr.getJsonArray("reviews", new JsonArray()))
            {
                JsonObject review = (JsonObject) item;

                if (!personId.equals(review.getString("reviewerId")))
                {
                    continue;
                }

                reviewed.add(pr.getString("id"));

                JsonArray comments = review.getJsonArray("comments", new JsonArray());

                comments.forEach(c -> written.add(((JsonObject) c).getString("body")));

                if (approved(review) && comments.isEmpty() && pr.getInteger("linesChanged", 0) > RUBBER_STAMP_LINES)
                {
                    stamps++;
                }
            }
        }

        int standard = countStandard(written, rules);

        return new JsonObject()
                .put("mine", ids(mine))
                .put("merged", count(mine, "Completed"))
                .put("active", count(mine, "Active"))
                .put("comp", compliance(mine, rules))
                .put("title", (int) mine.stream().filter(pr -> titleOk(pr.getString("title"), rules)).count())
                .put("size", SprintMetrics.median(numbers(mine, "linesChanged")))
                .put("big", (int) mine.stream().filter(pr -> pr.getInteger("linesChanged", 0) > rules.getInteger("prMaxLines", 400)).count())
                .put("rv", reviewed)
                .put("cm", written)
                .put("std", standard)
                .put("stdPct", written.isEmpty() ? null : (int) Math.round(standard * 100.0 / written.size()))
                .put("stamps", stamps)
                .put("firstRev", SprintMetrics.median(numbers(mine, "firstReviewHours")));
    }

    /**
     * The team view of {@code prTeam} over the given people: PRs and merged, PR standard %, titles to
     * the standard, comments written and to the standard, rubber-stamps, each check's pass count
     * ({@code checkBars}), the comment classes ({@code cmtBreak}) and the merged PRs below the standard,
     * lowest score first (at most 8). People with no PRs and no reviews are left out.
     */
    public static JsonObject team(Collection<String> personIds, Collection<JsonObject> prs, LocalDate from, LocalDate to,
                                  JsonObject rules)
    {
        List<JsonObject> all = new ArrayList<>();

        JsonArray comments = new JsonArray();

        int stamps = 0;

        JsonArray people = new JsonArray();

        Map<String, JsonObject> byId = new LinkedHashMap<>();

        prs.forEach(pr -> byId.put(pr.getString("id"), pr));

        for (String id : personIds)
        {
            JsonObject stats = person(id, prs, from, to, rules);

            if (stats.getJsonArray("mine").isEmpty() && stats.getJsonArray("rv").isEmpty())
            {
                continue;
            }

            people.add(stats.copy().put("person", id));

            stats.getJsonArray("mine").forEach(prId -> all.add(byId.get((String) prId)));

            stats.getJsonArray("cm").forEach(comments::add);

            stamps += stats.getInteger("stamps");
        }

        int titles = (int) all.stream().filter(pr -> titleOk(pr.getString("title"), rules)).count();

        int standard = countStandard(comments, rules);

        Map<String, JsonObject> checks = new LinkedHashMap<>();

        for (JsonObject pr : all)
        {
            for (Object item : checks(pr, rules))
            {
                JsonObject c = (JsonObject) item;

                if (c.getBoolean("ok") == null)
                {
                    continue;
                }

                JsonObject bar = checks.computeIfAbsent(c.getString("k"),
                        k -> new JsonObject().put("k", k).put("l", c.getString("l")).put("p", 0).put("n", 0));

                bar.put("n", bar.getInteger("n") + 1);

                if (c.getBoolean("ok"))
                {
                    bar.put("p", bar.getInteger("p") + 1);
                }
            }
        }

        JsonObject classes = new JsonObject();

        for (Object text : comments)
        {
            String label = commentClass((String) text, rules).getString(1);

            classes.put(label, classes.getInteger(label, 0) + 1);
        }

        List<JsonObject> below = new ArrayList<>();

        for (JsonObject pr : all)
        {
            boolean failed = checks(pr, rules).stream().anyMatch(c -> Boolean.FALSE.equals(((JsonObject) c).getBoolean("ok")));

            if ("Completed".equals(pr.getString("status")) && failed)
            {
                below.add(pr);
            }
        }

        below.sort(Comparator.comparingDouble(pr -> score(pr, rules)));

        return new JsonObject()
                .put("people", people)
                .put("prs", all.size())
                .put("merged", count(all, "Completed"))
                .put("comp", compliance(all, rules))
                .put("titles", titles)
                .put("titlePct", all.isEmpty() ? null : (int) Math.round(titles * 100.0 / all.size()))
                .put("comments", comments.size())
                .put("stdPct", comments.isEmpty() ? null : (int) Math.round(standard * 100.0 / comments.size()))
                .put("stamps", stamps)
                .put("checks", new JsonArray(new ArrayList<>(checks.values())))
                .put("commentClasses", classes)
                .put("belowStandard", ids(below.subList(0, Math.min(8, below.size()))));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject check(String key, String label, Boolean ok, String why)
    {
        return new JsonObject().put("k", key).put("l", label).put("ok", ok).put("why", why);
    }

    private static boolean approved(JsonObject review)
    {
        return review.getString("vote", "").startsWith("Approved");
    }

    private static int approvals(JsonObject pr)
    {
        int n = 0;

        for (Object item : pr.getJsonArray("reviews", new JsonArray()))
        {
            if (approved((JsonObject) item))
            {
                n++;
            }
        }

        return n;
    }

    private static List<JsonObject> comments(JsonObject pr)
    {
        List<JsonObject> out = new ArrayList<>();

        for (Object review : pr.getJsonArray("reviews", new JsonArray()))
        {
            ((JsonObject) review).getJsonArray("comments", new JsonArray()).forEach(c -> out.add((JsonObject) c));
        }

        return out;
    }

    private static Integer compliance(List<JsonObject> prs, JsonObject rules)
    {
        if (prs.isEmpty())
        {
            return null;
        }

        double sum = 0;

        for (JsonObject pr : prs)
        {
            sum += score(pr, rules);
        }

        return (int) Math.round(sum / prs.size() * 100);
    }

    private static int countStandard(JsonArray comments, JsonObject rules)
    {
        int n = 0;

        for (Object text : comments)
        {
            if (STANDARD.equals(commentClass((String) text, rules).getString(1)))
            {
                n++;
            }
        }

        return n;
    }

    private static boolean within(JsonObject pr, LocalDate from, LocalDate to)
    {
        if (from == null && to == null)
        {
            return true;
        }

        LocalDate opened = LocalDate.parse(pr.getString("openedAt").substring(0, 10));

        return (from == null || !opened.isBefore(from)) && (to == null || !opened.isAfter(to));
    }

    private static int count(List<JsonObject> prs, String status)
    {
        return (int) prs.stream().filter(pr -> status.equals(pr.getString("status"))).count();
    }

    private static JsonArray ids(List<JsonObject> prs)
    {
        JsonArray out = new JsonArray();

        prs.forEach(pr -> out.add(pr.getString("id")));

        return out;
    }

    private static List<Double> numbers(List<JsonObject> prs, String key)
    {
        List<Double> out = new ArrayList<>();

        prs.forEach(pr -> out.add(Workload.orZero(Workload.number(pr, key))));

        return out;
    }

    /**
     * A whole number grouped the Indian way, as {@code toLocaleString('en-IN')}: 1,234 and 1,23,456.
     */
    static String indian(int value)
    {
        String digits = Integer.toString(Math.abs(value));

        if (digits.length() <= 3)
        {
            return (value < 0 ? "-" : "") + digits;
        }

        StringBuilder head = new StringBuilder(digits.substring(0, digits.length() - 3));

        String tail = digits.substring(digits.length() - 3);

        for (int i = head.length() - 2; i > 0; i -= 2)
        {
            head.insert(i, ',');
        }

        return (value < 0 ? "-" : "") + head + "," + tail;
    }
}
