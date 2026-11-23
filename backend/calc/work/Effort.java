package com.pulse.calc.work;

import com.pulse.calc.core.CalendarFormat;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Effort and extra-mile points (task 11.1, spec work/effort-extra-mile, reference section 7.4), ported
 * from the effort part of the prototype's {@code monthStats}, {@code itemPts} and the requester
 * ranking of {@code pEffortTeam} (app.js ~429-503), plus the detection of unplanned and extra work from
 * the Track 2 tables. Pure functions over plain data.
 *
 * <p>An item is {@code {id, kind, ref, title, requestedBy, detectionNote, evidenceSource, occurredOn,
 * hours, isDone, valuesCredited, state}} (the {@code extra_items} row). Points come from the rule
 * {@code pts} per kind; reviews earn {@code pts.reviews} per 5 reviews; a visit's points come from
 * Track 3 (the {@code visitPoints} map, item ref to points).
 */
public final class Effort
{

    public static final String MIDSPRINT = "midsprint";

    public static final String INCIDENT = "incident";

    public static final String TAKEOVER = "takeover";

    public static final String HELP = "help";

    public static final String UNTRACKED = "untracked";

    public static final String REVIEWS = "reviews";

    public static final String SUPPORT = "support";

    public static final String PROACTIVE_FIX = "proactivefix";

    public static final String VISIT = "visit";

    /**
     * Reviews for others earn their points per this many reviews.
     */
    public static final int REVIEWS_PER_POINT_STEP = 5;

    /**
     * The values each kind credits (reference section 7.4); a visit's values follow its purpose.
     */
    public static final Map<String, List<String>> CREDITS = Map.of(
            MIDSPRINT, List.of("Adaptability", "Agility in Execution"),
            INCIDENT, List.of("Resilience", "Outcome Ownership"),
            TAKEOVER, List.of("Collaboration", "Outcome Ownership"),
            HELP, List.of("Collaboration"),
            UNTRACKED, List.of("Resourcefulness"),
            REVIEWS, List.of("Collaboration"),
            SUPPORT, List.of("Outcome Ownership", "Collaboration"),
            PROACTIVE_FIX, List.of("Problem Solving", "Collaboration"));

    public static final String PLANNING_MESSAGE = "This is a planning signal for the manager, not a performance problem";

    private Effort()
    {
    }

    /**
     * {@code itemPts}: a visit's points from Track 3; otherwise 0 until the work is done, then the kind's
     * points ({@code reviews}: per 5 reviews, the count being the number at the start of {@code ref}).
     */
    public static double points(JsonObject item, JsonObject rules, JsonObject visitPoints)
    {
        String kind = item.getString("kind");

        if (VISIT.equals(kind))
        {
            Object points = visitPoints == null ? null : visitPoints.getValue(item.getString("ref"));

            return points instanceof Number n ? n.doubleValue() : 0;
        }

        if (!item.getBoolean("isDone", false))
        {
            return 0;
        }

        Object value = rules.getJsonObject("pts", new JsonObject()).getValue(kind);

        double points = value instanceof Number n ? n.doubleValue() : 0;

        return REVIEWS.equals(kind) ? points * Math.floor(leadingInt(item.getString("ref")) / (double) REVIEWS_PER_POINT_STEP) : points;
    }

    /**
     * Below this share of {@code dayHours} a logged day reads as short ({@code calendar}).
     */
    public static final double SHORT_DAY_PCT = 50;

    /**
     * {@code calendar}: the Daily log of a period, one cell per day from the day facts of
     * {@code work.periodWork}: before joining, future, holiday, leave, customer visit, weekend work or a
     * day off, code only (commits, no worklog), else a working day by hours logged against {@code dayHours}
     * (short below {@value #SHORT_DAY_PCT}%, normal to 100%, long to rule {@code over}, then very long).
     * {@code tone} is the heat tone of the cell, '' for leave, holiday and visit.
     *
     * @return {@code [{date, kind, label, hours, tone}]}
     */
    public static JsonArray dailyLog(JsonArray facts, LocalDate today, JsonObject rules)
    {
        double dayHours = rules.getDouble("dayHours", 8.0);

        double over = rules.getDouble("over", 120.0);

        JsonArray out = new JsonArray();

        for (Object item : facts)
        {
            JsonObject f = (JsonObject) item;

            LocalDate date = LocalDate.parse(f.getString("date"));

            double hours = Workload.orZero(Workload.number(f, "hours"));

            String label = (hours == Math.rint(hours) ? String.valueOf((long) hours) : String.valueOf(hours)) + "h";

            String kind;

            String tone = "";

            if (f.getBoolean("beforeJoining", false))
            {
                kind = "prejoin";

                label = "";
            }
            else if (date.isAfter(today))
            {
                kind = "future";

                label = "";
            }
            else if (f.getBoolean("holiday", false))
            {
                kind = "holiday";

                label = "Hol";
            }
            else if (f.getBoolean("leave", false))
            {
                kind = "leave";

                label = "Leave";
            }
            else if (f.getBoolean("visit", false))
            {
                kind = "visit";

                label = "Visit";
            }
            else if (!f.getBoolean("weekday", true))
            {
                kind = hours > 0 ? "weekend" : "off";

                tone = hours > 0 ? "accent" : "";

                label = hours > 0 ? label : "";
            }
            else if (hours == 0 && f.getBoolean("commits", false))
            {
                kind = "codeOnly";

                tone = "info";

                label = "Code";
            }
            else
            {
                double pct = hours / dayHours * 100;

                kind = pct > 100 ? "long" : "work";

                tone = pct < SHORT_DAY_PCT ? "info" : pct <= 100 ? "ok" : pct <= over ? "warn" : "danger";
            }

            out.add(new JsonObject().put("date", f.getString("date")).put("kind", kind).put("label", label).put("hours", hours)
                    .put("tone", tone));
        }

        return out;
    }

