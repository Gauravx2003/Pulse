package com.pulse.calc.core;

import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;

/**
 * What a {@link Formula} body gets from {@link CalcService} (task 4.10): data access, today, the rules
 * in force and the other formulas. Values passed between formulas are raw: null means "no value"; the
 * guard ({@link ResultGuard}) runs only on what leaves the service.
 */
public interface CalcContext
{
    /** Read access to the database (never write from a formula). */
    SqlExecutor sql();

    /** Today in the company time zone ({@link PulseClock}, pinned by {@code PULSE_TODAY} outside production). */
    LocalDate today();

    /** The rule set in force (a copy), as stored in {@code rule_versions}. */
    JsonObject rules();

    /** The version of {@link #rules()}. */
    int ruleVersion();

    /** The period calendar of today, from the {@code sprints} and {@code releases} tables. */
    Future<PeriodCalendar> calendar();

    /**
     * Another formula's raw value, cached; for a closed period of a periodic formula, the value in its
     * snapshot. Fails with {@link IllegalArgumentException} when no module registers {@code formula}.
     */
    Future<Object> get(String formula, JsonObject params);

    /**
     * An input port: another track's formula that this one can do without. Resolves to null (no data)
     * when no module registers it, so a core formula works before Tracks 2 and 3 land their ports.
     */
    Future<Object> optional(String formula, JsonObject params);
}
