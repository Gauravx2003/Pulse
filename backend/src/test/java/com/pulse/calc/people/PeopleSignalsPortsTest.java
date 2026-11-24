package com.pulse.calc.people;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.calc.core.ResultGuard;
import com.pulse.testsupport.Oracle;
import com.pulse.testsupport.TestApi;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Track 3's ports for Track 2's Action center and Progress ({@link PeopleSignalsFormulas}) against the
 * prototype for every seeded person: the last 1:1 ({@code LAST11}), kudos received, training behind
 * ({@code trOverdue}), training load ({@code capRow}), wins ({@code teamWins}) and the Track 3 parts of
 * {@code achievements} and {@code nextActions}.
 */
class PeopleSignalsPortsTest
{
    private static final int PORT = 18_375;

    private static TestApi api;

    private static Oracle oracle;

    private static CalcService calc;

    private static final List<JsonObject> PEOPLE = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28"), "work", "pull_requests", "bugs", "effort", "progress",
                "visits", "reviews", "training", "attendance", "culture");

        calc = new CalcService(api.sql(), PulseClock.of(Clock.systemUTC(), "2026-09-28", false), FormulaRegistry.discover());

        oracle = Oracle.start();

        for (Object o : TestApi.await(api.sql().queryList(
                "SELECT id::text AS id, external_id AS key, name FROM people WHERE source = 'seed' ORDER BY external_id")))
        {
            PEOPLE.add((JsonObject) o);
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

    private static Object port(String formula, JsonObject params) throws Exception
    {
        return ResultGuard.unguard(TestApi.await(calc.get(formula, params)));
    }

    private static JsonObject of(JsonObject p)
    {
        return new JsonObject().put("person", p.getString("id"));
    }

    @Test
    void theLastOneOnOneAndKudosMatchThePrototype() throws Exception
    {
        for (JsonObject p : PEOPLE)
        {
            assertEquals(oracle.eval("LAST11['" + p.getString("key") + "']||null"), port("people.lastOneOnOne", of(p)), p.getString("name"));

            Object expected = oracle.eval("KUDOS.filter(function(k){return k.to==='" + p.getString("name") + "'}).length");

            assertEquals(((Number) expected).intValue(), port("people.kudosReceived", of(p).put("period", "q:2026-Q3")), p.getString("name"));
        }
    }

    @Test
    void trainingBehindAndLoadMatchThePrototype() throws Exception
    {
        for (JsonObject p : PEOPLE)
        {
            String pid = "'" + p.getString("key") + "'";

            JsonArray expected = (JsonArray) oracle.eval("trFor(" + pid + ").filter(function(a){return ['Completed','Not completed']"
                    + ".indexOf(trStage(a))<0}).map(function(a){return {plan:a.plan.name,overdue:trOverdue(a).length}})");

            assertEquals(expected, port("people.trainingBehind", of(p)), p.getString("name"));

            JsonObject load = (JsonObject) port("people.trainingLoad", of(p));

            JsonObject cap = (JsonObject) oracle.eval("(function(){var r=capRow(person(" + pid + "));return {trh:r.trh,mnh:r.mnh}})()");

            double hours = 0;

            for (Object o : load.getJsonArray("plans"))
            {
                JsonObject plan = (JsonObject) o;

                if (plan.getString("end").compareTo("2026-10-12") >= 0)
                {
                    hours += plan.getDouble("hoursPerWeek") * 2;
                }
            }

            assertEquals(cap.getInteger("trh").intValue(), (int) Math.round(hours), p.getString("name") + " " + load.encode());

            assertEquals(cap.getDouble("mnh"), load.getInteger("mentees") * 1.5 * 2, 1e-9, p.getString("name"));
        }
    }

    @Test
    void winsMatchThePrototypesTeamWins() throws Exception
    {
        int total = 0;

        for (JsonObject p : PEOPLE)
        {
            JsonArray wins = (JsonArray) port("people.wins", of(p));

            JsonArray expected = (JsonArray) oracle.eval("teamWins([person('" + p.getString("key") + "')]).filter(function(w){"
                    + "return w.what.indexOf('Fixed a bug')<0}).map(function(w){return w.what})");

            List<String> mine = new ArrayList<>();

            wins.forEach(w -> mine.add(((JsonObject) w).getString("what")));

            List<String> theirs = new ArrayList<>();

            expected.forEach(w -> theirs.add((String) w));

            mine.sort(String::compareTo);

            theirs.sort(String::compareTo);

            assertEquals(theirs, mine, p.getString("name"));

            total += mine.size();
        }

        assertTrue(total > 0);
    }

    @Test
    void progressHasTheTrack3AchievementsAndNextSteps() throws Exception
    {
        for (JsonObject p : PEOPLE)
        {
            String pid = "'" + p.getString("key") + "'";

            JsonObject progress = (JsonObject) port("people.progress", of(p));

            JsonArray expected = (JsonArray) oracle.eval("achievements(person(" + pid + ")).filter(function(a){"
                    + "return ['Delighted customer','Training completed','Mentor'].indexOf(a[0])>=0}).map(function(a){return a[0]+' | '+a[1]})");

            List<String> mine = new ArrayList<>();

            progress.getJsonArray("achievements").forEach(a -> mine.add(((JsonObject) a).getString("title") + " | " + ((JsonObject) a).getString("detail")));

            assertEquals(expected.getList(), mine, p.getString("name"));

            // Next steps from training and visits as in nextActions (reviews to give depend on who is signed in,
            // so they are left out). Deviation: checkpoint reviews (cp:) are not acknowledged in Pulse's training
            // screens, so they are not counted as feedback to acknowledge.
            JsonArray steps = (JsonArray) oracle.eval("(function(pid){var out=[];trFor(pid).forEach(function(a){"
                    + "var n=trUnacked(a).filter(function(f){return f.key.indexOf('cp:')!==0}).length;"
                    + "if(n)out.push(n+' training feedback item(s) to acknowledge · '+a.plan.name);"
                    + "if(trStage(a)==='Trainee sign-off')out.push('Sign off the final feedback for '+a.plan.name);"
                    + "var c=ciStats(a),cur=c.ps[0];if(cur&&pStat(a,cur)==='Due'&&a.status==='Active')"
                    + "out.push(cadOf(a)+' training check-in due · '+a.plan.name)});"
                    + "VISITS.filter(function(v){return v.pid===pid&&!v.report&&v.to<TODAY}).forEach(function(v){"
                    + "out.push('Visit report due: '+v.customer)});return out})(" + pid + ")");

            List<String> mySteps = new ArrayList<>();

            progress.getJsonArray("nextSteps").forEach(s ->
            {
                JsonObject step = (JsonObject) s;

                if (!step.getBoolean("selfOnly"))
                {
                    mySteps.add(step.getString("text"));
                }
            });

            List<String> expectedSteps = new ArrayList<>();

            steps.forEach(x -> expectedSteps.add((String) x));

            expectedSteps.sort(String::compareTo);

            mySteps.sort(String::compareTo);

            assertEquals(expectedSteps, mySteps, p.getString("name"));

            assertEquals(((Number) oracle.eval("trFor(" + pid + ").length")).intValue(), progress.getJsonArray("training").size(), p.getString("name"));
        }
    }
}
