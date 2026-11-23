package com.pulse.calc.work;

import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.PeriodCalendar;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Effort and extra-mile formulas of {@code calc/work} (task 11.1): the loaders around {@link Effort}.
 * Items come from {@code extra_items}; a visit item's points come from Track 3's optional port
 * {@code people.visitPointsByRef {person}} ({@code {"VIS-101": 2.5}}), and read as 0 until it exists.
 * It is not Track 3's per-visit {@code people.visitPoints {visit}}, which takes a visit id.
 *
 * <p>Detection ({@code work.extraDetected}) reads the Track 2 tables with these event conventions,
 * which ingest (task 5.4) follows: a {@code sprint_change} event's {@code to_value} is the sprint key
 * ({@code S43}); an {@code assignee_change} event's {@code from_value} and {@code to_value} are the
 * people ids when matched, otherwise the names. Bot events never count.
 */
public final class EffortFormulas implements FormulaModule
{

    /**
     * Extra-mile items changed (detection, confirm or reject, task 11.2).
     */
    public static final String EXTRA_ITEMS = "work.extraItems";

    public static final String EFFORT = "work.effort";

    public static final String TEAM = "work.effortTeam";

    public static final String CULTURE_ITEMS = "work.extraMileItems";

    public static final String DETECTED = "work.extraDetected";

    private static final String ITEM_ROW = """
            SELECT x.id, x.detection_key AS "detectionKey", x.kind, x.ref, x.title, x.requested_by AS "requestedBy",
                   x.detection_note AS "detectionNote", x.evidence_source AS "evidenceSource", x.occurred_on AS "occurredOn",
                   x.hours, x.is_done AS "isDone", x.values_credited AS "valuesCredited", x.state,
                   x.decided_at AS "decidedAt"
            FROM extra_items x
            """;

    @Override
    public String id()
    {
        return "work";
    }

    @Override
    public List<Formula> formulas()
    {
        Set<String> topics = Set.of(EXTRA_ITEMS, WorkloadFormulas.ISSUES, WorkloadFormulas.WORKLOGS, WorkloadFormulas.BUGS,
                PrFormulas.PULL_REQUESTS, CalcService.PEOPLE, CalcService.SPRINTS, CalcService.importTopic("visits"));

        return List.of(
                new Formula(EFFORT, Set.of("person", "period"), topics, Set.of(PeriodCalendar.SPRINT), safe(EffortFormulas::effort)),
                Formula.of(TEAM, Set.of("team", "period"), topics, safe(EffortFormulas::team)),
                Formula.of(CULTURE_ITEMS, Set.of("person", "period"), topics, safe(EffortFormulas::cultureItems)),
                Formula.of(DETECTED, Set.of("person", "period"), topics, safe(EffortFormulas::detected)));
    }

    // ------------------------------------------------------------------ formulas

    /**
     * {@code work.effort {person, period}}: the period's time ({@code work.periodWork}), its items, the
     * effort totals of {@link Effort#totals}, the planning signal and who gave the unplanned work.
     */
    private static Future<Object> effort(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        return period(ctx, params).compose(period ->
        {
            Future<Object> work = ctx.get(WorkloadFormulas.PERIOD_WORK, params);

            Future<JsonArray> items = WorkSql.rows(ctx, ITEM_ROW + " WHERE x.person_id = $1 AND x.occurred_on BETWEEN $2 AND $3 "
                    + "ORDER BY x.occurred_on, x.id", Tuple.of(person, from(period), to(period)));

            Future<Object> visits = ctx.optional("people.visitPointsByRef", new JsonObject().put("person", person.toString()));

            return Future.all(work, items, visits).map(all ->
            {
                JsonObject time = (JsonObject) work.result();

                JsonObject visitPoints = visits.result() instanceof JsonObject v ? v : null;

                List<JsonObject> list = list(items.result());

                JsonObject totals = Effort.totals(list, time.getDouble("logged"), ctx.rules(), visitPoints);

                JsonArray withPoints = new JsonArray();

                for (JsonObject item : list)
                {
                    withPoints.add(item.copy().put("points", Effort.points(item, ctx.rules(), visitPoints)));
                }

                return (Object) time.copy().mergeIn(totals)
                        .put("items", withPoints)
                        .put("planning", Effort.planningSignal(totals.getInteger("share"), ctx.rules()))
                        .put("requesters", Effort.requesters(list));
            });
        });
    }

