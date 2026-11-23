package com.pulse.calc.work;

import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.testsupport.Oracle;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.pulse.platform.db.TestDb.await;

/**
 * Test fixture for Track 2 calc tests: copies the prototype's work data (through the oracle) into a
 * migrated test database, so CalcService results can be compared with the prototype's functions on
 * the same data, and stands in for Track 3's optional calendar and visit ports.
 */
final class PrototypeWork
{

    private static final String RANGE = "'2026-03-01','2026-12-31'";

    private final Oracle oracle;

    private final SqlExecutor owner;

    /**
     * Prototype id ("ps") to people.id.
     */
    final Map<String, String> people = new HashMap<>();

    private final Map<String, JsonArray> leaveDays = new HashMap<>();

    private final Map<String, JsonArray> visitDays = new HashMap<>();

    private JsonObject visitPoints = new JsonObject();

    private final Map<String, JsonObject> onboarding = new HashMap<>();

    PrototypeWork(Oracle oracle, SqlExecutor owner) throws Exception
    {
        this.oracle = oracle;

        this.owner = owner;

        for (Object item : await(owner.queryList("SELECT id, external_id FROM people WHERE source = 'seed'")))
        {
            JsonObject p = (JsonObject) item;

            people.put(p.getString("external_id"), p.getString("id"));
        }
    }

    /**
     * The people the prototype has on onboarding ({@code onboarding(pid)}: an active training plan as a new
     * joiner), answered by the {@code people.onboarding} stand-in.
     */
    void loadOnboarding()
    {
        for (Map.Entry<String, String> person : people.entrySet())
        {
            Object plan = oracle.eval("(function(id){if(!onboarding(id))return null;var a=trFor(id).filter(function(x){"
                    + "return x.status==='Active'})[0];return {plan:a.plan.name}})('" + person.getKey() + "')");

            if (plan instanceof JsonObject o)
            {
                onboarding.put(person.getValue(), o);
            }
        }
    }

    /**
     * The prototype's extra-mile items (EXTRA) as extra_items rows, and the visit points Track 3 would give.
     */
    void loadExtraItems() throws Exception
    {
        JsonArray items = new JsonArray();

        for (Object item : (JsonArray) oracle.eval("EXTRA"))
        {
            JsonObject x = (JsonObject) item;

            items.add(new JsonObject()
                    .put("key", "seed:" + x.getString("id")).put("person", people.get(x.getString("pid"))).put("kind", x.getString("kind"))
                    .put("ref", x.getString("ref")).put("title", x.getString("title")).put("by", x.getString("by"))
                    .put("how", x.getString("how")).put("src", x.getString("src")).put("day", x.getString("date"))
                    .put("hours", x.getValue("hours")).put("done", x.getBoolean("done", false))
                    .put("values", x.getJsonArray("values")).put("state", x.getString("state")));
        }

        await(owner.execute("""
                INSERT INTO extra_items (detection_key, person_id, kind, ref, title, requested_by, detection_note, evidence_source,
                                         occurred_on, hours, is_done, values_credited, state, decided_at)
                SELECT x.key, x.person, x.kind, x.ref, x.title, x.by, x.how, x.src, x.day, x.hours, x.done,
                       ARRAY(SELECT json_array_elements_text(x.values)), x.state,
                       CASE WHEN x.state = 'pending' THEN NULL ELSE now() END
                FROM json_to_recordset($1::json) AS x(key text, person uuid, kind text, ref text, title text, by text, how text,
                     src text, day date, hours numeric, done boolean, values json, state text)""", Tuple.of(items)));

        visitPoints = (JsonObject) oracle.eval("(function(){var o={};EXTRA.forEach(function(it){if(it.kind==='visit')o[it.ref]=itemPts(it)});"
                + "return o})()");
    }

