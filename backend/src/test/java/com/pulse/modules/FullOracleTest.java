package com.pulse.modules;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.calc.core.ResultGuard;
import com.pulse.calc.work.WorkloadFormulas;
import com.pulse.platform.module.ModuleRegistry;
import com.pulse.platform.module.PulseModule;
import com.pulse.testsupport.Oracle;
import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 23.2, the integration oracle check: with every product module installed and the whole demo seed
 * loaded, the way the demo runs, {@link CalcService} agrees with the prototype for every seeded person on
 * {@code index}, {@code cult}, {@code valData}, {@code discMetrics} / {@code discScore}, {@code attStats}
 * (every month, sprint and quarter of the seed), {@code trRating} / {@code trPerson} (every seeded
 * assignment and trainee) and {@code aprRecommend}; and utilization, the stale count and Discipline read
 * the same on Overview, Work items, Team utilization and Culture values as {@code CalcService} gives for
 * the same inputs, for the active sprint and a closed one.
 *
 * <p>The per-task tests (4.3–4.8 and the Track 2 and 3 port tests) check each formula with only the
 * modules it needs; this one checks that nothing changes when they are all installed together and that
 * the screens read one number. Numbers are compared to the precision the screen shows.
 */
class FullOracleTest
{
    private static final int PORT = 18_377;

    private static final String TODAY = "2026-09-28";

    private static final String QUARTER = "q:2026-Q3";

    /** As DisciplineTest (task 4.5). */
    private static final String DISC_METRICS = "(function(id){var m=discMetrics(person(id));if(!m)return null;"
            + "return {days:m.days.length,updated:m.upd,compliance:m.comp,stale:m.stale,replan:m.replan,status:m.status,"
            + "block:m.block,process:m.proc,base:m.base,adjustments:m.adj.map(function(a){return {label:a[0],detail:a[1],"
            + "points:a[2]}}),auto:m.auto}})";

    private static final String DISC_SCORE = "(function(id){var s=discScore(person(id));"
            + "return {score:s.score,source:s.src,reason:s.reason===undefined?null:s.reason}})";

    /**
     * As AttendanceTest (task 4.6), with the prototype's statuses as the spec stores them: {@code HD} for its
     * half day {@code H}, and {@code S} for its leave {@code L} of type Sick or Emergency (spec time/attendance;
     * the attendance seed mapper loads them so).
     */
    private static final String ATT_STATS = "(function(id,f,t){var o=attStats(person(id),f,t);return {workingDays:o.wd,records:o.rec,"
            + "office:o.office,wfh:o.wfh,duty:o.duty,leave:o.leave,half:o.half,planned:o.planned,shortNotice:o.unplanned,"
            + "excused:o.excused,absent:o.absent,late:o.late,timed:o.timed,hours:o.hrs,hoursN:o.hrsN,lateDays:o.lateDays,"
            + "leaves:o.leaves.map(function(l){return {date:l.date,status:l.st==='H'?'HD':l.st==='L'&&(l.lt==='Sick'||l.lt==='Emergency')?'S':l.st,leaveType:l.lt,appliedOn:l.ap,"
            + "notice:l.notice,'class':l.cls==='unplanned'?'shortNotice':l.cls,amount:l.amt}}),noRecord:o.noRec,months:o.months,"
            + "uploaded:o.up,missing:o.missing,officeRequired:o.officeReq,officeAttended:o.officeGot,officePct:o.officePct,"
            + "noticePct:o.noticePct,allowance:o.graceN,lateExcess:o.lateExcess,punctualityPct:o.punctPct,avgHours:o.avgHrs,"
            + "score:o.score,band:attTone(o.score)[1]}})";

    /** As TrainingRatingTest (task 4.7). */
    private static final String TR_RATING = "(function(id){var a=TASSIGN.filter(function(x){return x.id===id})[0],r=trRating(a);"
            + "return {parts:r.parts.map(function(p){return {part:p[0],value:p[1],weight:p[2],detail:p[3]}}),score:r.score,"
            + "'final':r['final'],state:r.score===null?'Not rated':r['final']?'Final':'Provisional',"
            + "band:{tone:r.band[0],label:r.band[1]}}})";

