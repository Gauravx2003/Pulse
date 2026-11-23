package com.pulse.platform.module;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A raw-event table that the retention purge job empties of old rows (task 5.5, spec
 * platform/privacy-retention "Data retention windows": raw tool events are kept for 13 months and
 * purged automatically; the aggregated scores derived from them stay). A module lists its raw-event
 * tables in {@link PulseModule#retention()}; the platform job {@code platform.retention-purge} deletes
 * every row whose {@code column} is older than {@code months} before today. A table that is not
 * installed is skipped.
 *
 * @param table  the table, e.g. {@code "issue_events"}
 * @param column the timestamp or date column that dates the event, e.g. {@code "at"}
 * @param months how long a row is kept
 */
public record RetentionRule(String table, String column, int months)
{
    /** The retention window of raw tool events (spec privacy-retention). */
    public static final int RAW_EVENT_MONTHS = 13;

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    public RetentionRule
    {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(column, "column");

        if (!IDENTIFIER.matcher(table).matches() || !IDENTIFIER.matcher(column).matches())
        {
            throw new IllegalArgumentException("Retention rule needs plain lowercase identifiers: " + table + "." + column);
        }

        if (months < 1)
        {
            throw new IllegalArgumentException("Retention of " + table + " must be at least one month");
        }
    }

    /** A raw tool-event table kept for {@link #RAW_EVENT_MONTHS}. */
    public static RetentionRule rawEvents(String table, String column)
    {
        return new RetentionRule(table, column, RAW_EVENT_MONTHS);
    }
}
