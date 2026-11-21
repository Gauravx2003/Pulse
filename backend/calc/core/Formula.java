package com.pulse.calc.core;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One named formula in a {@link FormulaModule} (task 4.10). {@link CalcService} caches its results,
 * drops them when one of its {@link #topics} changes, and, for a {@link #periodKinds periodic}
 * formula, freezes them when a period closes.
 *
 * @param name        unique, prefixed with the module id: {@code "core.index"}, {@code "work.utilization"}
 * @param params      the parameter names it reads, e.g. {@code person}, {@code period}; part of the cache key
 * @param topics      the data it reads ({@link CalcService} topic constants, or a module's own such as
 *                    {@code "work.issues"}); every formula also depends on the rules
 * @param periodKinds the period kinds ({@code sprint}, {@code quarter}) whose closed periods keep this
 *                    formula's value in a snapshot; empty for a formula that is not frozen. A periodic
 *                    formula takes a {@code period} parameter and, per person, a {@code person} parameter.
 * @param compute     the body: loads its inputs through the {@link CalcContext} and calls the pure
 *                    functions; resolves to a number, string, boolean, {@code JsonObject},
 *                    {@code JsonArray} or null (not applicable)
 */
public record Formula(String name, Set<String> params, Set<String> topics, Set<String> periodKinds, Compute compute)
{
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9]*(\\.[a-zA-Z][a-zA-Z0-9]*)+");

    /** The body of a formula. */
    @FunctionalInterface
    public interface Compute
    {
        Future<Object> apply(CalcContext ctx, JsonObject params);
    }

    public Formula
    {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(compute, "compute");

        if (!NAME.matcher(name).matches())
        {
            throw new IllegalArgumentException("Formula name '" + name + "' must look like 'module.name'");
        }

        params = params == null ? Set.of() : Set.copyOf(params);
        topics = topics == null ? Set.of() : Set.copyOf(topics);
        periodKinds = periodKinds == null ? Set.of() : Set.copyOf(periodKinds);

        if (!periodKinds.isEmpty() && !params.contains("period"))
        {
            throw new IllegalArgumentException("Periodic formula '" + name + "' needs a 'period' parameter");
        }
    }

    /** A formula that is not frozen at period close. */
    public static Formula of(String name, Set<String> params, Set<String> topics, Compute compute)
    {
        return new Formula(name, params, topics, Set.of(), compute);
    }

    /** The module id: the part of the name before the first dot. */
    public String module()
    {
        return name.substring(0, name.indexOf('.'));
    }

    /** True for a formula computed per person ({@code person} parameter). */
    public boolean perPerson()
    {
        return params.contains("person");
    }
}
