package com.pulse.calc.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

/**
 * The fixed thresholds of the platform and the people modules that are not Admin-editable rules (task
 * 23.6; design.md decision 5: no threshold constant outside {@code calc/}). Modules read them through
 * {@code core.thresholds}; the editable ones stay in the rule set ({@link RuleCatalogue}).
 *
 * <p>A tone band list is {@code [{min, tone}]}, highest {@code min} first; a value takes the tone of the
 * first band whose {@code min} it reaches, and the last band (no {@code min}) catches the rest
 * ({@link #tone}).
 */
public final class CoreThresholds
{

    /** Months after joining during which a person is a new joiner (prototype {@code isNewJoiner}). */
    public static final int NEW_JOINER_MONTHS = 3;

    /** The proposed rating of an index (prototype calibration table): Exceeds from 4.2, Meets from 3.5. */
    public static final double INDEX_EXCEEDS = 4.2;

    public static final double INDEX_MEETS = 3.5;

    public static final String EXCEEDS = "Exceeds";

    public static final String MEETS = "Meets";

    public static final String DEVELOPING = "Developing";

    /** Attendance Action Center flags (prototype {@code attActionItems}): short-notice leave days. */
    public static final double ATTENDANCE_SHORT_NOTICE_DAYS = 2;

    /** Office policy share under which a person is flagged. */
    public static final int ATTENDANCE_OFFICE_PCT = 70;

    /** Late check-ins over the allowance from which a person is flagged. */
    public static final int ATTENDANCE_LATE_EXCESS = 3;

    /** Working days left in the month at which the upload reminder shows. */
    public static final int ATTENDANCE_UPLOAD_DUE_DAYS = 5;

    /** Badge tones of a 1–5 score: ok from 4, brand from 3, else accent. */
    public static final JsonArray SCORE_TONES = bands(4, "ok", 3, "brand", null, "accent");

    /** Tones of an index or pillar score as the proposed ratings: ok from Exceeds, brand from Meets, else accent. */
    public static final JsonArray INDEX_TONES = bands(INDEX_EXCEEDS, "ok", INDEX_MEETS, "brand", null, "accent");

    private CoreThresholds()
    {
    }

    /**
     * The {@code core.thresholds} result.
     *
     * @return {@code {newJoinerMonths, index: {exceeds, meets}, attendance: {shortNoticeDays, officePct,
     *         lateExcess, uploadDueDays}, tones: {score, index}}}
     */
    public static JsonObject json()
    {
        return new JsonObject()
                .put("newJoinerMonths", NEW_JOINER_MONTHS)
                .put("index", new JsonObject().put("exceeds", INDEX_EXCEEDS).put("meets", INDEX_MEETS))
                .put("attendance", new JsonObject()
                        .put("shortNoticeDays", ATTENDANCE_SHORT_NOTICE_DAYS)
                        .put("officePct", ATTENDANCE_OFFICE_PCT)
                        .put("lateExcess", ATTENDANCE_LATE_EXCESS)
                        .put("uploadDueDays", ATTENDANCE_UPLOAD_DUE_DAYS))
                .put("tones", new JsonObject().put("score", SCORE_TONES.copy()).put("index", INDEX_TONES.copy()));
    }

    /**
     * The proposed rating of an index: {@value #EXCEEDS} from {@value #INDEX_EXCEEDS}, {@value #MEETS} from
     * {@value #INDEX_MEETS}, else {@value #DEVELOPING}.
     */
    public static String proposed(double index)
    {
        return index >= INDEX_EXCEEDS ? EXCEEDS : index >= INDEX_MEETS ? MEETS : DEVELOPING;
    }

    /**
     * A tone band list from {@code min, tone} pairs, highest {@code min} first; a null {@code min} is the
     * last band, stored without {@code min}.
     */
    public static JsonArray bands(Object... minTone)
    {
        JsonArray out = new JsonArray();

        for (int i = 0; i + 1 < minTone.length; i += 2)
        {
            JsonObject band = new JsonObject();

            // no "min" on the last band: CalcService guards a null as "not applicable"
            if (minTone[i] != null)
            {
                band.put("min", minTone[i]);
            }

            out.add(band.put("tone", minTone[i + 1]));
        }

        return out;
    }

    /**
     * The tone of {@code value} in a band list: the first band whose {@code min} the value reaches, else
     * the band without a {@code min}; {@code ""} for a null value.
     */
    public static String tone(Number value, JsonArray bands)
    {
        if (value == null)
        {
            return "";
        }

        for (Object o : bands)
        {
            JsonObject band = (JsonObject) o;

            Number min = band.getNumber("min");

            if (min == null || value.doubleValue() >= min.doubleValue())
            {
                return band.getString("tone");
            }
        }

        return "";
    }
}
