package com.pulse.calc.core;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The result guard (task 4.9, spec platform/calculation-engine "No opportunity, no penalty"): no
 * calculation result leaves the service holding NaN, an infinity, null or negative zero.
 *
 * <p>The ports in {@code calc/core} return null for "no value" (a rating with no part, a missing 360°,
 * a parameter with no opportunity). {@link #guard} replaces every such null, and every NaN or
 * infinity, by the explicit not-applicable state, and every −0 by 0:
 * <pre>{@code
 * {"notApplicable": true}                         // or with a reason:
 * {"notApplicable": true, "reason": "No on-call shifts in the period"}
 * }</pre>
 * Clients test a value with {@code value.notApplicable === true} and never see null. A port that
 * knows why a value is missing returns {@link #notApplicable(String)} itself.
 *
 * <p>NaN and infinities are bugs, not "no data": {@link #violations} lists them (and −0) so the
 * property tests can assert that the raw ports never produce them, and CalcService logs them.
 */
public final class ResultGuard
{
    public static final String NOT_APPLICABLE = "notApplicable";

    private ResultGuard()
    {
    }

    /** The not-applicable state with no reason (a new object). */
    public static JsonObject notApplicable()
    {
        return new JsonObject().put(NOT_APPLICABLE, true);
    }

    /** The not-applicable state with a reason shown to the user. */
    public static JsonObject notApplicable(String reason)
    {
        return notApplicable().put("reason", reason);
    }

    /**
     * True for the not-applicable state: an object holding {@code notApplicable: true} and at most a
     * {@code reason}. A culture parameter's own {@code notApplicable} flag sits among its other fields,
     * so a parameter is never taken for the state.
     */
    public static boolean isNotApplicable(Object value)
    {
        if (!(value instanceof JsonObject o) || !Boolean.TRUE.equals(o.getValue(NOT_APPLICABLE)))
        {
            return false;
        }

        for (String k : o.fieldNames())
        {
            if (!NOT_APPLICABLE.equals(k) && !"reason".equals(k))
            {
                return false;
            }
        }

        return true;
    }

    /**
     * A guarded copy of a result: every null, NaN or infinity becomes {@link #notApplicable()}, every
     * −0 becomes 0, recursively through objects and arrays. The input is not changed.
     */
    public static Object guard(Object result)
    {
        if (result == null)
        {
            return notApplicable();
        }

        if (result instanceof JsonObject o)
        {
            JsonObject out = new JsonObject();

            for (Map.Entry<String, Object> e : o)
            {
                out.put(e.getKey(), guard(e.getValue()));
            }

            return out;
        }

        if (result instanceof JsonArray a)
        {
            JsonArray out = new JsonArray();

            for (Object x : a)
            {
                out.add(guard(x));
            }

            return out;
        }

        if (result instanceof Map<?, ?> m)
        {
            return guard(new JsonObject(castMap(m)));
        }

        if (result instanceof List<?> l)
        {
            return guard(new JsonArray(new ArrayList<>(l)));
        }

        if (result instanceof Double || result instanceof Float)
        {
            double d = ((Number) result).doubleValue();

            if (Double.isNaN(d) || Double.isInfinite(d))
            {
                return notApplicable();
            }

            return d == 0 ? (Object) 0.0 : result;
        }

        return result;
    }

    /**
     * The reverse of {@link #guard} for values passed between formulas: every not-applicable state
     * becomes null again, recursively (used when a formula reads a value back from a snapshot).
     */
    public static Object unguard(Object value)
    {
        if (isNotApplicable(value))
        {
            return null;
        }

        if (value instanceof JsonObject o)
        {
            JsonObject out = new JsonObject();

            for (Map.Entry<String, Object> e : o)
            {
                out.put(e.getKey(), unguard(e.getValue()));
            }

            return out;
        }

        if (value instanceof JsonArray a)
        {
            JsonArray out = new JsonArray();

            for (Object x : a)
            {
                out.add(unguard(x));
            }

            return out;
        }

        return value;
    }

    /** {@link #guard} for a result known to be an object. */
    public static JsonObject guardObject(JsonObject result)
    {
        return (JsonObject) guard(result == null ? new JsonObject() : result);
    }

    /**
     * The paths in a raw result holding NaN, an infinity or −0 ({@code "parts[3].value: NaN"}); empty
     * when there is none. Nulls are not violations: the guard turns them into not-applicable.
     */
    public static List<String> violations(Object result)
    {
        List<String> out = new ArrayList<>();

        walk("$", result, out);

        return out;
    }

    /** True when the value holds no null, NaN, infinity or −0 anywhere (a guarded result). */
    public static boolean isClean(Object result)
    {
        if (result == null)
        {
            return false;
        }

        if (result instanceof JsonObject o)
        {
            for (Map.Entry<String, Object> e : o)
            {
                if (!isClean(e.getValue()))
                {
                    return false;
                }
            }

            return true;
        }

        if (result instanceof JsonArray a)
        {
            for (Object x : a)
            {
                if (!isClean(x))
                {
                    return false;
                }
            }

            return true;
        }

        return violations(result).isEmpty();
    }

    private static void walk(String path, Object value, List<String> out)
    {
        if (value instanceof JsonObject o)
        {
            for (Map.Entry<String, Object> e : o)
            {
                walk(path + "." + e.getKey(), e.getValue(), out);
            }
        }
        else if (value instanceof JsonArray a)
        {
            for (int i = 0; i < a.size(); i++)
            {
                walk(path + "[" + i + "]", a.getValue(i), out);
            }
        }
        else if (value instanceof Map<?, ?> m)
        {
            walk(path, new JsonObject(castMap(m)), out);
        }
        else if (value instanceof List<?> l)
        {
            walk(path, new JsonArray(new ArrayList<>(l)), out);
        }
        else if (value instanceof Double || value instanceof Float)
        {
            double d = ((Number) value).doubleValue();

            if (Double.isNaN(d))
            {
                out.add(path + ": NaN");
            }
            else if (Double.isInfinite(d))
            {
                out.add(path + ": " + (d > 0 ? "Infinity" : "-Infinity"));
            }
            else if (d == 0 && Double.doubleToRawLongBits(d) != 0)
            {
                out.add(path + ": -0");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m)
    {
        return (Map<String, Object>) m;
    }
}
