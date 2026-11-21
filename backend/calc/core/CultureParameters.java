package com.pulse.calc.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;
import java.util.Map;

/**
 * The 35 data parameters of the culture values (task 4.4), as defined in the prototype's
 * {@code valData} (app.js line ~2202): name, source, how it is measured, scoring bands, direction,
 * weight, unit, whether 0 means "no opportunity", and how the raw value is displayed.
 *
 * <p>12 parameters ({@code "format": "signal"}) have no source table in v1. Their raw value and
 * display string come from {@code culture_signals} ({@code is_simulated = true}) and are never
 * recomputed. The other 23 are measured from the work, PR, bug, visit, attendance, kudos, pulse and
 * training data and arrive as inputs (see {@link CultureScore#valData}).
 *
 * <p>Each definition is a {@link JsonObject}:
 * <pre>{@code
 * {"key": "commitment_met", "name": "Commitment met", "source": "Jira · last 3 sprints", "how": "...",
 *  "bands": [95, 85, 70, 55], "higherIsBetter": true, "weight": 35, "unit": "%",
 *  "zeroIsNoOpportunity": false, "format": "pct"}
 * }</pre>
 * Formats: {@code int} ("3"), {@code pct} ("82%"), {@code of5} ("4.3 / 5"), {@code days} ("2.5 d"),
 * {@code lines} ("240 lines"), {@code openBugs} ("2 (1 high)"), {@code pctOf} ("92% of 12"),
 * {@code of} ("3 of 6"), {@code pctInSprints} ("85% in 2 sprints") and {@code signal} (stored display).
 * {@code {staleDays}} and {@code {officeDays}} in {@code how} are filled from the rules.
 */
public final class CultureParameters
{
    /** The 11 culture values, in the order of every per-value array (rule {@code vw}, ratings, scores). */
    public static final List<String> VALUES = List.of("Outcome Ownership", "Moral & Ethics", "Decision Making",
            "Problem Solving", "Risk Appetite", "Adaptability", "Agility in Execution", "Collaboration", "Resilience",
            "Resourcefulness", "Discipline");

    public static final String MORAL_AND_ETHICS = "Moral & Ethics";

    public static final String DISCIPLINE = "Discipline";

