package com.pulse.calc.work;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Team utilization (task 9.1, spec work/team-utilization), ported from the prototype's {@code pTeam} and
 * {@code pTeamPer}: the team KPIs of a live sprint and of a closed period, the rebalance suggestion, the
 * heatmap row and the team utilization of one sprint. Pure functions over per-person results the
 * {@code work.*} formulas already compute; the caller passes only the people the viewer may see.
 */
public final class TeamViews
{

    /**
     * Stale share of open work above which the KPI is shown as a danger ({@code st/op > 0.3} in the
     * prototype); a display tone, not a rule.
     */
    public static final double STALE_SHARE_ALERT = 0.3;

    /**
     * A rated skill counts for a rebalance match from this level (of 5) up: the person can work on it
     * alone.
     */
    public static final int SKILL_MATCH_LEVEL = 3;

    private TeamViews()
    {
    }

    /**
     * Live sprint KPIs. {@code rows}: {@code {id, name, util, work, cap, open, stale, onboarding}} per
     * person. Team utilization is all work over all capacity; Overloaded is above 100%, Under-utilized is
     * below the {@code under} rule and never an onboarding joiner (spec "Fairness").
     */
    public static JsonObject liveKpis(List<JsonObject> rows, JsonObject rules)
    {
        double under = rules.getDouble("under", 60.0);

        double work = 0;

        double capacity = 0;

        int stale = 0;

        int open = 0;

        int onboarding = 0;

        JsonArray overloaded = new JsonArray();

        JsonArray low = new JsonArray();

        for (JsonObject row : byUtilization(rows))
        {
            work += number(row, "work");

            capacity += number(row, "cap");

            stale += row.getInteger("stale", 0);

            open += row.getInteger("open", 0);

            boolean joiner = row.getBoolean("onboarding", false);

            if (joiner)
            {
                onboarding++;
            }

            if (number(row, "util") > 100)
            {
                overloaded.add(brief(row));
            }

            if (number(row, "util") < under && !joiner)
            {
                low.add(brief(row));
            }
        }

        Integer team = capacity > 0 ? (int) Math.round(work / capacity * 100) : null;

        return new JsonObject()
                .put("people", rows.size())
                .put("onboarding", onboarding)
                .put("utilization", team)
                .put("band", team == null ? null : Workload.band(team, rules))
                .put("overloaded", overloaded)
                .put("underUtilized", low)
                .put("countable", rows.size() - onboarding)
                .put("stale", stale)
                .put("open", open)
                .put("staleShare", open > 0 ? (Integer) (int) Math.round(stale * 100.0 / open) : null)
                .put("staleAlert", open > 0 && (double) stale / open > STALE_SHARE_ALERT);
    }