    /**
     * The prototype's pull requests with their reviews and comments ({@code PRS}), as source "test" with
     * the prototype PR id as external id.
     */
    void loadPrs() throws Exception
    {
        JsonArray prs = new JsonArray();

        JsonArray reviews = new JsonArray();

        JsonArray comments = new JsonArray();

        for (Object item : (JsonArray) oracle.eval("PRS"))
        {
            JsonObject x = (JsonObject) item;

            JsonObject desc = x.getJsonObject("desc");

            String item2 = x.getString("item");

            prs.add(new JsonObject()
                    .put("number", x.getString("id")).put("tool", x.getString("src")).put("repo", x.getString("repo"))
                    .put("title", x.getString("title")).put("suggested", x.getString("sugg")).put("author", people.get(x.getString("pid")))
                    .put("item", item2 == null || item2.isEmpty() ? null : item2).put("status", x.getString("status"))
                    .put("opened", x.getString("created") + "T10:00:00+05:30")
                    .put("merged", x.getString("merged") == null ? null : x.getString("merged") + "T18:00:00+05:30")
                    .put("lines", x.getInteger("lines")).put("files", x.getInteger("files"))
                    .put("summary", desc.getBoolean("summary")).put("tested", desc.getBoolean("tested")).put("risk", desc.getBoolean("risk"))
                    .put("build", x.getBoolean("build")).put("first_review", x.getValue("firstRev")));

            JsonArray reviewers = x.getJsonArray("reviewers");

            for (int j = 0; j < reviewers.size(); j++)
            {
                JsonObject r = reviewers.getJsonObject(j);

                String review = x.getString("id") + "|" + j;

                reviews.add(new JsonObject().put("pr", x.getString("id")).put("external", review)
                        .put("reviewer", people.get(r.getString("pid"))).put("vote", r.getString("vote"))
                        .put("at", x.getString("created") + "T11:00:00+05:30").put("seq", j));

                JsonArray cs = r.getJsonArray("comments");

                for (int k = 0; k < cs.size(); k++)
                {
                    JsonObject c = cs.getJsonObject(k);

                    comments.add(new JsonObject().put("pr", x.getString("id")).put("review", review)
                            .put("author", people.get(r.getString("pid"))).put("body", c.getString("text"))
                            .put("resolved", c.getBoolean("resolved")).put("at", c.getString("at") + "T12:00:00+05:30")
                            .put("seq", j * 100 + k).put("external", review + "|" + k));
                }
            }
        }

        await(owner.execute("""
                INSERT INTO pull_requests (number, tool, repo, title, suggested_title, author_id, work_item_key, status,
                                           opened_at, merged_at, lines_changed, files_changed, has_summary, has_testing,
                                           has_risk, build_passed, first_review_hours, source, external_id)
                SELECT x.number, x.tool, x.repo, x.title, x.suggested, x.author, x.item, x.status, x.opened, x.merged,
                       x.lines, x.files, x.summary, x.tested, x.risk, x.build, x.first_review, 'test', x.number
                FROM json_to_recordset($1::json) AS x(number text, tool text, repo text, title text, suggested text,
                     author uuid, item text, status text, opened timestamptz, merged timestamptz, lines int, files int,
                     summary boolean, tested boolean, risk boolean, build boolean, first_review numeric)""", Tuple.of(prs)));

        await(owner.execute("""
                INSERT INTO pr_reviews (pull_request_id, reviewer_id, vote, reviewed_at, source, external_id)
                SELECT (SELECT id FROM pull_requests WHERE external_id = x.pr AND source = 'test'), x.reviewer, x.vote,
                       x.at + make_interval(mins => x.seq), 'test', x.external
                FROM json_to_recordset($1::json) AS x(pr text, external text, reviewer uuid, vote text, at timestamptz, seq int)""",
                Tuple.of(reviews)));

        await(owner.execute("""
                INSERT INTO pr_comments (pull_request_id, review_id, author_id, body, is_resolved, commented_at, source, external_id)
                SELECT (SELECT id FROM pull_requests WHERE external_id = x.pr AND source = 'test'),
                       (SELECT id FROM pr_reviews WHERE external_id = x.review AND source = 'test'), x.author, x.body,
                       x.resolved, x.at + make_interval(secs => x.seq), 'test', x.external
                FROM json_to_recordset($1::json) AS x(pr text, review text, author uuid, body text, resolved boolean,
                     at timestamptz, seq int, external text)""", Tuple.of(comments)));
    }