    /**
     * The points each item scores for the culture bonus (spec work/effort-extra-mile "Caps"): confirmed
     * items count in the order they were confirmed ({@code decidedAt}, then date) until the person's
     * month of the work reaches {@code monthCap}, visits separately up to {@code visitCap}; the excess
     * does not score, so a later confirmation never takes points from an earlier one. Other items keep their points (the
     * bonus reads confirmed items only). Deviation: the prototype's {@code valueBonus} ignores the
     * monthly caps, which only its {@code monthStats} totals show; the spec's "the excess does not score"
     * wins.
     *
     * @param items {@code {id, occurredOn, decidedAt, kind, ref, isDone, state, valuesCredited}} of one person
     * @return per item, in the order given: {@code {state, points, values, capped}}, where {@code capped}
     *         is the points the cap took away
     */
    public static JsonArray scored(List<JsonObject> items, JsonObject rules, JsonObject visitPoints)
    {
        double monthCap = rules.getDouble("monthCap", 6.0);

        double visitCap = rules.getDouble("visitCap", 8.0);

        List<Integer> order = new ArrayList<>();

        for (int i = 0; i < items.size(); i++)
        {
            order.add(i);
        }

        order.sort(Comparator.comparing((Integer i) -> items.get(i).getValue("decidedAt") == null ? "~"
                        : String.valueOf(items.get(i).getValue("decidedAt")))
                .thenComparing(i -> String.valueOf(items.get(i).getValue("occurredOn")))
                .thenComparing(i -> String.valueOf(items.get(i).getValue("id"))));

        Map<String, Double> used = new LinkedHashMap<>();

        double[] points = new double[items.size()];

        double[] taken = new double[items.size()];

        for (int i : order)
        {
            JsonObject item = items.get(i);

            double raw = points(item, rules, visitPoints);

            points[i] = raw;

            if (!"confirmed".equals(item.getString("state")) || raw <= 0)
            {
                continue;
            }

            boolean visit = VISIT.equals(item.getString("kind"));

            String month = String.valueOf(item.getValue("occurredOn"));

            String bucket = (month.length() >= 7 ? month.substring(0, 7) : month) + (visit ? ":visit" : ":work");

            double before = used.getOrDefault(bucket, 0.0);

            double counted = Math.max(0, Math.min(raw, (visit ? visitCap : monthCap) - before));

            used.put(bucket, before + counted);

            points[i] = counted;

            taken[i] = raw - counted;
        }

        JsonArray out = new JsonArray();

        for (int i = 0; i < items.size(); i++)
        {
            JsonObject item = items.get(i);

            out.add(new JsonObject()
                    .put("state", item.getString("state"))
                    .put("points", points[i])
                    .put("values", item.getJsonArray("valuesCredited", new JsonArray()))
                    .put("capped", taken[i]));
        }

        return out;
    }