    /**
     * The rebalance suggestion (spec "Rebalance suggestion"): the most loaded person above 100% who has a
     * To Do issue, their biggest To Do issue by remaining hours, and the first person below the
     * {@code under} rule, by utilization from the top as in the prototype, who is not onboarding and
     * whose skills match. New levels move the issue's hours at {@code focus} hours a day. Null when
     * nobody fits. Nothing is written anywhere: Reassign happens in Jira.
     *
     * @param rows   {@code {id, name, util, work, cap, onboarding, level, skills}} per person
     * @param toDo   person id to that person's To Do issues {@code {key, summary, remainingHours, htmlUrl}};
     *               the suggestion's issue keeps {@code htmlUrl}, the link to a live issue in its tool
     * @param match  whether the second person may take the first person's work, e.g. {@link #skillsMatch}
     */
    public static JsonObject rebalance(List<JsonObject> rows, Map<String, List<JsonObject>> toDo,
                                       BiPredicate<JsonObject, JsonObject> match, JsonObject rules)
    {
        double under = rules.getDouble("under", 60.0);

        double focus = rules.getDouble("focus", 6.0);

        List<JsonObject> sorted = byUtilization(rows);

        for (JsonObject from : sorted)
        {
            if (number(from, "util") <= 100 || number(from, "cap") <= 0)
            {
                continue;
            }

            List<JsonObject> issues = new ArrayList<>(toDo.getOrDefault(from.getString("id"), List.of()));

            if (issues.isEmpty())
            {
                continue;
            }

            issues.sort(Comparator.comparingDouble((JsonObject issue) -> -number(issue, "remainingHours"))
                    .thenComparing(issue -> issue.getString("key", "")));

            JsonObject issue = issues.get(0);

            double hours = number(issue, "remainingHours");

            for (JsonObject to : sorted)
            {
                boolean candidate = number(to, "util") < under && !to.getBoolean("onboarding", false)
                        && number(to, "cap") > 0 && !to.getString("id").equals(from.getString("id"));

                if (!candidate || !match.test(from, to))
                {
                    continue;
                }

                int fromAfter = (int) Math.round((number(from, "work") - hours / focus) / number(from, "cap") * 100);

                int toAfter = (int) Math.round((number(to, "work") + hours / focus) / number(to, "cap") * 100);

                int fromNow = (int) Math.round(number(from, "util"));

                int toNow = (int) Math.round(number(to, "util"));

                String text = "Move " + issue.getString("key") + " (" + hoursText(hours) + " h) from " + from.getString("name")
                        + " (" + fromNow + "%) to " + to.getString("name") + " (" + toNow + "%). New levels ≈ "
                        + fromAfter + "% and " + toAfter + "%.";

                return new JsonObject()
                        .put("issue", new JsonObject()
                                .put("key", issue.getString("key"))
                                .put("summary", issue.getString("summary"))
                                .put("hours", hours)
                                .put("htmlUrl", issue.getValue("htmlUrl") instanceof String url ? url : null))
                        .put("from", side(from, fromNow, fromAfter))
                        .put("to", side(to, toNow, toAfter))
                        .put("text", text);
            }
        }

        return null;
    }