    /**
     * {@code work.effortTeam {team, period}}: one row per person of the team
     * ({@code people.department}) with the team totals, the items awaiting review and who gives
     * unplanned work across the team.
     */
    private static Future<Object> team(CalcContext ctx, JsonObject params)
    {
        String team = params.getString("team");

        if (team == null || team.isBlank())
        {
            throw new IllegalArgumentException("team: A team is required.");
        }

        return WorkSql.rows(ctx, "SELECT id, name FROM people WHERE department = $1 ORDER BY name, id", Tuple.of(team))
                .compose(people ->
                {
                    List<Future<Object>> rows = new ArrayList<>();

                    for (Object p : people)
                    {
                        rows.add(ctx.get(EFFORT, new JsonObject().put("person", ((JsonObject) p).getString("id"))
                                .put("period", params.getString("period"))));
                    }

                    return Future.all(rows).map(all ->
                    {
                        JsonArray out = new JsonArray();

                        List<JsonObject> items = new ArrayList<>();

                        double logged = 0;

                        double unplanned = 0;

                        for (int i = 0; i < rows.size(); i++)
                        {
                            JsonObject effort = (JsonObject) rows.get(i).result();

                            JsonObject who = people.getJsonObject(i);

                            logged += effort.getDouble("logged");

                            unplanned += effort.getDouble("unplanned") + effort.getDouble("untracked");

                            effort.getJsonArray("items").forEach(it -> items.add((JsonObject) it));

                            out.add(new JsonObject()
                                    .put("person", who.getString("id"))
                                    .put("name", who.getString("name"))
                                    .put("logged", effort.getValue("logged"))
                                    .put("daysWorked", effort.getValue("daysWorked"))
                                    .put("active", effort.getValue("active"))
                                    .put("work", effort.getValue("work"))
                                    .put("codeOnly", effort.getValue("codeOnly"))
                                    .put("share", effort.getValue("share"))
                                    .put("items", effort.getJsonArray("items").size())
                                    .put("ptsConf", effort.getValue("ptsConf"))
                                    .put("ptsPend", effort.getValue("ptsPend")));
                        }

                        return (Object) new JsonObject()
                                .put("people", out)
                                .put("logged", logged)
                                .put("personDays", logged / ctx.rules().getDouble("dayHours", 8.0))
                                .put("share", logged == 0 ? 0 : (int) Math.round(unplanned / logged * 100))
                                .put("items", items.size())
                                .put("done", (int) items.stream().filter(it -> it.getBoolean("isDone", false)).count())
                                .put("pending", (int) items.stream().filter(it -> "pending".equals(it.getString("state"))).count())
                                .put("requesters", Effort.requesters(items));
                    });
                });
    }

    /**
     * {@code work.extraMileItems {person, period}}: the input port of {@code core.culture}: every item of
     * the quarter as {@code {state, points, values, capped}}, confirmed points already within the monthly
     * caps ({@link Effort#scored}); the bonus per point and its 0.3 cap per value are core's.
     */
    private static Future<Object> cultureItems(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        return period(ctx, params).compose(period ->
        {
            Future<JsonArray> items = WorkSql.rows(ctx, ITEM_ROW + " WHERE x.person_id = $1 AND x.occurred_on BETWEEN $2 AND $3 "
                    + "ORDER BY x.occurred_on, x.id", Tuple.of(person, from(period), to(period)));

            Future<Object> visits = ctx.optional("people.visitPointsByRef", new JsonObject().put("person", person.toString()));

            return Future.all(items, visits).map(all ->
            {
                JsonObject visitPoints = visits.result() instanceof JsonObject v ? v : null;

                return (Object) Effort.scored(list(items.result()), ctx.rules(), visitPoints);
            });
        });
    }