    /**
     * The effort totals of {@code monthStats} for a period's items and hours logged: unplanned,
     * untracked, review and visit hours, planned hours (the rest), confirmed and pending points within
     * the monthly caps ({@code monthCap}, and {@code visitCap} for visits apart), whether a cap was hit,
     * confirmed visit points and the unplanned share %.
     */
    public static JsonObject totals(List<JsonObject> items, double logged, JsonObject rules, JsonObject visitPoints)
    {
        double unplanned = 0;

        double untracked = 0;

        double reviews = 0;

        double visits = 0;

        List<JsonObject> work = new ArrayList<>();

        List<JsonObject> visitItems = new ArrayList<>();

        for (JsonObject item : items)
        {
            String kind = item.getString("kind");

            double hours = Workload.orZero(Workload.number(item, "hours"));

            if (UNTRACKED.equals(kind))
            {
                untracked += hours;
            }
            else if (REVIEWS.equals(kind))
            {
                reviews += hours;
            }
            else if (VISIT.equals(kind))
            {
                visits += hours;
            }
            else
            {
                unplanned += hours;
            }

            (VISIT.equals(kind) ? visitItems : work).add(item);
        }

        double[] workPoints = capped(work, rules.getDouble("monthCap", 6.0), rules, visitPoints);

        double[] visitPts = capped(visitItems, rules.getDouble("visitCap", 8.0), rules, visitPoints);

        return new JsonObject()
                .put("unplanned", unplanned)
                .put("untracked", untracked)
                .put("reviews", reviews)
                .put("visitH", visits)
                .put("planned", Math.max(0, logged - unplanned - untracked - reviews - visits))
                .put("ptsConf", Math.round((workPoints[0] + visitPts[0]) * 100) / 100.0)
                .put("ptsPend", Math.round((workPoints[1] + visitPts[1]) * 100) / 100.0)
                .put("capped", workPoints[2] > 0 || visitPts[2] > 0)
                .put("visitPts", visitPts[0])
                .put("share", logged == 0 ? 0 : (int) Math.round((unplanned + untracked) / logged * 100));
    }

    /**
     * "Who gives unplanned work": requesters of every item except untracked work and reviews, with
     * their item count and hours, most hours first.
     */
    public static JsonArray requesters(List<JsonObject> items)
    {
        Map<String, JsonObject> by = new LinkedHashMap<>();

        for (JsonObject item : items)
        {
            String kind = item.getString("kind");

            if (UNTRACKED.equals(kind) || REVIEWS.equals(kind))
            {
                continue;
            }

            JsonObject row = by.computeIfAbsent(item.getString("requestedBy"),
                    k -> new JsonObject().put("by", k).put("items", 0).put("hours", 0.0));

            row.put("items", row.getInteger("items") + 1)
                    .put("hours", row.getDouble("hours") + Workload.orZero(Workload.number(item, "hours")));
        }

        List<JsonObject> rows = new ArrayList<>(by.values());

        rows.sort(Comparator.comparingDouble((JsonObject r) -> r.getDouble("hours")).reversed());

        return new JsonArray(new ArrayList<>(rows));
    }

    /**
     * The planning guardrail: an unplanned share at or above {@code unplannedAlert} is a signal for the
     * manager, not a performance issue for the employee.
     */
    public static boolean planningSignal(int share, JsonObject rules)
    {
        return share >= rules.getInteger("unplannedAlert", 30);
    }

    // ------------------------------------------------------------------ detection

    /**
     * Added mid-sprint: the issue's Sprint field set to the sprint after it started.
     */
    public static JsonObject midSprint(JsonObject issue, String sprintName, LocalDate sprintStart, Instant setAt,
                                       String requestedBy, double hours)
    {
        LocalDate day = CalendarFormat.companyDate(setAt);

        return item(MIDSPRINT, issue.getString("key"), issue.getString("summary"), requestedBy,
                "Jira changelog: Sprint field set to " + sprintName + " on " + CalendarFormat.date(day) + " "
                        + CalendarFormat.time(setAt) + " (sprint started " + CalendarFormat.date(sprintStart) + ")",
                "Jira", day, hours, "Done".equals(issue.getString("status")));
    }

    /**
     * Took over: the assignee changed to this person in the middle of the sprint.
     */
    public static JsonObject takeover(JsonObject issue, String from, String to, Instant changedAt, String requestedBy,
                                      double hours)
    {
        LocalDate day = CalendarFormat.companyDate(changedAt);

        return item(TAKEOVER, issue.getString("key"), issue.getString("summary") + " (from " + from + ")", requestedBy,
                "Jira changelog: assignee " + from + " → " + to + " on " + CalendarFormat.date(day),
                "Jira", day, hours, "Done".equals(issue.getString("status")));
    }

    /**
     * Helped on another's issue: commits by this person on an issue assigned to someone else.
     */
    public static JsonObject help(JsonObject issue, String helper, String assignee, int commits, LocalDate day, double hours)
    {
        return item(HELP, issue.getString("key"), issue.getString("summary"), assignee + " · teammate",
                "GitHub: " + commits + " commit" + (commits == 1 ? "" : "s") + " by " + helper + " on branch "
                        + issue.getString("key") + " (assignee " + assignee + ")",
                "GitHub", day, hours, true);
    }

    /**
     * Untracked useful work: a merged PR with no work item key.
     */
    public static JsonObject untracked(JsonObject pr, LocalDate merged, double hours)
    {
        return item(UNTRACKED, "PR " + pr.getString("number"), pr.getString("title"), "Self-initiated",
                "GitHub PR merged " + CalendarFormat.date(merged) + " with no Jira key in branch, title or commits",
                pr.getString("tool", "GitHub"), merged, hours, true);
    }

