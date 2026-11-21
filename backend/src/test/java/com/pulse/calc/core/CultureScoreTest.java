package com.pulse.calc.core;

import com.pulse.testsupport.Oracle;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 4.4: culture value scores against the spec and against the oracle {@code valData},
 * {@code valueBonus} and the later {@code cult} (app.js ~2202, ~445, ~2251), for every seeded person.
 *
 * <p>Fixture: the 12 simulated parameters come from the seeded {@code seed/extracted/culture_signals.json}
 * (what S120 loads into {@code culture_signals}); the 23 measured parameters, the extra-mile points and
 * the Discipline score come from the prototype, until their own ports (Tracks 2 and 3, tasks 4.5–4.7)
 * and CalcService (task 4.10) supply them from the seeded tables.
 */
class CultureScoreTest
{
    /** The prototype's valData in the shape of the port's payload. */
    private static final String VALDATA = "(function(id,v){var r=valData(person(id),v);return {score:r.score,weight:r.w,"
            + "parameters:r.params.map(function(x){return {name:x.name,source:x.src,how:x.how,"
            + "raw:x.raw===undefined?null:x.raw,display:x.disp,bands:x.th,higherIsBetter:x.hib,weight:x.w,unit:x.u,"
            + "score:x.score,notApplicable:!!x.na}})}})";

    private static final Pattern OF = Pattern.compile("(?:% of | of | in )(\\d+)");

    private static final Pattern HIGH = Pattern.compile("\\((\\d+) high\\)");

    private static Oracle oracle;

    private static JsonArray ids;

    private static JsonObject rules;

    private static final Map<String, JsonObject> SIGNALS = new HashMap<>();

    @BeforeAll
    static void start() throws Exception
    {
        oracle = Oracle.start();

        ids = (JsonArray) oracle.eval("PEOPLE.map(function(p){return p.id})");

        JsonObject r = (JsonObject) oracle.eval("S.rules");

        rules = new JsonObject()
                .put("dataShare", r.getValue("dataShare"))
                .put("bonusPerPoint", r.getValue("bonusPerPoint"))
                .put("bonusCap", r.getValue("bonusCap"))
                .put("staleDays", r.getValue("staleDays"))
                .put("officeDays", r.getJsonObject("att").getValue("officeDays"));

        JsonArray rows = new JsonArray(Files.readString(Path.of("..", "seed", "extracted", "culture_signals.json")));

        for (Object o : rows)
        {
            JsonObject row = (JsonObject) o;

            SIGNALS.computeIfAbsent(row.getString("person"), k -> new JsonObject())
                    .put(row.getString("param_key"), new JsonObject()
                            .put("raw", row.getValue("raw_value"))
                            .put("display", row.getString("display_value"))
                            .put("sourceLabel", row.getString("source_label"))
                            .put("isSimulated", row.getBoolean("is_simulated")));
        }
    }

    @AfterAll
    static void stop()
    {
        oracle.close();
    }

    // ------------------------------------------------------------------ oracle

    @Test
    void valDataMatchesTheOracleForEveryPersonAndValue()
    {
        for (Object o : ids)
        {
            String id = (String) o;

            for (String value : CultureParameters.VALUES)
            {
                JsonObject inputs = inputs(id, value);

                oracle.compare(VALDATA, Oracle.args(id, value), 2, in ->
                {
                    JsonObject port = CultureScore.valData(value, inputs, SIGNALS.get(id), rules);

                    JsonArray params = new JsonArray();

                    for (Object p : port.getJsonArray("parameters"))
                    {
                        JsonObject x = (JsonObject) p;

                        params.add(new JsonObject()
                                .put("name", x.getValue("name")).put("source", x.getValue("source"))
                                .put("how", x.getValue("how")).put("raw", x.getValue("raw"))
                                .put("display", x.getValue("display")).put("bands", x.getValue("bands"))
                                .put("higherIsBetter", x.getValue("higherIsBetter")).put("weight", x.getValue("weight"))
                                .put("unit", x.getValue("unit")).put("score", x.getValue("score"))
                                .put("notApplicable", x.getValue("notApplicable")));
                    }

                    return new JsonObject().put("score", port.getValue("score")).put("weight", port.getValue("weight"))
                            .put("parameters", params);
                });
            }
        }
    }