    private static final Map<String, JsonArray> BY_VALUE = Map.of(
            "Outcome Ownership", list(
                    p("commitment_met", "Commitment met", "Jira · last 3 sprints",
                            "Done story points ÷ committed story points, averaged over the last 3 finished sprints",
                            b(95, 85, 70, 55), true, 35, "%", false, "pct"),
                    p("issues_carried_over", "Issues carried over", "Jira · last 3 sprints",
                            "Issues still open when the sprint closed and moved to the next sprint",
                            b(0, 1, 2, 4), false, 20, "", false, "int"),
                    p("open_bugs_own_code", "Open bugs traced to own code", "Jira + git blame",
                            "Open bugs whose fixing PR changes lines this person wrote (see Bugs & support)",
                            b(0, 1, 2, 3), false, 20, "", false, "openBugs"),
                    p("manager_follow_ups", "Follow-ups needed from the manager", "1:1 action items · Q3",
                            "Times the manager had to chase an agreed action",
                            b(0, 1, 2, 3), false, 10, "", false, "signal"),
                    p("customer_visit_rating", "Customer visit rating", "Customer visits · Q3",
                            "Average CSAT recorded by the account owner after on-site visits",
                            b(4.8, 4.3, 3.8, 3), true, 15, "", false, "of5")),
            MORAL_AND_ETHICS, new JsonArray(),
            "Decision Making", list(
                    p("decisions_documented", "Decisions documented", "Confluence · Q3",
                            "ADRs and design docs authored with options and trade-offs",
                            b(5, 4, 2, 1), true, 40, "", false, "signal"),
                    p("median_time_to_decide", "Median time to decide", "Jira · Q3",
                            "Working days an issue sat in “Waiting for decision” when this person owned the decision",
                            b(1, 2, 3, 4), false, 35, " d", false, "signal"),
                    p("decisions_reversed", "Decisions reversed", "Confluence · Q3",
                            "ADRs superseded within 90 days",
                            b(0, 1, 1, 2), false, 25, "", false, "signal")),
            "Problem Solving", list(
                    p("bugs_fixed_without_reopening", "Bugs fixed without reopening", "Jira · last 6 sprints",
                            "Bugs resolved by this person that were not reopened ÷ all bugs they resolved",
                            b(98, 92, 85, 75), true, 35, "%", false, "pctOf"),
                    p("others_bugs_fixed_proactively", "Others’ bugs fixed proactively", "Jira · Q3",
                            "Bugs in someone else’s code that this person self-assigned and resolved",
                            b(3, 2, 1, 0), true, 30, "", true, "int"),
                    p("root_cause_analyses_written", "Root-cause analyses written", "Confluence · Q3",
                            "RCA documents linked to incidents or bugs",
                            b(3, 2, 1, 0), true, 20, "", true, "signal"),
                    p("support_tickets_resolved", "Support tickets resolved", "Support desk · Q3",
                            "Extra support tickets taken and resolved",
                            b(4, 3, 2, 1), true, 15, "", true, "int")),
            "Risk Appetite", list(
                    p("spikes_and_experiments_run", "Spikes and experiments run", "Jira · Q3",
                            "Issues of type Spike / Experiment owned and closed",
                            b(3, 2, 1, 0), true, 40, "", true, "signal"),
                    p("ideas_proposed_piloted", "Ideas proposed → piloted", "Idea board · Q3",
                            "Ideas this person proposed that reached a pilot",
                            b(3, 2, 1, 0), true, 35, "", true, "signal"),
                    p("risks_raised_in_planning", "Risks raised in planning", "Jira label “risk” · Q3",
                            "Risks logged during sprint planning before work started",
                            b(4, 3, 2, 1), true, 25, "", true, "signal")),
            "Adaptability", list(
                    p("mid_sprint_work_absorbed", "Mid-sprint work absorbed", "Jira changelog · Q3",
                            "Issues added to an active sprint that this person finished",
                            b(4, 3, 2, 1), true, 35, "", true, "int"),
                    p("areas_worked_in", "Areas worked in", "PRs + Jira components · 6 months",
                            "Product areas with merged PRs or finished issues",
                            b(4, 3, 2, 1), true, 25, "", false, "of"),
                    p("training_rating", "Training rating", "Training plans",
                            "Overall training rating across plans (assessment, mentor and manager reviews, check-ins)",
                            b(4.5, 3.75, 3, 2.25), true, 20, "", false, "of5"),
                    p("time_to_productivity_new_tool", "Time to productivity on a new tool", "Onboarding checklist · Q3",
                            "Working days from first access to first real use of a new tool",
                            b(4, 7, 10, 13), false, 20, " d", false, "signal")),
            "Agility in Execution", list(
                    p("median_cycle_time", "Median cycle time", "Jira · last 3 sprints",
                            "Working days from In Progress to Done, median over done issues",
                            b(2, 3, 4.5, 6), false, 35, " d", false, "days"),
                    p("stale_share", "Stale share of open work", "Jira · active sprint",
                            "Open issues with no update for more than {staleDays} working days ÷ open issues",
                            b(0, 15, 30, 50), false, 25, "%", false, "pct"),
                    p("median_pr_size", "Median PR size", "TFS + GitHub · last month",
                            "Lines changed per pull request, median",
                            b(200, 300, 400, 600), false, 20, "", false, "lines"),
                    p("logged_vs_estimate", "Logged ÷ estimate", "Jira · last 3 sprints",
                            "Hours logged ÷ original estimate on done issues (rework and underestimation)",
                            b(110, 125, 140, 160), false, 20, "%", false, "pct")),
            "Collaboration", list(
                    p("pr_reviews_given", "PR reviews given", "TFS + GitHub · last month",
                            "Pull requests of others this person reviewed",
                            b(15, 10, 6, 3), true, 25, "", false, "int"),
                    p("review_comments_standard", "Review comments to the standard", "TFS + GitHub · last month",
                            "Comments with a label and a reason ÷ all review comments",
                            b(85, 70, 55, 40), true, 20, "%", false, "pct"),
                    p("kudos_received", "Kudos received", "Slack /kudos · Q3",
                            "Kudos from colleagues",
                            b(3, 2, 1, 0), true, 20, "", true, "int"),
                    p("helped_others", "Helped others", "Jira + GitHub + Training · Q3",
                            "Issues taken over or helped on, plus active mentees",
                            b(3, 2, 1, 0), true, 20, "", true, "int"),
                    p("cross_team_issues", "Cross-team issues", "Jira · Q3",
                            "Issues finished for other teams",
                            b(4, 3, 2, 1), true, 15, "", true, "signal"),
                    p("office_presence", "Office and on-site presence", "Attendance upload · Q3",
                            "Office and customer on-site days ÷ days the hybrid policy asks for ({officeDays} a week, fewer in leave weeks)",
                            b(95, 85, 70, 55), true, 15, "%", false, "pct")),
            "Resilience", list(
                    p("incidents_handled", "Incidents and escalations handled", "PagerDuty + customer visits · Q3",
                            "Incidents, hotfixes and customer escalations this person handled",
                            b(3, 2, 1, 0), true, 35, "", true, "int"),
                    p("delivery_overloaded_sprints", "Delivery in overloaded sprints", "Jira · last 6 sprints",
                            "Commitment met in sprints where utilization was over 100%",
                            b(90, 80, 65, 50), true, 40, "%", false, "pctInSprints"),
                    p("mood_heavy_weeks", "Mood in heavy weeks", "Weekly pulse · 6 weeks",
                            "Average mood in weeks where workload was 4 or 5",
                            b(4, 3.5, 3, 2.5), true, 25, "", false, "of5")),
            "Resourcefulness", list(
                    p("useful_tools_scripts", "Useful tools and scripts", "GitHub PRs with no Jira key · Q3",
                            "Internal tools, scripts and dashboards merged outside sprint scope",
                            b(3, 2, 1, 0), true, 35, "", true, "int"),
                    p("docs_templates_prompts_shared", "Docs, templates and prompts shared", "Confluence · Q3",
                            "Pages others reuse (templates, how-tos, AI prompts)",
                            b(6, 4, 2, 1), true, 30, "", true, "signal"),
                    p("hours_saved_by_automation", "Hours saved by automation", "Reported with the tool · Q3",
                            "Team hours saved per quarter, as reported when the tool was shared",
                            b(30, 20, 10, 3), true, 35, " h", true, "signal")));