    /**
     * Whether {@code to} may take {@code from}'s work: the same engineering level or higher, and, when
     * both have rated skills (Road map skills matrix), at least one shared skill that {@code to} holds at
     * {@link #SKILL_MATCH_LEVEL} or above. People without a level or rated skills are not ruled out by
     * that part.
     */
    public static boolean skillsMatch(JsonObject from, JsonObject to)
    {
        Integer fromLevel = level(from.getString("level"));

        Integer toLevel = level(to.getString("level"));

        if (fromLevel != null && toLevel != null && toLevel < fromLevel)
        {
            return false;
        }

        JsonArray fromSkills = from.getJsonArray("skills", new JsonArray());

        JsonArray toSkills = to.getJsonArray("skills", new JsonArray());

        if (fromSkills.isEmpty() || toSkills.isEmpty())
        {
            return true;
        }

        for (Object a : fromSkills)
        {
            String skill = ((JsonObject) a).getString("skill", "").strip().toLowerCase(Locale.ROOT);

            for (Object b : toSkills)
            {
                JsonObject held = (JsonObject) b;

                if (skill.equals(held.getString("skill", "").strip().toLowerCase(Locale.ROOT))
                        && held.getInteger("current", 0) >= SKILL_MATCH_LEVEL)
                {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * One heatmap row over the active sprint's working days (spec "Daily heatmap"), from the person's
     * {@code work.dailyLoad} days: "—" before joining, then Leave, Visit and Training (onboarding) cells
     * with no value, else logged ÷ focus hours before today and the live utilization from today, each
     * with its band.
     */
    public static JsonArray heatmapRow(JsonArray load, String joinedOn, boolean onboarding, Integer util, JsonObject rules)
    {
        double focus = rules.getDouble("focus", 6.0);

        JsonArray cells = new JsonArray();

        for (Object item : load)
        {
            JsonObject day = (JsonObject) item;

            String date = day.getString("date");

            String note = day.getString("note");

            JsonObject cell = new JsonObject().put("date", date).put("today", day.getBoolean("today", false));

            if (joinedOn != null && date.compareTo(joinedOn) < 0)
            {
                cells.add(cell.put("kind", "prejoin").put("label", "—"));
            }
            else if ("Leave".equals(note))
            {
                cells.add(cell.put("kind", "leave").put("label", "Leave"));
            }
            else if (note != null)
            {
                cells.add(cell.put("kind", "visit").put("label", "Visit"));
            }
            else if (onboarding)
            {
                cells.add(cell.put("kind", "training").put("label", "Training"));
            }
            else
            {
                boolean past = "logged".equals(day.getString("kind"));

                int value = past ? (int) Math.round(number(day, "hours") / focus * 100) : util == null ? 0 : util;

                cells.add(cell.put("kind", past ? "logged" : "planned")
                        .put("value", value)
                        .put("band", Workload.band(value, rules)));
            }
        }

        return cells;
    }

    /**
     * Closed period KPIs. {@code rows}: {@code {id, name, logged, work, com, dsp, carry, util, newJoiner}}
     * per person. Team utilization is hours logged over working days × {@code dayHours}; Under leaves out
     * new joiners.
     */
    public static JsonObject closedKpis(List<JsonObject> rows, JsonObject rules)
    {
        double under = rules.getDouble("under", 60.0);

        double dayHours = rules.getDouble("dayHours", 8.0);

        double logged = 0;

        double work = 0;

        double committed = 0;

        double done = 0;

        int carried = 0;

        JsonArray over = new JsonArray();

        JsonArray low = new JsonArray();

        for (JsonObject row : byUtilization(rows))
        {
            logged += number(row, "logged");

            work += number(row, "work");

            committed += number(row, "com");

            done += number(row, "dsp");

            carried += row.getInteger("carry", 0);

            if (number(row, "util") > 100)
            {
                over.add(brief(row));
            }

            if (number(row, "util") < under && !row.getBoolean("newJoiner", false))
            {
                low.add(brief(row));
            }
        }

        int team = work > 0 ? (int) Math.round(logged / (work * dayHours) * 100) : 0;

        return new JsonObject()
                .put("people", rows.size())
                .put("utilization", team)
                .put("band", Workload.band(team, rules))
                .put("storyPointsDone", done)
                .put("committed", committed)
                .put("commitment", committed > 0 ? (Integer) (int) Math.round(done / committed * 100) : null)
                .put("carriedOver", carried)
                .put("over", over)
                .put("under", low);
    }

    /**
     * Team utilization of one sprint on the "by sprint" chart: the average of the people's live
     * utilization for the active sprint (a forecast), else hours logged over working days × {@code dayHours}.
     */
    public static int sprintUtilization(boolean live, List<Double> liveUtilizations, double logged, double work, JsonObject rules)
    {
        if (live)
        {
            return liveUtilizations.isEmpty() ? 0
                    : (int) Math.round(liveUtilizations.stream().mapToDouble(Double::doubleValue).sum() / liveUtilizations.size());
        }

        return work > 0 ? (int) Math.round(logged / (work * rules.getDouble("dayHours", 8.0)) * 100) : 0;
    }

    /**
     * Rows by utilization, highest first, then by name (the people table order).
     */
    public static List<JsonObject> byUtilization(List<JsonObject> rows)
    {
        List<JsonObject> out = new ArrayList<>(rows);

        out.sort(Comparator.comparingDouble((JsonObject row) -> -number(row, "util"))
                .thenComparing(row -> row.getString("name", "")));

        return out;
    }

    private static JsonObject brief(JsonObject row)
    {
        return new JsonObject()
                .put("id", row.getString("id"))
                .put("name", row.getString("name"))
                .put("utilization", (int) Math.round(number(row, "util")));
    }

    private static JsonObject side(JsonObject row, int now, int after)
    {
        return new JsonObject()
                .put("id", row.getString("id"))
                .put("name", row.getString("name"))
                .put("utilization", now)
                .put("after", after);
    }

    private static String hoursText(double hours)
    {
        return BigDecimal.valueOf(hours).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static Integer level(String level)
    {
        if (level == null || !level.matches("L[0-9]"))
        {
            return null;
        }

        return Integer.parseInt(level.substring(1));
    }

    private static double number(JsonObject row, String key)
    {
        return row.getValue(key) instanceof Number n ? n.doubleValue() : 0;
    }
}