    @Test
    void valueBonusMatchesTheOracleForEveryPerson()
    {
        for (Object o : ids)
        {
            String id = (String) o;

            JsonArray items = extraItems(id);

            oracle.compare("valueBonus", Oracle.args(Oracle.js("person('" + id + "')")), 2,
                    in -> CultureScore.valueBonus(items, rules));
        }
    }

    @Test
    void cultMatchesTheOracleExactlyForEveryPerson()
    {
        for (Object o : ids)
        {
            String id = (String) o;

            JsonArray port = cult(id);

            oracle.compare("cult", Oracle.args(Oracle.js("person('" + id + "')")), 2, in -> port);

            JsonArray expected = (JsonArray) oracle.call("cult", Oracle.js("person('" + id + "')"));

            for (int i = 0; i < 11; i++)
            {
                assertEquals(((Number) expected.getValue(i)).doubleValue(), port.getDouble(i), 0.0,
                        id + " " + CultureParameters.VALUES.get(i));
            }
        }
    }

    // ------------------------------------------------------------------ payload

    @Test
    void detailPayloadReproducesTheScoreFromItsOwnInputs()
    {
        for (Object o : ids)
        {
            String id = (String) o;

            JsonArray scores = cult(id);

            JsonArray ratings = (JsonArray) oracle.eval("person('" + id + "').culture");

            JsonObject raters = (JsonObject) oracle.eval("person('" + id + "').raters || {}");

            JsonArray bonus = CultureScore.valueBonus(extraItems(id), rules);

            double discipline = discipline(id);

            for (int i = 0; i < 11; i++)
            {
                String value = CultureParameters.VALUES.get(i);

                JsonObject groups = new JsonObject();

                int k = i;

                raters.forEach(e ->
                {
                    JsonArray g = (JsonArray) e.getValue();

                    if (k < g.size())
                    {
                        groups.put(e.getKey(), g.getValue(k));
                    }
                });

                JsonArray items = new JsonArray();

                extraItems(id).stream().map(JsonObject.class::cast)
                        .filter(x -> "confirmed".equals(x.getString("state")) && x.getJsonArray("values").contains(value))
                        .forEach(items::add);

                JsonObject detail = CultureScore.detail(value, ratings.getDouble(i), groups,
                        CultureScore.valData(value, inputs(id, value), SIGNALS.get(id), rules),
                        bonus.getDouble(i), discipline, items, rules);

                assertEquals(scores.getDouble(i), detail.getDouble("score"), 0.0, id + " " + value);

                JsonObject parts = detail.getJsonObject("parts");

                double fromParts;

                if (CultureParameters.DISCIPLINE.equals(value))
                {
                    fromParts = parts.getDouble("discipline");
                }
                else
                {
                    double share = parts.getDouble("dataShare") / 100;

                    double data = parts.getValue("dataScore") == null ? 0 : parts.getDouble("dataScore");

                    fromParts = Math.min(5, Num.fixed(parts.getDouble("rating360") * (1 - share) + data * share
                            + parts.getDouble("bonus"), 2));
                }

                assertEquals(detail.getDouble("score"), fromParts, 0.0, id + " " + value + " from its parts");

                // Each parameter's score is its band score, and the data score is their weighted average.
                if (parts.getValue("dataScore") != null)
                {
                    double w = 0;
                    double s = 0;

                    for (Object p : detail.getJsonArray("parameters"))
                    {
                        JsonObject x = (JsonObject) p;

                        if (!x.getBoolean("notApplicable"))
                        {
                            assertEquals(CultureScore.bandScore(x.getDouble("raw"), x.getJsonArray("bands"),
                                    x.getBoolean("higherIsBetter")), x.getInteger("score"));

                            w += x.getInteger("weight");
                            s += x.getInteger("score") * x.getInteger("weight");
                        }
                    }

                    assertEquals(parts.getDouble("dataScore"), Num.round(s / w, 2), 0.0, id + " " + value + " data score");
                }
            }
        }
    }

