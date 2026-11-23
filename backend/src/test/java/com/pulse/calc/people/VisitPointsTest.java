package com.pulse.calc.people;

import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 18.1: visit points, values and stats against spec time/customer-visits "Verification and
 * points" (the 5-point example) and against the oracle {@code visitPts} (app.js ~1288),
 * {@code visitValues} and {@code vStats} (~1306) for every seeded visit and person.
 *
 * <p>Fixture: {@code seed/extracted/visits.json} and {@code holidays.json}, the same rows the visits
 * demo seed loads.
 */
class VisitPointsTest
{
    private static final LocalDate TODAY = LocalDate.parse("2026-09-28");

    private static final String POINTS = "(function(id){var r=visitPts(vById(id));return {parts:r.parts.map(function(p){"
            + "return {label:p[0],points:p[1]}}),total:r.total}})";

    private static final String VALUES = "(function(id){return visitValues(vById(id))})";

    private static final String STATS = "(function(pid){var s=vStats(pid);return {visits:s.all.length,reported:s.done.length,"
            + "planned:s.planned.length,days:s.days,weekendDays:s.off,travelHours:s.travel,onsiteHours:s.onsite,csat:s.csat,"
            + "feedback:s.nfb,askAgain:s.again,points:s.pts,pointsConfirmed:s.ptsConf}})";

    private static Oracle oracle;

    private static JsonObject pts;

    private static Set<LocalDate> holidays;

    /** Prototype id → visit in the visits module's shape. */
    private static final Map<String, JsonObject> VISITS = new LinkedHashMap<>();

    /** Prototype person id → their visits. */
    private static final Map<String, JsonArray> BY_PERSON = new LinkedHashMap<>();

    @BeforeAll
    static void start() throws Exception
    {
        oracle = Oracle.start();

        assertEquals(TODAY.toString(), oracle.eval("TODAY"));

        pts = (JsonObject) oracle.eval("S.rules.pts");

        holidays = new HashSet<>();

        for (Object o : new JsonArray(Files.readString(Path.of("..", "seed", "extracted", "holidays.json"))))
        {
            holidays.add(LocalDate.parse(((JsonObject) o).getString("date")));
        }

        for (Object o : new JsonArray(Files.readString(Path.of("..", "seed", "extracted", "visits.json"))))
        {
            JsonObject v = (JsonObject) o;

            JsonObject visit = moduleShape(v);

            VISITS.put(v.getString("id"), visit);

            BY_PERSON.computeIfAbsent(v.getString("pid"), k -> new JsonArray()).add(visit);
        }
    }

    @AfterAll
    static void stop()
    {
        oracle.close();
    }

    @AfterEach
    void reset()
    {
        oracle.reset();
    }

    /** The prototype visit as the visits table stores it (V320). */
    private static JsonObject moduleShape(JsonObject v)
    {
        JsonObject report = v.getJsonObject("report");

        JsonObject feedback = v.getJsonObject("fb");

        String state = v.getString("seedState") != null ? v.getString("seedState") : report == null ? null : "pending";

        return new JsonObject()
                .put("from", v.getString("from"))
                .put("to", v.getString("to"))
                .put("purpose", v.getString("purpose"))
                .put("reportedOn", report == null ? null : report.getString("at"))
                .put("onsiteHours", report == null ? null : report.getNumber("onsite"))
                .put("travelHours", report == null ? null : report.getNumber("travel"))
                .put("csat", feedback == null ? null : feedback.getNumber("csat"))
                .put("askAgain", feedback == null ? null : feedback.getBoolean("again"))
                .put("verification", state);
    }

    // ------------------------------------------------------------------ spec