    private static final String TR_PERSON = "(function(pid){var t=trPerson(pid);return {score:t.score,'final':t['final'],n:t.n}})";

    /** As AppraisalTest (task 4.8). */
    private static final String APR_RECOMMEND = "(function(id){var r=aprRecommend(person(id)),a=r.a;return {pct:r.pct,"
            + "band:r.band,eligible:r.eligible,months:r.months===undefined?null:r.months,"
            + "prorated:r.prorated===undefined?null:r.prorated,why:r.why,parts:a.parts.map(function(x){return {key:x[0],"
            + "label:x[1],value:x[2],weight:aprRules().w[x[0]]}}),score:a.score,cycle:a.cycle,missing:a.missing}})";

    private static TestApi api;

    private static Oracle oracle;

    private static CalcService calc;

    /** Prototype person id to database person id, for every seeded person. */
    private static final Map<String, String> PEOPLE = new LinkedHashMap<>();

    @BeforeAll
    static void start() throws Exception
    {
        // every product module, the way the demo runs; periods are not frozen at start so the server and
        // this CalcService read the same live values
        JsonObject config = new JsonObject()
                .put("today", TODAY)
                .put("http.userPerMinute", 100_000)
                .put("calc.closePeriods", false);

        api = TestApi.start(PORT, config, productModules());

        calc = new CalcService(api.sql(), PulseClock.of(Clock.systemUTC(), TODAY, false), FormulaRegistry.discover());

        oracle = Oracle.start();

        for (Object o : (JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})"))
        {
            String key = (String) o;

            JsonObject row = api.queryOne("SELECT id::text AS id FROM people WHERE source = 'seed' AND external_id = '" + key + "'");

            assertNotNull(row, "seeded person " + key);

            PEOPLE.put(key, row.getString("id"));
        }

        assertEquals(8, PEOPLE.size());
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (oracle != null)
        {
            oracle.close();
        }

        if (api != null)
        {
            api.close();
        }
    }

    @AfterEach
    void reset()
    {
        oracle.reset();
    }

    // ------------------------------------------------------------------ index, cult, valData

    @Test
    void indexCultureAndValueDataMatchThePrototypeForEveryPerson() throws Exception
    {
        for (Map.Entry<String, String> p : PEOPLE.entrySet())
        {
            String who = p.getKey();

            JsonObject params = quarter(p.getValue());

            Object index = get("core.index", params);

            Object culture = get("core.culture", params);

            oracle.compare("index", Oracle.args(Oracle.js("person('" + who + "')")), 1, in -> index);

            oracle.compare("cult", Oracle.args(Oracle.js("person('" + who + "')")), 1, in -> culture);

            JsonArray data = (JsonArray) get("core.cultureData", params);

            JsonArray values = (JsonArray) oracle.eval("VALUES");

            assertEquals(values.size(), data.size(), who);

            for (int i = 0; i < values.size(); i++)
            {
                Object score = data.getJsonObject(i).getValue("score");

                oracle.compare("(function(id,v){return valData(person(id),v).score})", Oracle.args(who, values.getString(i)), 1,
                        in -> score);
            }
        }
    }

    // ------------------------------------------------------------------ discMetrics, discScore