    @Test
    void theTwelveReportedParametersShowTheirSourceLabelAndAreSimulated()
    {
        assertEquals(12, CultureParameters.signalKeys().size());

        for (Object o : ids)
        {
            String id = (String) o;

            int seen = 0;

            for (String value : CultureParameters.VALUES)
            {
                for (Object p : CultureScore.valData(value, inputs(id, value), SIGNALS.get(id), rules).getJsonArray("parameters"))
                {
                    JsonObject x = (JsonObject) p;

                    if (CultureParameters.signalKeys().contains(x.getString("key")))
                    {
                        seen++;

                        assertTrue(x.getBoolean("isSimulated"), id + " " + x.getString("key"));
                        assertEquals(SIGNALS.get(id).getJsonObject(x.getString("key")).getString("sourceLabel"),
                                x.getString("sourceLabel"));
                    }
                    else
                    {
                        assertFalse(x.getBoolean("isSimulated"), id + " " + x.getString("key"));
                        assertEquals(x.getString("source"), x.getString("sourceLabel"));
                    }
                }
            }

            assertEquals(12, seen, id);
        }
    }

    // ------------------------------------------------------------------ rules

    @Test
    void moralAndEthicsUsesOnlyThe360Rating()
    {
        JsonObject detail = CultureScore.detail(CultureParameters.MORAL_AND_ETHICS, 4.8, new JsonObject(),
                CultureScore.valData("Outcome Ownership", new JsonObject(), new JsonObject(), rules), 0.1, 3,
                new JsonArray(), rules);

        assertEquals(0, detail.getJsonObject("parts").getInteger("dataShare"));
        assertNull(detail.getJsonObject("parts").getValue("dataScore"));
        assertEquals(4.9, detail.getDouble("score"), 0.0);
        assertTrue(CultureScore.valData(CultureParameters.MORAL_AND_ETHICS, null, null, rules).getJsonArray("parameters").isEmpty());
    }

    @Test
    void noOpportunityParametersAreLeftOutAndWeightsRenormalised()
    {
        JsonObject inputs = new JsonObject()
                .put("incidents_handled", new JsonObject().put("raw", 0))                       // 0 = no opportunity
                .put("delivery_overloaded_sprints", new JsonObject().put("raw", 90).put("of", 2)) // 5 × 40
                .put("mood_heavy_weeks", new JsonObject().put("raw", 3.2));                    // 3 × 25

        JsonObject r = CultureScore.valData("Resilience", inputs, null, rules);

        JsonObject incidents = r.getJsonArray("parameters").getJsonObject(0);

        assertTrue(incidents.getBoolean("notApplicable"));
        assertNull(incidents.getValue("score"));
        assertEquals("0 — no opportunity", incidents.getString("display"));
        assertEquals(65, r.getInteger("weight"));
        assertEquals(Num.round((5 * 40 + 3 * 25) / 65.0, 2), r.getDouble("score"), 0.0);
        assertEquals("90% in 2 sprints", r.getJsonArray("parameters").getJsonObject(1).getString("display"));

        JsonObject none = CultureScore.valData("Resilience", new JsonObject(), null, rules);

        assertNull(none.getValue("score"));
        assertEquals("—", none.getJsonArray("parameters").getJsonObject(1).getString("display"));
    }