    private CultureParameters()
    {
    }

    /** The parameter definitions of a value (a copy); none for Moral & Ethics and Discipline. */
    public static JsonArray of(String value)
    {
        JsonArray defs = BY_VALUE.get(value);

        return defs == null ? new JsonArray() : defs.copy();
    }

    /** The keys of the 12 parameters read from {@code culture_signals}. */
    public static List<String> signalKeys()
    {
        return VALUES.stream()
                .flatMap(v -> of(v).stream().map(JsonObject.class::cast))
                .filter(d -> "signal".equals(d.getString("format")))
                .map(d -> d.getString("key"))
                .toList();
    }

    private static JsonObject p(String key, String name, String source, String how, JsonArray bands,
                                boolean higherIsBetter, int weight, String unit, boolean zeroIsNoOpportunity,
                                String format)
    {
        return new JsonObject()
                .put("key", key)
                .put("name", name)
                .put("source", source)
                .put("how", how)
                .put("bands", bands)
                .put("higherIsBetter", higherIsBetter)
                .put("weight", weight)
                .put("unit", unit)
                .put("zeroIsNoOpportunity", zeroIsNoOpportunity)
                .put("format", format);
    }

    private static JsonArray b(double... th)
    {
        JsonArray out = new JsonArray();

        for (double t : th)
        {
            out.add(t == Math.rint(t) ? (Object) (int) t : (Object) t);
        }

        return out;
    }

    private static JsonArray list(JsonObject... defs)
    {
        JsonArray out = new JsonArray();

        for (JsonObject d : defs)
        {
            out.add(d);
        }

        return out;
    }
}
