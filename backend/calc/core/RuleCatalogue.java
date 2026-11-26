package com.pulse.calc.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The editable rules behind every score (task 5.2, spec platform/rules-thresholds, reference sections
 * 11.4 and 12): the default rule set, the cards (groups) of Rules &amp; thresholds and each editable field
 * with its type, range, step and unit. It is the only place that holds rule defaults and ranges
 * (design.md decision 5: no threshold constant outside {@code calc/}). The base seed writes
 * {@link #defaults()} as rule version 1; a test keeps the two equal.
 *
 * <p>A field is addressed by a path into the rule set: {@code "staleDays"}, {@code "weights.delivery"},
 * {@code "pts.visit"}, {@code "vw.3"} (the fourth culture value), {@code "appraisal.bands.2.lo"}. Ranges
 * and steps are the ones of the prototype's sliders and inputs (app.js {@code rg(...)} calls).
 *
 * <p>Field: {@code {path, group, label, help, type, min, max, step, unit, default}}; type is one of
 * {@code integer}, {@code number}, {@code boolean}, {@code pattern} (a regular expression, at most
 * {@link #PATTERN_MAX_LENGTH} characters) or {@code time} ({@code HH:MM}).
 */
public final class RuleCatalogue
{
    public static final String INTEGER = "integer";

    public static final String NUMBER = "number";

    public static final String BOOLEAN = "boolean";

    public static final String PATTERN = "pattern";

    public static final String TIME = "time";

    /** Longest PR title pattern accepted (spec "Value validation": length-limited). */
    public static final int PATTERN_MAX_LENGTH = 200;

    /** The total that weight sets must reach (spec "Weights must total 100"). */
    public static final double WEIGHTS_TOTAL = 100;

    /** The PR title standard of reference section 12 (prototype {@code PR_TITLE_DEFAULT}). */
    public static final String PR_TITLE_DEFAULT =
            "^[A-Z]+-\\d+ (feat|fix|refactor|perf|test|docs|chore)(\\([a-z0-9-]+\\))?: \\S.{8,70}[^.]$";

    public static final String STALENESS = "staleness_capacity";

    public static final String UTILIZATION = "utilization_bands";

    public static final String EFFORT = "effort_points";

    public static final String VISITS = "customer_visits";

    public static final String ATTENDANCE = "attendance_leave";

    public static final String PR_STANDARD = "pr_standard";

    public static final String TRAINING = "training";

    public static final String CULTURE = "culture_weights";

    public static final String DISCIPLINE = "discipline_bands";

    public static final String INDEX = "index_weights";

    public static final String APPRAISAL = "appraisal_standards";

    /** Permission that edits every group except appraisal standards. */
    public static final String RULES_PERMISSION = "rules.manage";

    /** Permission that edits appraisal standards (spec "Editable rule groups"). */
    public static final String APPRAISAL_PERMISSION = "appraisal.approve";

    /**
     * Reference section 12 defaults, without the PR title pattern (added in {@link #defaults()}), plus the
     * working-day window (change jira-derived-hours), which the seeded version 1 does not hold: a rule set read
     * from the database goes through {@link #withDefaults} so a later rule reads its default until saved.
     */
    private static final String DEFAULTS_JSON = """
            {"staleDays":2,"focus":6,"under":60,"healthy":85,"over":120,"notStartedPct":40,
             "weights":{"delivery":35,"quality":15,"culture":25,"okr":15,"growth":10},
             "nudge":true,"excludeBots":true,"vw":[15,10,10,10,5,5,10,10,5,10,10],
             "d5":95,"d4":85,"d3":70,"d2":50,"dayHours":8,"monthCap":6,"bonusPerPoint":0.05,"bonusCap":0.3,
             "unplannedAlert":30,"workday":{"start":"10:00","end":"18:00"},
             "pts":{"midsprint":1,"incident":2,"takeover":1,"untracked":1,"help":1,"reviews":0.5,"support":1,
                    "proactivefix":1.5,"visit":1.5,"visitDay":0.5,"visitWeekend":1,"visitEsc":0.5,"visitCsat":1},
             "visitCap":8,"prMaxLines":400,"prMinAppr":2,"prCmtMin":20,"prSla":24,"prTarget":85,
             "prItem":true,"prDesc":true,"prThreads":true,
             "trWa":40,"trWm":20,"trWg":20,"trWc":10,"trWp":10,"trPass":70,"trAssessDays":3,"trSignDays":5,
             "dataShare":40,
             "att":{"officeDays":3,"notice":2,"noticeLong":7,"lateAfter":"10:30","grace":3,"inCulture":true},
             "appraisal":{"w":{"perf":50,"r360":20,"mgr":20,"disc":10},"max":20,"budget":12,"minMonths":6,
                          "bands":[{"min":4.5,"lo":15,"hi":20,"name":"Outstanding","tone":"ok"},
                                   {"min":4,"lo":10,"hi":15,"name":"Exceeds","tone":"ok"},
                                   {"min":3.5,"lo":7,"hi":10,"name":"Strong","tone":"brand"},
                                   {"min":3,"lo":4,"hi":7,"name":"Meets","tone":"brand"},
                                   {"min":2.5,"lo":0,"hi":3,"name":"Developing","tone":"accent"},
                                   {"min":0,"lo":0,"hi":0,"name":"Below expectations","tone":"danger"}]}}
            """;

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("H:mm");

    private static final JsonObject DEFAULTS = buildDefaults();

    /** group key -> {key, label, permission, totalOf, fields: [...]}, in card order. */
    private static final Map<String, JsonObject> GROUPS = buildGroups();

    /** path -> field. */
    private static final Map<String, JsonObject> FIELDS = indexFields();

    private RuleCatalogue()
    {
    }

    // ------------------------------------------------------------------ reading the catalogue

    /** A copy of the full default rule set (rule version 1). */
    public static JsonObject defaults()
    {
        return DEFAULTS.copy();
    }

    /**
     * A copy of {@code rules} with every key it lacks taken from the defaults, at every level of nested
     * objects. A stored value is never replaced, and arrays are kept as stored. A rule added after a
     * database's rule version was written (the working-day window) so reads its default until it is saved.
     */
    public static JsonObject withDefaults(JsonObject rules)
    {
        JsonObject out = rules == null ? new JsonObject() : rules.copy();

        fill(out, DEFAULTS);

        return out;
    }

    /** Group keys in card order. */
    public static List<String> groupKeys()
    {
        return List.copyOf(GROUPS.keySet());
    }

    /** {@code {key, label, permission, totalOf, fields}} (a copy), or null for an unknown key. */
    public static JsonObject group(String key)
    {
        JsonObject g = key == null ? null : GROUPS.get(key);

        return g == null ? null : g.copy();
    }

    /** The field at {@code path} (a copy), or null when no editable field has that path. */
    public static JsonObject field(String path)
    {
        JsonObject f = path == null ? null : FIELDS.get(path);

        return f == null ? null : f.copy();
    }

    /** Paths of the editable fields of a group, in card order; empty for an unknown group. */
    public static List<String> paths(String group)
    {
        JsonObject g = GROUPS.get(group);

        if (g == null)
        {
            return List.of();
        }

        List<String> out = new ArrayList<>();

        g.getJsonArray("fields").forEach(f -> out.add(((JsonObject) f).getString("path")));

        return out;
    }

    /** Every editable path, in card order. */
    public static List<String> allPaths()
    {
        return List.copyOf(FIELDS.keySet());
    }

    // ------------------------------------------------------------------ paths

    /** The value at {@code path} in a rule set; null when any step is missing. */
    public static Object read(JsonObject rules, String path)
    {
        Object at = rules;

        for (String step : path.split("\\."))
        {
            if (at instanceof JsonObject o)
            {
                at = o.getValue(step);
            }
            else if (at instanceof JsonArray a)
            {
                int i = index(step);

                at = i >= 0 && i < a.size() ? a.getValue(i) : null;
            }
            else
            {
                return null;
            }
        }

        return at;
    }

    /** Sets the value at an existing {@code path} of a rule set; fails when the path does not exist. */
    public static void write(JsonObject rules, String path, Object value)
    {
        String[] steps = path.split("\\.");
        Object at = rules;

        for (int i = 0; i < steps.length - 1; i++)
        {
            at = at instanceof JsonObject o ? o.getValue(steps[i])
                    : at instanceof JsonArray a ? a.getValue(index(steps[i])) : null;
        }

        String last = steps[steps.length - 1];

        if (at instanceof JsonObject o)
        {
            o.put(last, value);
        }
        else if (at instanceof JsonArray a && index(last) >= 0 && index(last) < a.size())
        {
            a.set(index(last), value);
        }
        else
        {
            throw new IllegalArgumentException("No rule at " + path);
        }
    }

    // ------------------------------------------------------------------ validation

    /**
     * Checks one value against its field's type and range. Returns null when valid, otherwise the
     * problem as a sentence fragment ("must be between 1 and 5"). The safety of a pattern (catastrophic
     * backtracking) is checked by the rules module, which runs it off the event loop.
     */
    public static String problem(String path, Object value)
    {
        JsonObject f = FIELDS.get(path);

        if (f == null)
        {
            return "is not an editable rule";
        }

        String type = f.getString("type");

        switch (type)
        {
            case BOOLEAN:
                return value instanceof Boolean ? null : "must be true or false";

            case TIME:
                return value instanceof String s && validTime(s) ? null : "must be a time as HH:MM, for example 10:30";

            case PATTERN:
                if (!(value instanceof String s) || s.isBlank())
                {
                    return "must be a regular expression";
                }

                return s.length() > PATTERN_MAX_LENGTH ? "must be at most " + PATTERN_MAX_LENGTH + " characters" : null;

            default:
                if (!(value instanceof Number n) || Double.isNaN(n.doubleValue()) || Double.isInfinite(n.doubleValue()))
                {
                    return INTEGER.equals(type) ? "must be a whole number" : "must be a number";
                }

                if (INTEGER.equals(type) && n.doubleValue() != Math.rint(n.doubleValue()))
                {
                    return "must be a whole number";
                }

                double min = f.getDouble("min");
                double max = f.getDouble("max");

                return n.doubleValue() < min || n.doubleValue() > max
                        ? "must be between " + plain(min) + " and " + plain(max)
                        : null;
        }
    }

    /** The value as stored: integers as {@link Integer}, other numbers as they came. */
    public static Object normalize(String path, Object value)
    {
        JsonObject f = FIELDS.get(path);

        if (f != null && INTEGER.equals(f.getString("type")) && value instanceof Number n)
        {
            return n.intValue();
        }

        if (f != null && TIME.equals(f.getString("type")) && value instanceof String s)
        {
            return LocalTime.parse(s.strip(), HH_MM).format(DateTimeFormatter.ofPattern("HH:mm"));
        }

        return value;
    }

    /**
     * Checks the rules of a whole group in a candidate rule set: weight totals and the appraisal band
     * order. Returns null when valid, otherwise {@code {code, message[, total][, field]}}.
     */
    public static JsonObject groupProblem(String group, JsonObject rules)
    {
        JsonObject g = GROUPS.get(group);

        if (g == null)
        {
            return null;
        }

        String totalOf = g.getString("totalOf");

        if (totalOf != null)
        {
            double total = total(rules, totalOf);

            if (Math.abs(total - WEIGHTS_TOTAL) > 1e-9)
            {
                return new JsonObject()
                        .put("code", "weights_total")
                        .put("message", "Weights total " + plain(total) + "% — they must total 100%.")
                        .put("total", total);
            }
        }

        if (APPRAISAL.equals(group))
        {
            return appraisalProblem(rules);
        }

        if (STALENESS.equals(group))
        {
            return workdayProblem(rules);
        }

        return null;
    }

    /** The sum of the numbers under {@code prefix} ({@code "weights"}, {@code "vw"}, {@code "appraisal.w"}). */
    public static double total(JsonObject rules, String prefix)
    {
        Object at = read(rules, prefix);
        double total = 0;

        if (at instanceof JsonObject o)
        {
            for (String k : o.fieldNames())
            {
                total += o.getValue(k) instanceof Number n ? n.doubleValue() : 0;
            }
        }
        else if (at instanceof JsonArray a)
        {
            for (int i = 0; i < a.size(); i++)
            {
                total += a.getValue(i) instanceof Number n ? n.doubleValue() : 0;
            }
        }

        return total;
    }

    /**
     * The working-day window must end after it starts (spec platform/rules-thresholds, "Working-day window").
     * Each time is checked as HH:MM by {@link #problem} first.
     */
    private static JsonObject workdayProblem(JsonObject rules)
    {
        Object start = read(rules, "workday.start");

        Object end = read(rules, "workday.end");

        if (!(start instanceof String s) || !(end instanceof String e) || !validTime(s) || !validTime(e))
        {
            return null;
        }

        if (LocalTime.parse(e.strip(), HH_MM).isAfter(LocalTime.parse(s.strip(), HH_MM)))
        {
            return null;
        }

        return new JsonObject()
                .put("code", "workday_window")
                .put("message", "The working day must end after it starts (" + s.strip() + "–" + e.strip() + ").")
                .put("field", "workday.end");
    }

    private static void fill(JsonObject target, JsonObject defaults)
    {
        for (String key : defaults.fieldNames())
        {
            Object value = defaults.getValue(key);

            if (!target.containsKey(key))
            {
                target.put(key, value instanceof JsonObject o ? o.copy() : value instanceof JsonArray a ? a.copy() : value);
            }
            else if (value instanceof JsonObject o && target.getValue(key) instanceof JsonObject t)
            {
                fill(t, o);
            }
        }
    }

    private static JsonObject appraisalProblem(JsonObject rules)
    {
        double max = ((Number) read(rules, "appraisal.max")).doubleValue();
        JsonArray bands = (JsonArray) read(rules, "appraisal.bands");

        for (int i = 0; i < bands.size(); i++)
        {
            JsonObject b = bands.getJsonObject(i);

            if (b.getDouble("hi") > max || b.getDouble("lo") > b.getDouble("hi"))
            {
                return new JsonObject()
                        .put("code", "appraisal_bands")
                        .put("message", "Each band needs min ≤ max ≤ " + plain(max) + "%.")
                        .put("field", "appraisal.bands." + i + ".hi");
            }

            if (i > 0 && b.getDouble("min") >= bands.getJsonObject(i - 1).getDouble("min"))
            {
                return new JsonObject()
                        .put("code", "appraisal_bands")
                        .put("message", "Band scores must go down from top to bottom.")
                        .put("field", "appraisal.bands." + i + ".min");
            }
        }

        return null;
    }

    private static boolean validTime(String s)
    {
        if (!s.strip().matches("\\d{1,2}:\\d{2}"))
        {
            return false;
        }

        try
        {
            LocalTime.parse(s.strip(), HH_MM);

            return true;
        }
        catch (DateTimeParseException e)
        {
            return false;
        }
    }

    private static int index(String step)
    {
        try
        {
            return Integer.parseInt(step);
        }
        catch (NumberFormatException e)
        {
            return -1;
        }
    }

    private static String plain(double v)
    {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ------------------------------------------------------------------ the catalogue

    private static JsonObject buildDefaults()
    {
        JsonObject d = new JsonObject(DEFAULTS_JSON);

        d.put("prTitle", PR_TITLE_DEFAULT);

        return d;
    }

    private static Map<String, JsonObject> buildGroups()
    {
        Map<String, JsonObject> groups = new LinkedHashMap<>();

        groups.put(STALENESS, group(STALENESS, "Staleness & capacity", RULES_PERMISSION, null,
                f("staleDays", "Stale after more than", "Working days without assignee activity", INTEGER, 1, 5, 1, " d"),
                f("notStartedPct", "“Not started” after", "% of sprint elapsed with issue still To Do", INTEGER, 20, 80, 5, "%"),
                f("focus", "Focus hours per day", "Hours of task work in a working day", NUMBER, 4, 8, 0.5, " h"),
                f("nudge", "Slack nudges", "Let managers DM a nudge for stale issues", BOOLEAN, 0, 0, 0, ""),
                f("excludeBots", "Exclude bots", "Ignore edits by bots and non-assignees when computing last activity",
                        BOOLEAN, 0, 0, 0, ""),
                f("workday.start", "Working day starts", "Work intervals from Jira count from this time (Asia/Kolkata)",
                        TIME, 0, 0, 0, ""),
                f("workday.end", "Working day ends", "Work intervals from Jira count until this time; at most the hours per day",
                        TIME, 0, 0, 0, "")));

        groups.put(UTILIZATION, group(UTILIZATION, "Utilization bands", RULES_PERMISSION, null,
                f("under", "Under below", "Spare capacity", INTEGER, 30, 80, 5, "%"),
                f("healthy", "Healthy up to", "Then “At capacity” until 100%", INTEGER, 70, 100, 5, "%"),
                f("over", "Over up to", "Above this = Critical", INTEGER, 105, 160, 5, "%")));

        groups.put(EFFORT, group(EFFORT, "Monthly effort & extra-mile points", RULES_PERMISSION, null,
                f("dayHours", "Hours per day", "Converts logged hours into days worked", NUMBER, 6, 9, 0.5, " h"),
                f("unplannedAlert", "Unplanned alert at", "Share of logged time that triggers a planning signal",
                        INTEGER, 10, 60, 5, "%"),
                f("pts.midsprint", "Added mid-sprint (done)", "Points per item", NUMBER, 0, 3, 0.5, ""),
                f("pts.incident", "Incident / hotfix", "Points per item", NUMBER, 0, 4, 0.5, ""),
                f("pts.takeover", "Took over / helped", "Points per item", NUMBER, 0, 3, 0.5, ""),
                f("pts.untracked", "Untracked useful work", "Points per item", NUMBER, 0, 3, 0.5, ""),
                f("pts.reviews", "Reviews for others", "Points per 5 reviews", NUMBER, 0, 2, 0.5, ""),
                f("pts.support", "Extra support ticket (resolved)", "Points per ticket", NUMBER, 0, 3, 0.5, ""),
                f("pts.proactivefix", "Proactive fix of someone else’s bug", "Points per bug", NUMBER, 0, 3, 0.5, ""),
                f("monthCap", "Monthly cap", "Max points per person per month", INTEGER, 2, 12, 1, ""),
                f("bonusPerPoint", "Culture bonus per point", "Added to each credited value (split across values)",
                        NUMBER, 0, 0.2, 0.01, ""),
                f("bonusCap", "Max bonus per value", "Per quarter", NUMBER, 0, 1, 0.05, "")));

        groups.put(VISITS, group(VISITS, "Customer visits", RULES_PERMISSION, null,
                f("pts.visit", "Customer visit (report submitted)", "Points per visit", NUMBER, 0, 4, 0.5, ""),
                f("pts.visitDay", "Each extra day on site", "After the first day", NUMBER, 0, 2, 0.25, ""),
                f("pts.visitWeekend", "Weekend or holiday on site", "Points per day", NUMBER, 0, 3, 0.5, ""),
                f("pts.visitEsc", "Customer escalation", "Extra points for escalation visits", NUMBER, 0, 3, 0.5, ""),
                f("pts.visitCsat", "Customer rated 4.5 or higher", "Added when feedback is recorded", NUMBER, 0, 3, 0.5, ""),
                f("visitCap", "Monthly cap for visit points", "Separate from the extra-mile cap", INTEGER, 2, 16, 1, "")));

        groups.put(ATTENDANCE, group(ATTENDANCE, "Attendance & leave", RULES_PERMISSION, null,
                f("att.officeDays", "Office days a week", "Hybrid policy · on-site customer days count as office",
                        INTEGER, 0, 5, 1, " d"),
                f("att.notice", "Notice for casual leave / comp off", "Applied at least this many days ahead = planned",
                        INTEGER, 0, 7, 1, " d"),
                f("att.noticeLong", "Notice for earned leave", "Longer leave needs more notice", INTEGER, 0, 30, 1, " d"),
                f("att.grace", "Late check-ins allowed a month", "Before punctuality drops", INTEGER, 0, 10, 1, ""),
                f("att.lateAfter", "Late after", "A first check-in after this time is late", TIME, 0, 0, 0, ""),
                f("att.inCulture", "Use attendance in culture values", "Discipline rule h and the Collaboration presence band",
                        BOOLEAN, 0, 0, 0, "")));

        groups.put(PR_STANDARD, group(PR_STANDARD, "Pull request standard (TFS & GitHub)", RULES_PERMISSION, null,
                f("prTitle", "Title pattern", "Regular expression every PR title must match", PATTERN, 0, 0, 0, ""),
                f("prItem", "Require a linked work item", "Jira key or TFS work item", BOOLEAN, 0, 0, 0, ""),
                f("prDesc", "Require the description template", "Summary · How it was tested · Risk / rollback",
                        BOOLEAN, 0, 0, 0, ""),
                f("prThreads", "Blocking threads resolved before merge", "issue, todo and “(blocking)” comments",
                        BOOLEAN, 0, 0, 0, ""),
                f("prMaxLines", "Max lines changed", "Bigger PRs fail the size check", INTEGER, 100, 1000, 50, ""),
                f("prMinAppr", "Min approvals", "Before merge", INTEGER, 1, 3, 1, ""),
                f("prCmtMin", "Min reason length", "Characters after the label for issue / suggestion / todo",
                        INTEGER, 10, 60, 5, ""),
                f("prSla", "First review within", "Hours · shown on each PR", INTEGER, 4, 48, 4, " h"),
                f("prTarget", "Compliance target", "Per person per month", INTEGER, 50, 100, 5, "%")));

        groups.put(TRAINING, group(TRAINING, "Training rating & sign-off", RULES_PERMISSION, null,
                f("trWa", "Assessment", "Weight in the training rating", INTEGER, 0, 60, 5, "%"),
                f("trWm", "Mentor final review", "Weight", INTEGER, 0, 40, 5, "%"),
                f("trWg", "Manager final review", "Weight", INTEGER, 0, 40, 5, "%"),
                f("trWc", "Check-ins verified", "Weight", INTEGER, 0, 30, 5, "%"),
                f("trWp", "Checkpoint reviews", "Weight", INTEGER, 0, 30, 5, "%"),
                f("trPass", "Default pass mark", "For new plans", INTEGER, 40, 100, 5, "%"),
                f("trAssessDays", "Assessment due", "Working days after the training period", INTEGER, 1, 15, 1, " d"),
                f("trSignDays", "Trainee sign-off due", "Working days after the final review", INTEGER, 1, 15, 1, " d")));

        List<JsonObject> culture = new ArrayList<>();

        culture.add(f("dataShare", "Data signals share",
                "Part of each value score from tool data; the rest is the 360° rating (Moral & Ethics is always 360° only, "
                        + "Discipline always data)", INTEGER, 0, 60, 5, "%"));

        for (int i = 0; i < CultureParameters.VALUES.size(); i++)
        {
            culture.add(f("vw." + i, CultureParameters.VALUES.get(i), "Weight of this value", INTEGER, 0, 30, 5, "%"));
        }

        groups.put(CULTURE, group(CULTURE, "Culture value weights", RULES_PERMISSION, "vw", culture.toArray(JsonObject[]::new)));

        groups.put(DISCIPLINE, group(DISCIPLINE, "Discipline rating bands", RULES_PERMISSION, null,
                f("d5", "Score 5 at", "Daily update compliance", INTEGER, 80, 100, 1, "%"),
                f("d4", "Score 4 at", "Daily update compliance", INTEGER, 60, 99, 1, "%"),
                f("d3", "Score 3 at", "Daily update compliance", INTEGER, 40, 95, 1, "%"),
                f("d2", "Score 2 at", "Below this = 1", INTEGER, 20, 90, 1, "%")));

        groups.put(INDEX, group(INDEX, "Performance index weights", RULES_PERMISSION, "weights",
                f("weights.delivery", "Delivery", "", INTEGER, 0, 60, 5, "%"),
                f("weights.quality", "Quality", "", INTEGER, 0, 60, 5, "%"),
                f("weights.culture", "Culture values", "", INTEGER, 0, 60, 5, "%"),
                f("weights.okr", "Goals / OKRs", "", INTEGER, 0, 60, 5, "%"),
                f("weights.growth", "Growth", "", INTEGER, 0, 60, 5, "%")));

        List<JsonObject> appraisal = new ArrayList<>(List.of(
                f("appraisal.w.perf", "Performance index", "Weight in the appraisal score", INTEGER, 0, 100, 1, "%"),
                f("appraisal.w.r360", "360° feedback", "Weight in the appraisal score", INTEGER, 0, 100, 1, "%"),
                f("appraisal.w.mgr", "Manager review", "Weight in the appraisal score", INTEGER, 0, 100, 1, "%"),
                f("appraisal.w.disc", "Discipline", "Weight in the appraisal score", INTEGER, 0, 100, 1, "%"),
                f("appraisal.max", "Max increment", "No increment above this", NUMBER, 1, 50, 0.5, "%"),
                f("appraisal.budget", "Budget", "Share of payroll", NUMBER, 0, 50, 0.5, "%"),
                f("appraisal.minMonths", "Min months to be eligible", "Since joining", INTEGER, 0, 24, 1, "")));

        JsonArray bands = DEFAULTS.getJsonObject("appraisal").getJsonArray("bands");

        for (int i = 0; i < bands.size(); i++)
        {
            String name = bands.getJsonObject(i).getString("name");

            if (i < bands.size() - 1)
            {
                appraisal.add(f("appraisal.bands." + i + ".min", name + " from score", "Lowest appraisal score of the band",
                        NUMBER, 0, 5, 0.1, ""));
            }

            appraisal.add(f("appraisal.bands." + i + ".lo", name + " min increment", "", NUMBER, 0, 50, 0.5, "%"));
            appraisal.add(f("appraisal.bands." + i + ".hi", name + " max increment", "", NUMBER, 0, 50, 0.5, "%"));
        }

        groups.put(APPRAISAL, group(APPRAISAL, "Appraisal standards", APPRAISAL_PERMISSION, "appraisal.w",
                appraisal.toArray(JsonObject[]::new)));

        return Collections.unmodifiableMap(groups);
    }

    private static Map<String, JsonObject> indexFields()
    {
        Map<String, JsonObject> fields = new LinkedHashMap<>();

        GROUPS.values().forEach(g -> g.getJsonArray("fields").forEach(f ->
        {
            JsonObject field = (JsonObject) f;

            if (fields.put(field.getString("path"), field) != null)
            {
                throw new IllegalStateException("Rule " + field.getString("path") + " is in two groups");
            }

            if (read(DEFAULTS, field.getString("path")) == null)
            {
                throw new IllegalStateException("Rule " + field.getString("path") + " has no default");
            }
        }));

        return Collections.unmodifiableMap(fields);
    }

    private static JsonObject group(String key, String label, String permission, String totalOf, JsonObject... fields)
    {
        JsonArray list = new JsonArray();

        for (JsonObject f : fields)
        {
            f.put("group", key);
            f.put("default", read(DEFAULTS, f.getString("path")));
            list.add(f);
        }

        return new JsonObject()
                .put("key", key)
                .put("label", label)
                .put("permission", permission)
                .put("totalOf", totalOf)
                .put("fields", list);
    }

    private static JsonObject f(String path, String label, String help, String type, double min, double max, double step,
                                String unit)
    {
        JsonObject f = new JsonObject()
                .put("path", path)
                .put("label", label)
                .put("help", help)
                .put("type", type);

        if (INTEGER.equals(type) || NUMBER.equals(type))
        {
            f.put("min", min).put("max", max).put("step", step).put("unit", unit);
        }

        return f;
    }
}