    @Test
    void disciplineMatchesDiscMetricsAndDiscScoreForEveryPerson() throws Exception
    {
        for (Map.Entry<String, String> p : PEOPLE.entrySet())
        {
            JsonObject discipline = (JsonObject) get("core.discipline", new JsonObject().put("person", p.getValue()));

            JsonObject m = discipline.getJsonObject("metrics");

            oracle.compare(DISC_METRICS, Oracle.args(p.getKey()), 2, in ->
            {
                if (m == null)
                {
                    return null;
                }

                JsonArray adjustments = new JsonArray();

                m.getJsonArray("adjustments").forEach(a -> adjustments.add(new JsonObject()
                        .put("label", ((JsonObject) a).getValue("label"))
                        .put("detail", ((JsonObject) a).getValue("detail"))
                        .put("points", ((JsonObject) a).getValue("points"))));

                return new JsonObject()
                        .put("days", m.getJsonArray("days").size()).put("updated", m.getValue("updated"))
                        .put("compliance", m.getValue("compliance")).put("stale", m.getValue("stale"))
                        .put("replan", m.getValue("replan")).put("status", m.getValue("status"))
                        .put("block", m.getValue("block")).put("process", m.getValue("process"))
                        .put("base", m.getValue("base")).put("adjustments", adjustments).put("auto", m.getValue("auto"));
            });

            oracle.compare(DISC_SCORE, Oracle.args(p.getKey()), 2, in -> new JsonObject()
                    .put("score", discipline.getValue("score"))
                    .put("source", discipline.getValue("source"))
                    .put("reason", discipline.getValue("reason")));
        }
    }

    // ------------------------------------------------------------------ attStats

    @Test
    void attendanceMatchesAttStatsForEveryPersonAndPeriod() throws Exception
    {
        List<String> keys = new ArrayList<>();

        for (String m : new String[] {"2026-04", "2026-05", "2026-06", "2026-07", "2026-08", "2026-09"})
        {
            keys.add("m:" + m);
        }

        ((JsonObject) get("core.periods", new JsonObject())).getJsonArray("sprints")
                .forEach(s -> keys.add(((JsonObject) s).getString("key")));

        keys.add("q:Q2");
        keys.add("q:Q3");

        assertTrue(keys.size() >= 6 + 13 + 2, keys.toString());

        int compared = 0;

        for (String key : keys)
        {
            JsonObject period = (JsonObject) get("core.period", new JsonObject().put("period", key));

            for (Map.Entry<String, String> p : PEOPLE.entrySet())
            {
                Object stats = get("people.attendance", new JsonObject().put("person", p.getValue()).put("period", key));

                oracle.compare(ATT_STATS, Oracle.args(p.getKey(), period.getString("from"), period.getString("to")), 2, in -> stats);

                compared++;
            }
        }

        assertEquals(keys.size() * 8, compared);
    }

    // ------------------------------------------------------------------ trRating, trPerson

    @Test
    void trainingRatingsMatchTrRatingForEverySeededAssignmentAndTrainee() throws Exception
    {
        JsonArray assignments = TestApi.await(api.sql().queryList(
                "SELECT id::text AS id, external_id AS key FROM training_assignments WHERE source = 'seed'"));

        assertEquals(((Number) oracle.eval("TASSIGN.length")).intValue(), assignments.size());

        for (Object o : assignments)
        {
            JsonObject a = (JsonObject) o;

            Object rating = get("people.trainingRating", new JsonObject().put("assignment", a.getString("id")));

            oracle.compare(TR_RATING, Oracle.args(a.getString("key")), 2, in ->
            {
                JsonObject r = (JsonObject) rating;

                return new JsonObject()
                        .put("parts", r.getJsonArray("parts"))
                        .put("score", r.getValue("score"))
                        .put("final", r.getValue("final"))
                        .put("state", r.getValue("state"))
                        .put("band", r.getValue("band"));
            });
        }

        int trainees = 0;

        for (Map.Entry<String, String> p : PEOPLE.entrySet())
        {
            boolean plans = !((JsonArray) oracle.eval("trPerson('" + p.getKey() + "').as")).isEmpty();

            Object training = get("core.training", new JsonObject().put("person", p.getValue()));

            if (!plans)
            {
                // no plan: no training rating, and the Growth pillar falls back as the spec says
                assertEquals(null, training, p.getKey());

                continue;
            }

            trainees++;

            JsonObject t = (JsonObject) training;

            oracle.compare(TR_PERSON, Oracle.args(p.getKey()), 2, in -> new JsonObject()
                    .put("score", t.getValue("score")).put("final", t.getValue("final")).put("n", t.getValue("n")));
        }

        assertTrue(trainees >= 4, "trainees " + trainees);
    }