    /**
     * Reviews for others: one item per period with the number of reviews on PRs others authored.
     */
    public static JsonObject reviews(int count, double medianTurnaroundHours, LocalDate day, double hours)
    {
        return item(REVIEWS, count + " PRs", "Code reviews on teammates’ PRs", "Team",
                "GitHub: " + count + " reviews on PRs authored by others, median turnaround "
                        + Math.round(medianTurnaroundHours) + " h",
                "GitHub", day, hours, true);
    }

    /**
     * Extra support ticket: a ticket taken beyond sprint work and resolved.
     */
    public static JsonObject support(JsonObject ticket)
    {
        String customer = ticket.getString("customer");

        String how = ticket.getString("howTaken", "Assigned");

        Object csat = ticket.getValue("csat");

        return item(SUPPORT, ticket.getString("key"),
                ticket.getString("title") + (customer == null ? "" : " (" + customer + ")"),
                how.startsWith("Self-picked") ? "Self-picked from support queue" : how,
                "Support desk: " + how + (csat == null ? "" : " · CSAT " + csat + "/5"),
                "Support desk", LocalDate.parse(ticket.getString("openedOn")),
                Workload.orZero(Workload.number(ticket, "hours")), "Resolved".equals(ticket.getString("status")));
    }

    /**
     * Proactive fix: a bug in someone else's code this person assigned to themself and resolved.
     */
    public static JsonObject proactiveFix(JsonObject bug, String codeOwner, double hours)
    {
        LocalDate resolved = bug.getString("resolvedOn") == null ? null : LocalDate.parse(bug.getString("resolvedOn"));

        return item(PROACTIVE_FIX, bug.getString("key"), bug.getString("title") + " (code by " + codeOwner + ")",
                "Self-assigned from bug backlog",
                "Jira: resolved " + (resolved == null ? "—" : CalendarFormat.date(resolved))
                        + (bug.getString("originRef") == null ? "" : "; bug linked to " + bug.getString("originRef")),
                "Jira + GitHub", resolved == null ? LocalDate.parse(bug.getString("reportedOn")) : resolved, hours,
                "Resolved".equals(bug.getString("status")));
    }

    /**
     * Incident or hotfix: an incident issue this person worked on, with its linked fix.
     */
    public static JsonObject incident(JsonObject issue, String linkedPr, double hours)
    {
        LocalDate day = issue.getString("resolvedOn") == null ? LocalDate.parse(issue.getString("startedOn"))
                : LocalDate.parse(issue.getString("resolvedOn"));

        return item(INCIDENT, issue.getString("key"), issue.getString("summary"), "On-call · incident",
                "Incident " + issue.getString("key") + (linkedPr == null ? "" : " + PR " + linkedPr + " merged"),
                linkedPr == null ? "Jira" : "Jira + GitHub", day, hours, "Done".equals(issue.getString("status")));
    }

    private static JsonObject item(String kind, String ref, String title, String requestedBy, String note, String source,
                                   LocalDate day, double hours, boolean done)
    {
        return new JsonObject()
                .put("kind", kind)
                .put("ref", ref)
                .put("title", title)
                .put("requestedBy", requestedBy)
                .put("detectionNote", note)
                .put("evidenceSource", source)
                .put("occurredOn", day.toString())
                .put("hours", hours)
                .put("isDone", done)
                .put("valuesCredited", new JsonArray(new ArrayList<Object>(CREDITS.getOrDefault(kind, List.of()))))
                .put("state", "pending");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * {@code cp}: [confirmed points within the cap, pending points that still fit, 1 when confirmed plus
     * pending went over the cap].
     */
    private static double[] capped(List<JsonObject> items, double cap, JsonObject rules, JsonObject visitPoints)
    {
        double confirmed = 0;

        double pending = 0;

        for (JsonObject item : items)
        {
            double points = points(item, rules, visitPoints);

            if ("confirmed".equals(item.getString("state")))
            {
                confirmed += points;
            }
            else if ("pending".equals(item.getString("state")))
            {
                pending += points;
            }
        }

        double kept = Math.min(confirmed, cap);

        return new double[] {kept, Math.max(0, Math.min(confirmed + pending, cap) - kept), confirmed + pending > cap ? 1 : 0};
    }

    /**
     * {@code parseInt(ref, 10) || 0}: the whole number at the start of {@code ref}.
     */
    private static int leadingInt(String ref)
    {
        if (ref == null)
        {
            return 0;
        }

        String text = ref.strip();

        int end = 0;

        if (end < text.length() && (text.charAt(end) == '-' || text.charAt(end) == '+'))
        {
            end++;
        }

        while (end < text.length() && Character.isDigit(text.charAt(end)))
        {
            end++;
        }

        try
        {
            return Integer.parseInt(text.substring(0, end));
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }
}