    @Test
    void specPointsExampleIsFive()
    {
        // A 3-day escalation, Thursday to Saturday (one weekend day), CSAT 4.8.
        JsonObject visit = new JsonObject().put("from", "2026-09-03").put("to", "2026-09-05").put("purpose", "Escalation")
                .put("reportedOn", "2026-09-07").put("csat", 4.8);

        JsonObject points = VisitPoints.points(visit, Set.of(), pts);

        assertEquals(5.0, points.getDouble("total"));

        assertEquals(5, points.getJsonArray("parts").size());

        assertEquals("Customer rated 4.8 / 5", points.getJsonArray("parts").getJsonObject(4).getString("label"));

        // Weekend days add Resilience; escalation already credits it.
        assertEquals(JsonArray.of("Resilience", "Problem Solving", "Outcome Ownership"), VisitPoints.values(visit, Set.of()));
    }

    @Test
    void noPointsBeforeTheReport()
    {
        JsonObject planned = new JsonObject().put("from", "2026-10-06").put("to", "2026-10-07").put("purpose", "Demo / POC");

        assertEquals(0, VisitPoints.points(planned, Set.of(), pts).getInteger("total"));
    }

    @Test
    void monthlyCapCountsConfirmedFirstAndPendingInTheHeadroom()
    {
        // Four 3-day escalations with CSAT 5 on weekdays: 1.5 + 1.0 + 0.5 + 1 = 4 points each.
        JsonArray visits = new JsonArray();

        String[][] dates = {{"2026-09-01", "2026-09-03"}, {"2026-09-08", "2026-09-10"}, {"2026-09-15", "2026-09-17"},
                {"2026-09-22", "2026-09-24"}};

        String[] states = {"confirmed", "pending", "pending", "rejected"};

        for (int i = 0; i < dates.length; i++)
        {
            visits.add(new JsonObject().put("from", dates[i][0]).put("to", dates[i][1]).put("purpose", "Escalation")
                    .put("reportedOn", dates[i][1]).put("csat", 5).put("verification", states[i]));
        }

        JsonObject month = VisitPoints.month(visits, Set.of(), pts, 8);

        assertEquals(4.0, month.getDouble("confirmed"));

        assertEquals(4.0, month.getDouble("pending"));

        assertTrue(month.getBoolean("capped"));
    }

    // ------------------------------------------------------------------ oracle

    @Test
    void pointsAndValuesMatchTheOracleForEverySeededVisit()
    {
        assertEquals(7, VISITS.size());

        // VIS-103: Sara's escalation Saturday to Monday, CSAT 5: 1.5 + 1.0 (2 extra days) + 2 (Sat, Sun) + 0.5 + 1 = 6.
        assertEquals(6.0, ((Number) oracle.eval("visitPts(vById('VIS-103')).total")).doubleValue());

        assertEquals(6.0, VisitPoints.points(VISITS.get("VIS-103"), holidays, pts).getDouble("total"));

        for (Map.Entry<String, JsonObject> visit : VISITS.entrySet())
        {
            oracle.compare(POINTS, Oracle.args(visit.getKey()), 2, args -> VisitPoints.points(visit.getValue(), holidays, pts));

            oracle.compare(VALUES, Oracle.args(visit.getKey()), 0, args -> VisitPoints.values(visit.getValue(), holidays));
        }
    }

    @Test
    void statsMatchTheOracleForEveryPersonInQ3AndTheYear()
    {
        // Everyone in the prototype, so people without visits are compared too.
        Set<String> people = new LinkedHashSet<>(BY_PERSON.keySet());

        ((JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})")).forEach(id -> people.add((String) id));

        for (String pid : people)
        {
            JsonArray visits = BY_PERSON.getOrDefault(pid, new JsonArray());

            oracle.compare(STATS, Oracle.args(pid), 2, args -> VisitPoints.stats(visits, LocalDate.parse("2026-07-01"),
                    LocalDate.parse("2026-09-30"), TODAY, holidays, pts));
        }

        oracle.eval("S.vPer='ytd'");

        for (String pid : people)
        {
            JsonArray visits = BY_PERSON.getOrDefault(pid, new JsonArray());

            oracle.compare(STATS, Oracle.args(pid), 2, args -> VisitPoints.stats(visits, LocalDate.parse("2026-01-01"),
                    LocalDate.parse("2026-12-31"), TODAY, holidays, pts));
        }
    }
}
