package com.pulse.calc.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.calc.core.ResultGuard;
import com.pulse.modules.work.WorkModule;
import com.pulse.platform.db.Db;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.TestDb;
import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 11.1: the effort formulas through CalcService on a database holding the prototype's work data
 * and its extra-mile items, compared with the oracle's {@code monthStats} and the team view of
 * {@code pEffortTeam} for every person, sprint and month; and the detection of each kind of extra
 * work from events of the Track 2 tables.
 */
class EffortFormulasTest
{

    private static TestDb db;

    private static SqlExecutor owner;

    private static CalcService calc;

    private static Oracle oracle;

    private static Map<String, String> people;

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        ScriptCatalog catalog = ScriptCatalog.load(EffortFormulasTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule())));

        await(MigrationRunner.migrate(db.ownerPool(), catalog));

        await(MigrationRunner.seed(db.ownerPool(), catalog, true));

        owner = new SqlExecutor(db.ownerPool());

        oracle = Oracle.start();

        PrototypeWork prototype = new PrototypeWork(oracle, owner);

        prototype.loadWork();

        prototype.loadExtraItems();

        people = prototype.people;

        List<FormulaModule> modules = new ArrayList<>(FormulaRegistry.discover().modules());

        prototype.replacePorts(modules);

        calc = new CalcService(new SqlExecutor(db.pool(Db.DEFAULT_APP_ROLE)),
                PulseClock.of(Clock.systemUTC(), "2026-09-28", false), FormulaRegistry.of(modules));
    }

    @AfterAll
    static void tearDown() throws Exception
    {
        if (oracle != null)
        {
            oracle.close();
        }

        if (db != null)
        {
            db.close();
        }
    }

    // ------------------------------------------------------------------ oracle

    @Test
    void effortMatchesMonthStatsForEveryPersonAndPeriod() throws Exception
    {
        int compared = 0;

        for (String id : people.keySet())
        {
            for (String period : periods())
            {
                String stats = "monthStats(person('" + id + "'),perOf('" + period + "'))";

                Object prototype = oracle.eval("(function(r){return {work:r.work,workAll:r.workAll,logged:r.logged,active:r.active,"
                        + "codeOnly:r.codeOnly,leave:r.leave,weekend:r.weekend,daysWorked:r.daysWorked,cover:r.cover,"
                        + "unplanned:r.unplanned,untracked:r.untracked,reviews:r.reviews,visitH:r.visitH,planned:r.planned,"
                        + "ptsConf:r.ptsConf,ptsPend:r.ptsPend,capped:r.capped,visitPts:r.visitPts,share:r.share}})(" + stats + ")");

                JsonObject port = effort(id, period);

                int items = port.getJsonArray("items").size();

                boolean planning = port.getBoolean("planning");

                for (String key : List.of("items", "planning", "requesters", "period"))
                {
                    port.remove(key);
                }

                assertSame(id + " " + period, prototype, port);

                assertEquals(((Number) oracle.eval(stats + ".items.length")).intValue(), items, id + " " + period + " items");

                assertEquals(port.getInteger("share") >= 30, planning, id + " " + period + " planning signal");

                compared++;
            }
        }

        assertTrue(compared > 100, "every person and period: " + compared);
    }

    @Test
    void teamViewMatchesPEffortTeamForEveryPeriod() throws Exception
    {
        String ids = new JsonArray(new ArrayList<>(people.keySet())).encode();

        for (String period : periods())
        {
            // The KPIs and requester ranking of pEffortTeam() (app.js ~493-503) over the whole team.
            Object prototype = oracle.eval("(function(ids,per){var rows=ids.map(function(id){return monthStats(person(id),per)});"
                    + "var all=[];rows.forEach(function(r){all=all.concat(r.items)});"
                    + "var tl=rows.reduce(function(a,r){return a+r.logged},0),tu=rows.reduce(function(a,r){return a+r.unplanned+r.untracked},0);"
                    + "var src={};all.forEach(function(it){if(it.kind==='untracked'||it.kind==='reviews')return;var k=it.by;"
                    + "src[k]=src[k]||{n:0,h:0};src[k].n++;src[k].h+=it.hours});"
                    + "return {logged:tl,personDays:tl/S.rules.dayHours,share:tl?Math.round(tu/tl*100):0,items:all.length,"
                    + "done:all.filter(function(i){return i.done}).length,pending:all.filter(function(i){return i.state==='pending'}).length,"
                    + "requesters:Object.keys(src).sort(function(a,b){return src[b].h-src[a].h}).map(function(k){"
                    + "return {by:k,items:src[k].n,hours:src[k].h}})}})(" + ids + ",perOf('" + period + "'))");

            JsonObject port = (JsonObject) await(calc.get(EffortFormulas.TEAM, new JsonObject().put("team", "Platform").put("period", period)));

            assertEquals(people.size(), port.getJsonArray("people").size());

            port.remove("people");

            assertSame("team " + period, prototype, port);
        }
    }

    @Test
    void cultureItemsCarryEveryItemOfTheQuarterWithItsPoints() throws Exception
    {
        for (String id : people.keySet())
        {
            JsonArray prototype = (JsonArray) oracle.eval("EXTRA.filter(function(it){return it.pid==='" + id + "'&&it.date>='2026-07-01'"
                    + "&&it.date<='2026-09-30'}).map(function(it){return JSON.stringify([it.state,+itemPts(it).toFixed(2),it.values])}).sort()");

            JsonArray port = (JsonArray) await(calc.get(EffortFormulas.CULTURE_ITEMS,
                    new JsonObject().put("person", people.get(id)).put("period", "q:Q3")));

            List<String> ours = new ArrayList<>();

            for (Object item : port)
            {
                JsonObject x = (JsonObject) item;

                ours.add(new JsonArray().add(x.getString("state")).add(Double.parseDouble(Oracle.display(x.getDouble("points"), 2)))
                        .add(x.getJsonArray("values")).encode());
            }

            ours.sort(null);

            List<String> expected = new ArrayList<>();

            prototype.forEach(s -> expected.add(new JsonArray((String) s).encode()));

            assertEquals(expected.size(), ours.size(), id);

            for (int i = 0; i < ours.size(); i++)
            {
                assertSame(id + " item " + i, new JsonArray(expected.get(i)), new JsonArray(ours.get(i)));
            }
        }
    }

    // ------------------------------------------------------------------ detection

    @Test
    void everyKindOfExtraWorkIsDetectedFromEvents() throws Exception
    {
        String dev = people.get("dp");

        await(owner.execute("""
                INSERT INTO sprints (key, number, starts_on, work_ends_on, ends_on, external_id, source)
                VALUES ('S46', 46, '2026-11-02', '2026-11-13', '2026-11-15', 'S46', 'test')""", Tuple.tuple()));

        String midSprint = issue("PLAT-9001", "Rate-limit alerts per tenant", "Story", dev, "Done", null);

        event(midSprint, "sprint_change", null, "S46", "2026-11-04T11:04:00+05:30", "Rahul Nair");

        String takeover = issue("PLAT-9002", "Collector restart storm", "Story", dev, "In Progress", null);

        event(takeover, "assignee_change", people.get("kr"), dev, "2026-11-05T10:00:00+05:30", "Rahul Nair");

        issue("PLAT-9003", "Trap filter wildcard", "Bug", people.get("nj"), "In Progress", null);

        await(owner.execute("INSERT INTO commit_days (person_id, commit_date, commit_count, issue_keys) "
                + "VALUES ($1::uuid, '2026-11-06', 2, '{PLAT-9003}')", Tuple.of(dev)));

        await(owner.execute("""
                INSERT INTO pull_requests (number, tool, repo, title, author_id, status, opened_at, merged_at, lines_changed, source, external_id)
                VALUES ('#9004', 'GitHub', 'pulse-agent', 'CI cache script', $1::uuid, 'Completed', '2026-11-08T10:00:00+05:30',
                        '2026-11-09T15:00:00+05:30', 80, 'test', '#9004')""", Tuple.of(dev)));

        for (int i = 0; i < 6; i++)
        {
            String pr = await(owner.queryOne("""
                    INSERT INTO pull_requests (number, tool, repo, title, author_id, status, opened_at, lines_changed,
                                               first_review_hours, source, external_id)
                    VALUES ($1, 'GitHub', 'nms-core', 'Review me', $2::uuid, 'Completed', '2026-11-03T10:00:00+05:30', 50, 3, 'test', $1)
                    RETURNING id""", Tuple.of("#91" + i, people.get("am")))).getString("id");

            await(owner.execute("INSERT INTO pr_reviews (pull_request_id, reviewer_id, vote) VALUES ($1::uuid, $2::uuid, 'Approved')",
                    Tuple.of(pr, dev)));
        }

        await(owner.execute("""
                INSERT INTO support_tickets (key, title, customer, priority, person_id, how_taken, hours, status, opened_on, csat, source, external_id)
                VALUES ('SUP-9006', 'Collector CPU spikes', 'Acme Retail', 'Medium', $1::uuid, 'Self-picked from queue', 2, 'Resolved',
                        '2026-11-10', 4, 'test', 'SUP-9006')""", Tuple.of(dev)));

        await(owner.execute("""
                INSERT INTO bugs (key, title, severity, status, environment, origin_person_id, origin_ref, fixed_by_id, proactive,
                                  reported_on, resolved_on, source, external_id)
                VALUES ('BUG-9007', 'Quota double count', 'Medium', 'Resolved', 'Staging', $1::uuid, 'PR #776', $2::uuid, true,
                        '2026-11-10', '2026-11-11', 'test', 'BUG-9007')""", Tuple.of(people.get("am"), dev)));

        issue("INC-9008", "Weekend ingestion outage", "Incident", dev, "Done", "2026-11-12");

        await(owner.execute("""
                INSERT INTO pull_requests (number, tool, repo, title, author_id, work_item_key, status, opened_at, merged_at,
                                           lines_changed, source, external_id)
                VALUES ('#9008', 'GitHub', 'nms-core', 'INC-9008 fix(ingestion): drain backlog', $1::uuid, 'INC-9008', 'Completed',
                        '2026-11-12T09:00:00+05:30', '2026-11-12T13:05:00+05:30', 40, 'test', '#9008')""", Tuple.of(dev)));

        JsonObject params = new JsonObject().put("person", dev).put("period", "m:2026-11");

        Map<String, JsonObject> found = byKind((JsonArray) await(calc.get(EffortFormulas.DETECTED, params)));

        assertEquals(Set.of("midsprint", "takeover", "help", "untracked", "reviews", "support", "proactivefix", "incident"),
                found.keySet());

        JsonObject added = found.get("midsprint");

        assertEquals("Jira changelog: Sprint field set to Sprint 46 on 4 Nov 11:04 (sprint started 2 Nov)", added.getString("detectionNote"));
        assertEquals("Rahul Nair · manager", added.getString("requestedBy"));
        assertEquals(List.of("Adaptability", "Agility in Execution"), added.getJsonArray("valuesCredited").getList());
        assertEquals(1.0, added.getDouble("points"), 1e-9);

        assertEquals("Jira changelog: assignee Kiran Rao → Dev Patel on 5 Nov", found.get("takeover").getString("detectionNote"));
        assertEquals("GitHub: 2 commits by Dev on branch PLAT-9003 (assignee Neha)", found.get("help").getString("detectionNote"));
        assertEquals("GitHub PR merged 9 Nov with no Jira key in branch, title or commits", found.get("untracked").getString("detectionNote"));
        assertEquals("GitHub: 6 reviews on PRs authored by others, median turnaround 3 h", found.get("reviews").getString("detectionNote"));
        assertEquals(0.5, found.get("reviews").getDouble("points"), 1e-9, "0.5 per 5 reviews");
        assertEquals(1.5, found.get("proactivefix").getDouble("points"), 1e-9);
        assertEquals(2.0, found.get("incident").getDouble("points"), 1e-9);
        assertTrue(found.get("incident").getString("detectionNote").contains("PR #9008"));
        assertEquals(0.0, found.get("takeover").getDouble("points"), 1e-9, "no points until the work is done");

        // An item already detected is not offered again.
        await(owner.execute("""
                INSERT INTO extra_items (detection_key, person_id, kind, ref, title, requested_by, detection_note, evidence_source, occurred_on)
                VALUES ($1, $2::uuid, 'midsprint', 'PLAT-9001', 'x', 'x', 'x', 'Jira', '2026-11-04')""",
                Tuple.of(added.getString("detectionKey"), dev)));

        calc.invalidate(EffortFormulas.EXTRA_ITEMS);

        assertFalse(byKind((JsonArray) await(calc.get(EffortFormulas.DETECTED, params))).containsKey("midsprint"));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject effort(String id, String period) throws Exception
    {
        return ((JsonObject) await(calc.get(EffortFormulas.EFFORT, new JsonObject().put("person", people.get(id)).put("period", period))))
                .copy();
    }

    private static String issue(String key, String summary, String type, String assignee, String status, String resolved) throws Exception
    {
        return await(owner.queryOne("""
                INSERT INTO issues (key, summary, issue_type, status, assignee_id, sprint_id, story_points, estimate_hours,
                                    spent_hours, remaining_hours, started_on, resolved_on, source, external_id)
                VALUES ($1, $2, $3, $4, $5::uuid, (SELECT id FROM sprints WHERE key = 'S46'), 3, 8, 6, 0, '2026-11-03', $6,
                        'test', $1)
                RETURNING id""", Tuple.of(key, summary, type, status, assignee, resolved == null ? null : LocalDate.parse(resolved)))).getString("id");
    }

    private static void event(String issue, String kind, String from, String to, String at, String actor) throws Exception
    {
        await(owner.execute("""
                INSERT INTO issue_events (issue_id, occurred_at, kind, field, from_value, to_value, actor_name, summary)
                VALUES ($1::uuid, $2, $3, $3, $4, $5, $6, $3)""", Tuple.of(issue, OffsetDateTime.parse(at), kind, from, to, actor)));
    }

    private static Map<String, JsonObject> byKind(JsonArray items)
    {
        Map<String, JsonObject> out = new HashMap<>();

        items.forEach(item -> out.put(((JsonObject) item).getString("kind"), (JsonObject) item));

        return out;
    }

    private static List<String> periods()
    {
        List<String> out = new ArrayList<>();

        for (Object id : (JsonArray) oracle.eval("SPRINTS.map(function(s){return s.id})"))
        {
            out.add("s:" + id);
        }

        for (Object month : (JsonArray) oracle.eval("PER_MONTHS.map(function(m){return m[0]})"))
        {
            out.add("m:" + month);
        }

        return out;
    }

    /**
     * Equal member by member, numbers as shown with 2 decimals; a guarded "not applicable" equals null.
     */
    private static void assertSame(String where, Object prototype, Object port)
    {
        List<String> diffs = new ArrayList<>();

        diff("$", prototype, port, diffs);

        if (!diffs.isEmpty())
        {
            fail(where + ": " + String.join("; ", diffs));
        }
    }

    private static void diff(String path, Object expected, Object actual, List<String> diffs)
    {
        Object a = ResultGuard.isNotApplicable(actual) ? null : actual;

        if (expected instanceof Number e && a instanceof Number n)
        {
            if (!Oracle.display(e, 2).equals(Oracle.display(n, 2)))
            {
                diffs.add(path + ": prototype " + e + ", port " + n);
            }

            return;
        }

        if (expected instanceof JsonObject e && a instanceof JsonObject o)
        {
            if (!e.fieldNames().equals(o.fieldNames()))
            {
                diffs.add(path + ": keys " + e.fieldNames() + " vs " + o.fieldNames());

                return;
            }

            for (String key : e.fieldNames())
            {
                diff(path + "." + key, e.getValue(key), o.getValue(key), diffs);
            }

            return;
        }

        if (expected instanceof JsonArray e && a instanceof JsonArray o)
        {
            if (e.size() != o.size())
            {
                diffs.add(path + ": " + e.encode() + " vs " + o.encode());

                return;
            }

            for (int i = 0; i < e.size(); i++)
            {
                diff(path + "[" + i + "]", e.getValue(i), o.getValue(i), diffs);
            }

            return;
        }

        if (expected == null ? a != null : !expected.equals(a))
        {
            diffs.add(path + ": prototype " + expected + ", port " + a);
        }
    }
}