    // ------------------------------------------------------------------ aprRecommend

    @Test
    void appraisalMatchesAprRecommendForEveryPerson() throws Exception
    {
        // The prototype takes the last cycle of CYCLES as the latest, and lists its training checkpoint cycles after
        // the 360° cycles whatever their dates; Pulse orders cycles by due date (as ReviewCyclesPortTest records).
        // Ordering the prototype's cycles by due date first gives the spec's latest manager review (kr: 3.8, not 3.9).
        oracle.eval("CYCLES.sort(function(a,b){return a.due<b.due?-1:a.due>b.due?1:0}).length");

        for (Map.Entry<String, String> p : PEOPLE.entrySet())
        {
            JsonObject appraisal = (JsonObject) get("core.appraisal", new JsonObject().put("person", p.getValue()));

            oracle.compare(APR_RECOMMEND, Oracle.args(p.getKey()), 2, in ->
            {
                JsonObject r = appraisal.getJsonObject("recommendation").copy();

                r.remove("improvementPlan");

                return r.put("months", r.getValue("months")).put("prorated", r.getValue("prorated"))
                        .put("parts", appraisal.getJsonArray("parts")).put("score", appraisal.getValue("score"))
                        .put("cycle", appraisal.getValue("cycle")).put("missing", appraisal.getJsonArray("missing"));
            });
        }
    }

    // ------------------------------------------------------------------ cross-screen

    @Test
    void liveSprintUtilizationStaleAndDisciplineAreOneNumberOnEveryScreen() throws Exception
    {
        TestApi.Session manager = RoleMatrix.session(api, RoleMatrix.MANAGER);

        JsonObject team = ok(api.get("/api/v1/utilization/team", manager));

        assertTrue(team.getBoolean("live"), "the default period is the active sprint");

        JsonArray rows = team.getJsonArray("people");

        assertTrue(rows.size() >= 4, "team rows " + rows.size());

        for (Object o : rows)
        {
            JsonObject row = (JsonObject) o;

            String id = row.getString("id");

            String who = row.getString("name");

            JsonObject live = (JsonObject) get(WorkloadFormulas.LIVE_SPRINT, new JsonObject().put("person", id));

            JsonObject discipline = (JsonObject) get("core.discipline", new JsonObject().put("person", id));

            Number utilization = (Number) live.getValue("util");

            int stale = live.getJsonArray("stale").size();

            double score = discipline.getDouble("score");

            JsonObject overview = ok(api.get("/api/v1/work/people/" + id + "/overview", manager)).getJsonObject("kpis");

            JsonObject items = ok(api.get("/api/v1/work/people/" + id + "/work-items", manager));

            JsonObject culture = ok(api.get("/api/v1/culture/people/" + id, manager)).getJsonObject("discipline");

            JsonObject disciplinePanel = ok(api.get("/api/v1/culture/people/" + id + "/discipline", manager));

            // utilization: Overview, Team utilization and CalcService (live Work items shows no utilization)
            assertShown(utilization, overview.getValue("utilization"), 0, who + " utilization on Overview");
            assertShown(utilization, row.getValue("utilization"), 0, who + " utilization on Team utilization");

            // stale count: Overview, Team utilization and CalcService. The Stale KPI counts issues flagged Stale,
            // Overdue or Not started (app.js ~182); Work items lists those same issues, though its "Stale / not
            // started" chip leaves Overdue to its own chip (app.js ~216, spec work/work-items), so it is checked on
            // the flagged issues of the list.
            assertEquals(stale, overview.getInteger("stale"), who + " stale on Overview");
            assertEquals(stale, row.getInteger("stale"), who + " stale on Team utilization");
            assertEquals(new TreeSet<>(live.getJsonArray("stale").getList()), staleKeys(items.getJsonArray("items")),
                    who + " stale issues on Work items");

            // Discipline: Team utilization, Culture values (card and panel) and CalcService
            assertShown(score, row.getValue("discipline"), 1, who + " Discipline on Team utilization");
            assertShown(score, culture.getValue("score"), 1, who + " Discipline on Culture values");
            assertShown(score, disciplinePanel.getValue("score"), 1, who + " Discipline panel on Culture values");
        }
    }