    @Test
    void bonusIsCappedAtPointThreeAndScoreAtFive()
    {
        JsonArray items = new JsonArray()
                .add(new JsonObject().put("state", "confirmed").put("points", 10).put("values", new JsonArray().add("Resilience")))
                .add(new JsonObject().put("state", "pending").put("points", 10).put("values", new JsonArray().add("Collaboration")))
                .add(new JsonObject().put("state", "confirmed").put("points", 2)
                        .put("values", new JsonArray().add("Collaboration").add("Outcome Ownership")));

        JsonArray bonus = CultureScore.valueBonus(items, rules);

        assertEquals(0.3, bonus.getDouble(CultureParameters.VALUES.indexOf("Resilience")), 0.0);
        assertEquals(0.05, bonus.getDouble(CultureParameters.VALUES.indexOf("Collaboration")), 0.0);
        assertEquals(0.05, bonus.getDouble(CultureParameters.VALUES.indexOf("Outcome Ownership")), 0.0);

        JsonArray ratings = new JsonArray();
        JsonArray data = new JsonArray();

        for (int i = 0; i < 11; i++)
        {
            ratings.add(4.9);
            data.add(5);
        }

        JsonArray scores = CultureScore.cult(ratings, data, bonus, 3.456, rules);

        assertEquals(5.0, scores.getDouble(CultureParameters.VALUES.indexOf("Resilience")), 0.0);
        assertEquals(3.46, scores.getDouble(CultureParameters.VALUES.indexOf("Discipline")), 0.0);
    }

    @Test
    void bandScoresInBothDirections()
    {
        JsonArray higher = new JsonArray().add(95).add(85).add(70).add(55);

        assertEquals(5, CultureScore.bandScore(95, higher, true));
        assertEquals(4, CultureScore.bandScore(94.9, higher, true));
        assertEquals(1, CultureScore.bandScore(54, higher, true));

        JsonArray lower = new JsonArray().add(0).add(1).add(2).add(4);

        assertEquals(5, CultureScore.bandScore(0, lower, false));
        assertEquals(2, CultureScore.bandScore(4, lower, false));
        assertEquals(1, CultureScore.bandScore(5, lower, false));
    }

    // ------------------------------------------------------------------ fixture

    /** The port's culture scores for a person, built from the fixture. */
    private static JsonArray cult(String id)
    {
        JsonArray ratings = (JsonArray) oracle.eval("person('" + id + "').culture");

        JsonArray data = new JsonArray();

        for (String value : CultureParameters.VALUES)
        {
            data.add(CultureScore.valData(value, inputs(id, value), SIGNALS.get(id), rules).getValue("score"));
        }

        return CultureScore.cult(ratings, data, CultureScore.valueBonus(extraItems(id), rules), discipline(id), rules);
    }

    /**
     * The measured inputs of a value, from the prototype's own computation: {@code raw} plus the
     * "of N" and "N high" counts shown in its display text.
     */
    private static JsonObject inputs(String id, String value)
    {
        JsonArray params = (JsonArray) oracle.eval("valData(person('" + id + "'), '" + value.replace("'", "\\'")
                + "').params.map(function(x){return {name: x.name, raw: x.raw === undefined ? null : x.raw, disp: x.disp}})");

        JsonObject out = new JsonObject();

        JsonArray defs = CultureParameters.of(value);

        for (int i = 0; i < params.size(); i++)
        {
            JsonObject p = params.getJsonObject(i);

            JsonObject def = defs.getJsonObject(i);

            assertEquals(def.getString("name"), p.getString("name"), "parameter order of " + value);

            if ("signal".equals(def.getString("format")))
            {
                continue;
            }

            JsonObject in = new JsonObject().put("raw", p.getValue("raw"));

            Matcher of = OF.matcher(p.getString("disp"));

            if (of.find())
            {
                in.put("of", Integer.parseInt(of.group(1)));
            }

            Matcher high = HIGH.matcher(p.getString("disp"));

            if (high.find())
            {
                in.put("high", Integer.parseInt(high.group(1)));
            }

            out.put(def.getString("key"), in);
        }

        return out;
    }

    /** The person's extra-mile items with their points (calc/work's itemPts, from the prototype for now). */
    private static JsonArray extraItems(String id)
    {
        return (JsonArray) oracle.eval("EXTRA.filter(function(x){return x.pid === '" + id + "'}).map(function(x){"
                + "return {state: x.state, points: itemPts(x), values: x.values, ref: x.ref, title: x.title, kind: x.kind}})");
    }

    /** The Discipline score (task 4.5), from the prototype for now. */
    private static double discipline(String id)
    {
        return ((Number) oracle.eval("discScore(person('" + id + "')).score")).doubleValue();
    }
}
