package com.pulse.calc.work;

import com.pulse.calc.core.CoreThresholds;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * Track 2's fixed thresholds and tone bands that are not Admin-editable rules (task 23.6; design.md
 * decision 5: no threshold constant outside {@code calc/}). Modules and screens read them through
 * {@code work.thresholds}; the PR compliance tones follow rule {@code prTarget}, so a rule edit moves
 * them. Tone band lists are those of {@link CoreThresholds#tone}.
 */
public final class WorkThresholds
{

    /** Stale issues of one person from which the Action center raises a Medium item. */
    public static final int STALE_ITEMS = 2;

    /** PR compliance under this share of the month is danger; from it up to rule {@code prTarget}, accent. */
    public static final int PR_COMPLIANCE_LOW = 70;

    /** A culture value that moved by more than this between quarters is shown as a gain or a drop. */
    public static final double CULTURE_GAIN = 0.05;

    /** Mood answer badge (1–5): ok from 4, brand from 3, else danger. */
    public static final JsonArray MOOD_TONES = CoreThresholds.bands(4, "ok", 3, "brand", null, "danger");

    /** Workload answer badge (1–5): danger at 5, warn at 4, else neutral. */
    public static final JsonArray WORKLOAD_TONES = CoreThresholds.bands(5, "danger", 4, "warn", null, "");

    /** Team pulse meter, weekly average mood: neutral from 3.5, else accent. */
    public static final JsonArray TEAM_MOOD_TONES = CoreThresholds.bands(3.5, "", null, "accent");

    /** Team pulse meter, weekly average workload: warn from 4, else info. */
    public static final JsonArray TEAM_WORKLOAD_TONES = CoreThresholds.bands(4, "warn", null, "info");

    /** Sprint forecast meter, % of the remaining work that fits: neutral at 100, accent from 80, else danger. */
    public static final JsonArray FORECAST_FIT_TONES = CoreThresholds.bands(100, "", 80, "accent", null, "danger");

    /** Skills coverage heat cell, people per area: ok from 4, info from 2, else none. */
    public static final JsonArray COVERAGE_TONES = CoreThresholds.bands(4, "ok", 2, "info", null, "");

    private WorkThresholds()
    {
    }

    /**
     * The {@code work.thresholds} result under a rule set.
     *
     * @return {@code {staleItems, oneOnOneOverdueDays, pulse: {lowMood, highWorkload}, tones: {mood,
     *         workload, teamMood, teamWorkload, forecastFit, coverage, prCompliance}}}
     */
    public static JsonObject json(JsonObject rules)
    {
        return new JsonObject()
                .put("staleItems", STALE_ITEMS)
                .put("oneOnOneOverdueDays", ActionCenter.ONE_ON_ONE_DAYS)
                .put("pulse", new JsonObject().put("lowMood", ActionCenter.LOW_MOOD).put("highWorkload", ActionCenter.HIGH_WORKLOAD))
                .put("tones", new JsonObject()
                        .put("mood", MOOD_TONES.copy())
                        .put("workload", WORKLOAD_TONES.copy())
                        .put("teamMood", TEAM_MOOD_TONES.copy())
                        .put("teamWorkload", TEAM_WORKLOAD_TONES.copy())
                        .put("forecastFit", FORECAST_FIT_TONES.copy())
                        .put("coverage", COVERAGE_TONES.copy())
                        .put("prCompliance", prComplianceTones(rules)));
    }

    /** PR compliance of a month: ok from rule {@code prTarget} (85), accent from {@value #PR_COMPLIANCE_LOW}, else danger. */
    public static JsonArray prComplianceTones(JsonObject rules)
    {
        return CoreThresholds.bands(rules.getValue("prTarget", 85), "ok", PR_COMPLIANCE_LOW, "accent", null, "danger");
    }

    /** A culture value's change between quarters: ok above +{@value #CULTURE_GAIN}, danger below −{@value #CULTURE_GAIN}. */
    public static String gainTone(Number gain)
    {
        if (gain == null)
        {
            return "";
        }

        return gain.doubleValue() > CULTURE_GAIN ? "ok" : gain.doubleValue() < -CULTURE_GAIN ? "danger" : "";
    }
}