    /**
     * The prototype's live tasks with their last activity, history issues, day-by-day worklogs and
     * commits (visit days excluded: their hours come from the visit port), bugs and support tickets.
     */
    void loadWork() throws Exception
    {
        JsonArray issues = new JsonArray();

        JsonArray events = new JsonArray();

        JsonArray worklogs = new JsonArray();

        JsonArray commits = new JsonArray();

        for (String id : people.keySet())
        {
            String person = people.get(id);

            for (Object item : (JsonArray) oracle.eval("person('" + id + "').tasks"))
            {
                JsonObject t = (JsonObject) item;

                boolean done = "Done".equals(t.getString("status"));

                issues.add(new JsonObject()
                        .put("key", t.getString("key")).put("summary", t.getString("summary")).put("issue_type", t.getString("type"))
                        .put("status", t.getString("status")).put("assignee_id", person).put("sprint", "S43").put("release", "8.3")
                        .put("story_points", t.getValue("sp")).put("estimate_hours", t.getValue("orig"))
                        .put("spent_hours", t.getValue("spent")).put("remaining_hours", t.getValue("rem"))
                        .put("due_on", t.getString("due")).put("started_on", t.getString("started"))
                        .put("resolved_on", done ? t.getString("last").substring(0, 10) : null)
                        .put("carried_over", false).put("was_stale", false).put("reopened", false));

                events.add(new JsonObject().put("key", t.getString("key")).put("actor_id", person)
                        .put("occurred_at", t.getString("last") + ":00+05:30").put("summary", t.getString("lastWhat")));
            }

            for (Object item : (JsonArray) oracle.eval("HIST.filter(function(h){return h.pid==='" + id + "'})"))
            {
                JsonObject h = (JsonObject) item;

                boolean carried = "Carried over".equals(h.getString("status"));

                issues.add(new JsonObject()
                        .put("key", h.getString("key")).put("summary", h.getString("summary")).put("issue_type", h.getString("type"))
                        .put("status", carried ? "In Progress" : "Done").put("assignee_id", person)
                        .put("sprint", h.getString("sprint")).put("release", h.getString("rel"))
                        .put("story_points", h.getValue("sp")).put("estimate_hours", h.getValue("orig"))
                        .put("spent_hours", h.getValue("spent")).put("remaining_hours", 0)
                        .put("started_on", h.getString("started")).put("resolved_on", h.getString("resolved"))
                        .put("carried_over", carried).put("was_stale", h.getBoolean("stale", false))
                        .put("reopened", h.getBoolean("reopened", false)));
            }

            for (Object item : (JsonArray) oracle.eval("(function(p){return days('2026-04-01','2026-10-11')"
                    + ".filter(function(s){return !visitOn(p.id,s)}).map(function(s){var x=dayInfo(p,s);"
                    + "return {day:s,h:x.h,gh:!!x.gh}})})(person('" + id + "'))"))
            {
                JsonObject day = (JsonObject) item;

                if (day.getDouble("h") > 0)
                {
                    worklogs.add(new JsonObject().put("person_id", person).put("work_date", day.getString("day"))
                            .put("hours", day.getValue("h")));
                }

                if (day.getBoolean("gh"))
                {
                    commits.add(new JsonObject().put("person_id", person).put("commit_date", day.getString("day")));
                }
            }

            JsonArray leave = new JsonArray();

            ((JsonArray) oracle.eval("(function(p){return days(" + RANGE + ").filter(function(s){return onLeave(p,s)})})"
                    + "(person('" + id + "'))")).forEach(leave::add);

            leaveDays.put(person, leave);

            visitDays.put(person, (JsonArray) oracle.eval("VISITS.filter(function(v){return v.pid==='" + id + "'}).map(function(v){"
                    + "return {from:v.from,to:v.to,hours:v.report?Math.round(v.report.onsite/vDays(v).length*2)/2:8}})"));
        }

        await(owner.execute("""
                INSERT INTO issues (key, summary, issue_type, status, assignee_id, sprint_id, release_id, story_points,
                                    estimate_hours, spent_hours, remaining_hours, due_on, started_on, resolved_on,
                                    carried_over, was_stale, reopened, source, external_id)
                SELECT x.key, x.summary, x.issue_type, x.status, x.assignee_id,
                       (SELECT id FROM sprints WHERE key = x.sprint), (SELECT id FROM releases WHERE key = x.release),
                       x.story_points, x.estimate_hours, x.spent_hours, x.remaining_hours, x.due_on, x.started_on,
                       x.resolved_on, x.carried_over, x.was_stale, x.reopened, 'test', x.key
                FROM json_to_recordset($1::json) AS x(key text, summary text, issue_type text, status text, assignee_id uuid,
                     sprint text, release text, story_points numeric, estimate_hours numeric, spent_hours numeric,
                     remaining_hours numeric, due_on date, started_on date, resolved_on date, carried_over boolean,
                     was_stale boolean, reopened boolean)""", Tuple.of(issues)));

        await(owner.execute("""
                INSERT INTO issue_events (issue_id, occurred_at, kind, actor_id, actor_name, summary)
                SELECT (SELECT id FROM issues WHERE key = x.key AND source = 'test'), x.occurred_at, 'worklog', x.actor_id,
                       'Assignee', x.summary
                FROM json_to_recordset($1::json) AS x(key text, occurred_at timestamptz, actor_id uuid, summary text)""",
                Tuple.of(events)));

        await(owner.execute("""
                INSERT INTO worklogs (person_id, work_date, hours)
                SELECT x.person_id, x.work_date, x.hours
                FROM json_to_recordset($1::json) AS x(person_id uuid, work_date date, hours numeric)""", Tuple.of(worklogs)));

        await(owner.execute("""
                INSERT INTO commit_days (person_id, commit_date, commit_count)
                SELECT x.person_id, x.commit_date, 1
                FROM json_to_recordset($1::json) AS x(person_id uuid, commit_date date)""", Tuple.of(commits)));

        JsonArray bugs = new JsonArray();

        for (Object item : (JsonArray) oracle.eval("BUGS"))
        {
            JsonObject b = (JsonObject) item;

            bugs.add(new JsonObject().put("key", b.getString("key")).put("title", b.getString("title"))
                    .put("severity", b.getString("sev")).put("status", b.getString("status")).put("environment", b.getString("env"))
                    .put("origin", people.get(b.getString("origin"))).put("assignee", people.get(b.getString("assignee")))
                    .put("fixed_by", people.get(b.getString("fixedBy"))).put("proactive", b.getBoolean("proactive", false))
                    .put("reported_on", b.getString("created")).put("resolved_on", b.getString("resolved")));
        }

        await(owner.execute("""
                INSERT INTO bugs (key, title, severity, status, environment, origin_person_id, assignee_id, fixed_by_id,
                                  proactive, reported_on, resolved_on, source, external_id)
                SELECT x.key, x.title, x.severity, x.status, x.environment, x.origin, x.assignee, x.fixed_by, x.proactive,
                       x.reported_on, x.resolved_on, 'test', x.key
                FROM json_to_recordset($1::json) AS x(key text, title text, severity text, status text, environment text,
                     origin uuid, assignee uuid, fixed_by uuid, proactive boolean, reported_on date, resolved_on date)""",
                Tuple.of(bugs)));

        JsonArray tickets = new JsonArray();

        for (Object item : (JsonArray) oracle.eval("SUPPORT"))
        {
            JsonObject t = (JsonObject) item;

            tickets.add(new JsonObject().put("key", t.getString("key")).put("title", t.getString("title"))
                    .put("priority", t.getString("pri")).put("person_id", people.get(t.getString("pid")))
                    .put("hours", t.getValue("hours")).put("status", t.getString("status"))
                    .put("opened_on", t.getString("date")).put("csat", t.getValue("csat")));
        }

        await(owner.execute("""
                INSERT INTO support_tickets (key, title, priority, person_id, hours, status, opened_on, csat, source, external_id)
                SELECT x.key, x.title, x.priority, x.person_id, x.hours, x.status, x.opened_on, x.csat, 'test', x.key
                FROM json_to_recordset($1::json) AS x(key text, title text, priority text, person_id uuid, hours numeric,
                     status text, opened_on date, csat smallint)""", Tuple.of(tickets)));
    }

    /**
     * A stand-in for Track 3's optional ports, answering from the prototype's leave, visits and visit
     * points.
     */
    FormulaModule ports()
    {
        return new FormulaModule()
        {
            @Override
            public String id()
            {
                return "people";
            }

            @Override
            public List<Formula> formulas()
            {
                return List.of(
                        Formula.of("people.leaveDays", Set.of("person"), Set.of(),
                                (ctx, params) -> Future.succeededFuture(leaveDays.getOrDefault(params.getString("person"), new JsonArray()))),
                        Formula.of("people.visitDays", Set.of("person"), Set.of(),
                                (ctx, params) -> Future.succeededFuture(visitDays.getOrDefault(params.getString("person"), new JsonArray()))),
                        Formula.of("people.visitPointsByRef", Set.of("person"), Set.of(),
                                (ctx, params) -> Future.succeededFuture(visitPoints)),
                        Formula.of("people.onboarding", Set.of("person"), Set.of(),
                                (ctx, params) -> Future.succeededFuture(onboarding.get(params.getString("person")))));
            }
        };
    }
}