    /**
     * {@code work.extraDetected {person, period}}: unplanned and extra work found in the period's data
     * that is not an extra-mile item yet, each with its {@code detectionKey}, points and evidence.
     * Visits are Track 3's and are not detected here.
     */
    private static Future<Object> detected(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        return period(ctx, params).compose(period ->
        {
            LocalDate from = from(period);

            LocalDate to = to(period);

            Tuple range = Tuple.of(person, from, to);

            Future<JsonObject> who = WorkSql.row(ctx, """
                    SELECT p.name,
                           (SELECT u.name FROM reporting_lines l JOIN users u ON u.id = l.manager_id
                            WHERE l.user_id = p.user_id AND l.valid_to IS NULL) AS manager
                    FROM people p WHERE p.id = $1""", Tuple.of(person));

            Future<JsonArray> midSprint = WorkSql.rows(ctx, """
                    SELECT i.key, i.summary, i.status, s.number, s.starts_on AS "sprintStart", e.occurred_at AS "at",
                           e.actor_name AS "actorName",
                           coalesce((SELECT sum(w.hours) FROM worklogs w WHERE w.issue_id = i.id AND w.person_id = $1), 0) AS hours
                    FROM issue_events e JOIN issues i ON i.id = e.issue_id JOIN sprints s ON s.key = e.to_value
                    WHERE i.assignee_id = $1 AND e.kind = 'sprint_change' AND NOT e.is_bot
                      AND (e.occurred_at AT TIME ZONE 'Asia/Kolkata')::date > s.starts_on
                      AND (e.occurred_at AT TIME ZONE 'Asia/Kolkata')::date BETWEEN $2 AND $3""", range);

            Future<JsonArray> takeovers = WorkSql.rows(ctx, """
                    SELECT i.key, i.summary, i.status, e.occurred_at AS "at", e.actor_name AS "actorName",
                           coalesce((SELECT name FROM people WHERE id::text = e.from_value), e.from_value) AS "fromName",
                           coalesce((SELECT sum(w.hours) FROM worklogs w WHERE w.issue_id = i.id AND w.person_id = $1), 0) AS hours
                    FROM issue_events e JOIN issues i ON i.id = e.issue_id LEFT JOIN sprints s ON s.id = i.sprint_id
                    WHERE e.kind = 'assignee_change' AND e.to_value = $1::text AND NOT e.is_bot
                      AND (s.starts_on IS NULL OR (e.occurred_at AT TIME ZONE 'Asia/Kolkata')::date > s.starts_on)
                      AND (e.occurred_at AT TIME ZONE 'Asia/Kolkata')::date BETWEEN $2 AND $3""", range);

            Future<JsonArray> help = WorkSql.rows(ctx, """
                    SELECT i.key, i.summary, a.name AS assignee, sum(c.commit_count) AS commits, max(c.commit_date) AS day,
                           coalesce((SELECT sum(w.hours) FROM worklogs w WHERE w.issue_id = i.id AND w.person_id = $1), 0) AS hours
                    FROM commit_days c CROSS JOIN LATERAL unnest(c.issue_keys) AS k(key)
                    JOIN issues i ON i.key = k.key JOIN people a ON a.id = i.assignee_id
                    WHERE c.person_id = $1 AND c.commit_date BETWEEN $2 AND $3 AND i.assignee_id <> $1
                    GROUP BY i.id, i.key, i.summary, a.name""", range);

            Future<JsonArray> untracked = WorkSql.rows(ctx, """
                    SELECT p.number, p.title, p.tool, (p.merged_at AT TIME ZONE 'Asia/Kolkata')::date AS merged
                    FROM pull_requests p
                    WHERE p.author_id = $1 AND p.status = 'Completed' AND coalesce(p.work_item_key, '') = ''
                      AND p.merged_at IS NOT NULL
                      AND (p.merged_at AT TIME ZONE 'Asia/Kolkata')::date BETWEEN $2 AND $3""", range);

            Future<JsonArray> reviews = WorkSql.rows(ctx, """
                    SELECT p.first_review_hours AS hours
                    FROM pr_reviews r JOIN pull_requests p ON p.id = r.pull_request_id
                    WHERE r.reviewer_id = $1 AND p.author_id IS DISTINCT FROM $1
                      AND (p.opened_at AT TIME ZONE 'Asia/Kolkata')::date BETWEEN $2 AND $3""", range);

            Future<JsonArray> support = WorkSql.rows(ctx, """
                    SELECT key, title, customer, how_taken AS "howTaken", hours, status, opened_on AS "openedOn", csat
                    FROM support_tickets_current
                    WHERE person_id = $1 AND status = 'Resolved' AND opened_on BETWEEN $2 AND $3""", range);

            Future<JsonArray> proactive = WorkSql.rows(ctx, """
                    SELECT b.key, b.title, b.status, b.origin_ref AS "originRef", b.reported_on AS "reportedOn",
                           b.resolved_on AS "resolvedOn", o.name AS owner
                    FROM bugs_current b LEFT JOIN people o ON o.id = b.origin_person_id
                    WHERE b.fixed_by_id = $1 AND b.origin_person_id IS DISTINCT FROM $1 AND b.proactive
                      AND b.status = 'Resolved' AND b.resolved_on BETWEEN $2 AND $3""", range);

            Future<JsonArray> incidents = WorkSql.rows(ctx, """
                    SELECT i.key, i.summary, i.status, i.started_on AS "startedOn", i.resolved_on AS "resolvedOn", i.spent_hours AS hours,
                           (SELECT p.number FROM pull_requests p WHERE p.work_item_key = i.key AND p.status = 'Completed'
                            ORDER BY p.merged_at LIMIT 1) AS pr
                    FROM issues i
                    WHERE i.assignee_id = $1 AND i.issue_type = 'Incident'
                      AND coalesce(i.resolved_on, i.started_on) BETWEEN $2 AND $3""", range);

            Future<JsonArray> known = WorkSql.rows(ctx, "SELECT detection_key AS key FROM extra_items WHERE person_id = $1",
                    Tuple.of(person));

            return Future.all(List.of(who, midSprint, takeovers, help, untracked, reviews, support, proactive, incidents, known))
                    .map(all ->
                    {
                        String name = who.result() == null ? "" : who.result().getString("name", "");

                        String manager = who.result() == null ? null : who.result().getString("manager");

                        List<JsonObject> found = new ArrayList<>();

                        for (JsonObject r : list(midSprint.result()))
                        {
                            found.add(Effort.midSprint(r, "Sprint " + r.getInteger("number"), LocalDate.parse(r.getString("sprintStart")),
                                    instant(r.getString("at")), requester(r.getString("actorName"), manager), number(r, "hours")));
                        }

                        for (JsonObject r : list(takeovers.result()))
                        {
                            found.add(Effort.takeover(r, r.getString("fromName"), name, instant(r.getString("at")),
                                    requester(r.getString("actorName"), manager), number(r, "hours")));
                        }

                        for (JsonObject r : list(help.result()))
                        {
                            found.add(Effort.help(r, first(name), first(r.getString("assignee")), (int) number(r, "commits"),
                                    LocalDate.parse(r.getString("day")), number(r, "hours")));
                        }

                        for (JsonObject r : list(untracked.result()))
                        {
                            found.add(Effort.untracked(r, LocalDate.parse(r.getString("merged")), 0));
                        }

                        if (!reviews.result().isEmpty())
                        {
                            List<Double> hours = new ArrayList<>();

                            reviews.result().forEach(h -> hours.add(Workload.orZero(Workload.number((JsonObject) h, "hours"))));

                            LocalDate day = to.isAfter(ctx.today()) ? ctx.today() : to;

                            found.add(Effort.reviews(reviews.result().size(), SprintMetrics.median(hours), day, 0));
                        }

                        for (JsonObject r : list(support.result()))
                        {
                            found.add(Effort.support(r));
                        }

                        for (JsonObject r : list(proactive.result()))
                        {
                            found.add(Effort.proactiveFix(r, r.getString("owner", "someone else"), 0));
                        }

                        for (JsonObject r : list(incidents.result()))
                        {
                            found.add(Effort.incident(r, r.getString("pr"), number(r, "hours")));
                        }

                        Set<String> existing = new HashSet<>();

                        known.result().forEach(k -> existing.add(((JsonObject) k).getString("key")));

                        JsonArray out = new JsonArray();

                        for (JsonObject item : found)
                        {
                            String ref = Effort.REVIEWS.equals(item.getString("kind")) ? period.getString("key") : item.getString("ref");

                            String key = item.getString("kind") + ":" + ref + ":" + person;

                            if (!existing.contains(key))
                            {
                                out.add(item.put("detectionKey", key).put("points", Effort.points(item, ctx.rules(), null)));
                            }
                        }

                        return (Object) out;
                    });
        });
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Who gave the work: the changelog author, marked as the person's manager or a teammate.
     */
    private static String requester(String actor, String manager)
    {
        if (actor == null || actor.isBlank())
        {
            return "Self-initiated";
        }

        return actor + " · " + (actor.equals(manager) ? "manager" : "teammate");
    }

    private static String first(String name)
    {
        if (name == null || name.isBlank())
        {
            return "";
        }

        return name.strip().split("\\s+")[0];
    }

    private static Instant instant(String timestamp)
    {
        return OffsetDateTime.parse(timestamp).toInstant();
    }

    private static double number(JsonObject row, String key)
    {
        return Workload.orZero(Workload.number(row, key));
    }

    private static Future<JsonObject> period(CalcContext ctx, JsonObject params)
    {
        return ctx.calendar().map(calendar -> calendar.period(params.getString("period")));
    }

    private static LocalDate from(JsonObject period)
    {
        return LocalDate.parse(period.getString("from"));
    }

    private static LocalDate to(JsonObject period)
    {
        return LocalDate.parse(period.getString("to"));
    }

    private static Formula.Compute safe(Formula.Compute compute)
    {
        return (ctx, params) ->
        {
            try
            {
                return compute.apply(ctx, params);
            }
            catch (IllegalArgumentException e)
            {
                return Future.failedFuture(e);
            }
        };
    }

    private static List<JsonObject> list(JsonArray rows)
    {
        List<JsonObject> out = new ArrayList<>();

        rows.forEach(row -> out.add((JsonObject) row));

        return out;
    }

    private static UUID uuid(JsonObject params, String name)
    {
        String value = params.getString(name);

        if (value == null)
        {
            throw new IllegalArgumentException(name + ": An id is required.");
        }

        try
        {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException e)
        {
            throw new IllegalArgumentException(name + ": '" + value + "' is not an id.");
        }
    }
}