    @Test
    void closedSprintUtilizationIsOneNumberOnEveryScreen() throws Exception
    {
        JsonObject periods = (JsonObject) get("core.periods", new JsonObject());

        String active = periods.getString("activeSprint");

        String closed = null;

        for (Object s : periods.getJsonArray("sprints"))
        {
            String key = ((JsonObject) s).getString("key");

            if (!key.equals(active))
            {
                closed = key;

                break;
            }
        }

        assertNotNull(closed, "a closed sprint");

        TestApi.Session manager = RoleMatrix.session(api, RoleMatrix.MANAGER);

        JsonObject team = ok(api.get("/api/v1/utilization/team?period=" + closed, manager));

        assertEquals(false, team.getBoolean("live"));

        JsonArray rows = team.getJsonArray("people");

        assertTrue(rows.size() >= 4, "team rows " + rows.size());

        for (Object o : rows)
        {
            JsonObject row = (JsonObject) o;

            String id = row.getString("id");

            String who = row.getString("name") + " " + closed;

            Object utilization = get(WorkloadFormulas.UTILIZATION, new JsonObject().put("person", id).put("period", closed));

            JsonObject overview = ok(api.get("/api/v1/work/people/" + id + "/overview?period=" + closed, manager));

            JsonObject items = ok(api.get("/api/v1/work/people/" + id + "/work-items?period=" + closed, manager));

            // Team utilization shows 0% for a person with no utilization in the period
            Number expected = utilization instanceof Number n ? n : 0;

            assertShown(expected, row.getValue("utilization"), 0, who + " utilization on Team utilization");

            if (utilization instanceof Number n)
            {
                assertShown(n, overview.getValue("utilization"), 0, who + " utilization on Overview");
                assertShown(n, items.getValue("utilization"), 0, who + " utilization on Work items");
            }
            else
            {
                assertTrue(ResultGuard.isNotApplicable(overview.getValue("utilization")), who + " Overview");
                assertTrue(ResultGuard.isNotApplicable(items.getValue("utilization")), who + " Work items");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Object get(String formula, JsonObject params) throws Exception
    {
        return ResultGuard.unguard(TestApi.await(calc.get(formula, params)));
    }

    /** The keys of the listed issues flagged Stale, Overdue or Not started. */
    private static Set<Object> staleKeys(JsonArray items)
    {
        Set<Object> keys = new TreeSet<>();

        for (Object o : items)
        {
            JsonObject issue = (JsonObject) o;

            for (Object f : issue.getJsonArray("flags", new JsonArray()))
            {
                String label = ((JsonArray) f).getString(1);

                if ("Stale".equals(label) || "Overdue".equals(label) || "Not started".equals(label))
                {
                    keys.add(issue.getString("key"));
                }
            }
        }

        return keys;
    }

    private static JsonObject quarter(String person)
    {
        return new JsonObject().put("person", person).put("period", QUARTER);
    }

    private static JsonObject ok(HttpResponse<Buffer> res)
    {
        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject();
    }

    /** {@code actual} is a number and reads as {@code expected} to {@code decimals} places, as the screen shows it. */
    private static void assertShown(Number expected, Object actual, int decimals, String what)
    {
        assertTrue(actual instanceof Number, what + ": " + actual);

        assertEquals(Oracle.display(expected, decimals), Oracle.display((Number) actual, decimals), what);
    }

    private static String[] productModules()
    {
        return ModuleRegistry.discover().modules().stream()
                .filter(m -> Files.isDirectory(Path.of("modules", m.id())))
                .map(PulseModule::id)
                .toArray(String[]::new);
    }
}
