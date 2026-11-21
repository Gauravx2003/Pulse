package com.pulse.testsupport;

import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * The prototype oracle (task 4.2, design.md decision 5; usage in {@code docs/oracle.md}).
 *
 * <p>Runs {@code reference/2-prototype/src/app.js} in a sandboxed Node {@code vm} context
 * ({@code seed/lib/oracle-server.mjs}, which reuses the seed extractor's loader) and lets a test call
 * any of its functions and compare the result with a Java port to the displayed precision:
 *
 * <pre>{@code
 * private static Oracle oracle;
 *
 * @BeforeAll static void start() throws Exception { oracle = Oracle.start(); }
 * @AfterAll  static void stop()                   { oracle.close(); }
 *
 * @Test void indexMatchesThePrototype()
 * {
 *     oracle.compare("index", Oracle.args(Oracle.js("person('ps')")), 1,
 *             in -> Index.of(in.getJsonObject(0), weights));      // in = the arguments as JSON
 * }
 * }</pre>
 *
 * <p>An argument made with {@link #js(String)} is evaluated inside the prototype (it can name
 * {@code PEOPLE}, {@code person('ps')}, {@code S.rules} and every other binding of app.js); any other
 * argument is passed as JSON. The port receives the same arguments, resolved to plain JSON, so both
 * sides see one fixture.
 *
 * <p>The clock is pinned to 28 Sep 2026 15:30 IST and {@code Math.random} is seeded, as in the seed
 * extractor. State changed with {@link #eval(String)} (for example rule weights) stays until
 * {@link #reset()}.
 *
 * <p>Needs Node 20+ on the PATH, or its path in {@code PULSE_NODE}. One process per instance: start
 * one per test class, not per test. Not thread-safe.
 */
public final class Oracle implements AutoCloseable
{
    /** Default displayed precision: one decimal, as scores and the index are shown. */
    public static final int DEFAULT_DECIMALS = 1;

    private final Process process;

    private final BufferedWriter in;

    private final BufferedReader out;

    private long nextId;

    private Oracle(Process process)
    {
        this.process = process;
        this.in = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /** Starts the Node oracle and waits until app.js has loaded. */
    public static Oracle start() throws IOException
    {
        Path server = repoRoot().resolve("seed/lib/oracle-server.mjs");

        String node = System.getenv().getOrDefault("PULSE_NODE", "node");

        ProcessBuilder builder = new ProcessBuilder(node, server.toString())
                .redirectError(ProcessBuilder.Redirect.INHERIT);

        builder.environment().put("TZ", "Asia/Kolkata");

        Process process;

        try
        {
            process = builder.start();
        }
        catch (IOException e)
        {
            throw new IOException("The oracle needs Node 20+ on the PATH (or PULSE_NODE): " + e.getMessage(), e);
        }

        Oracle oracle = new Oracle(process);

        String ready = oracle.out.readLine();

        if (ready == null || !ready.contains("\"ready\":true"))
        {
            oracle.close();

            throw new IOException("The oracle did not start (app.js failed to load?): " + ready);
        }

        return oracle;
    }

    // ------------------------------------------------------------------ arguments

    /** An argument evaluated inside the prototype, e.g. {@code js("person('ps')")}. */
    public static JsonObject js(String expression)
    {
        return new JsonObject().put("$js", expression);
    }

    /** The argument list for {@link #call} and {@link #compare}. */
    public static JsonArray args(Object... args)
    {
        JsonArray out = new JsonArray();

        for (Object a : args)
        {
            out.add(a);
        }

        return out;
    }

    // ------------------------------------------------------------------ calls

    /**
     * Calls prototype function {@code name} with {@code args} and returns its result as JSON values
     * ({@link Number}, {@link String}, {@link Boolean}, {@link JsonObject}, {@link JsonArray} or null).
     * NaN and infinities come back as {@link Double} NaN / infinity; {@code undefined} as
     * {@code {"$undefined": true}}.
     */
    public Object call(String name, JsonArray args)
    {
        return request(new JsonObject().put("op", "call").put("name", name).put("args", args));
    }

    /** {@link #call(String, JsonArray)} with the arguments inline. */
    public Object call(String name, Object... args)
    {
        return call(name, args(args));
    }

    /** Evaluates an expression inside the prototype, e.g. {@code eval("S.rules.weights.okr = 20")}. */
    public Object eval(String expression)
    {
        return request(new JsonObject().put("op", "eval").put("expr", expression));
    }

    /** The arguments as the prototype sees them, as plain JSON: what the port of a comparison receives. */
    public JsonArray resolve(JsonArray args)
    {
        return (JsonArray) request(new JsonObject().put("op", "resolve").put("args", args));
    }

    /** Reloads app.js, undoing every {@link #eval(String)}. */
    public void reset()
    {
        request(new JsonObject().put("op", "reset"));
    }

    // ------------------------------------------------------------------ comparison

    /** {@link #compare(String, JsonArray, int, Function)} at {@link #DEFAULT_DECIMALS}. */
    public void compare(String name, JsonArray args, Function<JsonArray, Object> port)
    {
        compare(name, args, DEFAULT_DECIMALS, port);
    }

    /**
     * Asserts that the port gives what the prototype function {@code name} gives for the same
     * arguments, to {@code decimals} places: every number is rounded as JavaScript's
     * {@code toFixed(decimals)} would show it, strings and booleans must be equal, and objects and
     * arrays are compared member by member (same keys, same length). Fails when either side holds NaN,
     * an infinity or {@code undefined}.
     *
     * @param port gets the resolved arguments (see {@link #resolve}) and returns its result as a
     *             number, string, boolean, {@link JsonObject}, {@link JsonArray}, {@link Map} or {@link List}
     */
    public void compare(String name, JsonArray args, int decimals, Function<JsonArray, Object> port)
    {
        Object expected = call(name, args);

        assertFinite(name + " (oracle)", expected);

        Object actual = normalize(port.apply(resolve(args)));

        assertFinite(name + " (port)", actual);

        List<String> diffs = new ArrayList<>();

        diff("$", expected, actual, decimals, diffs);

        if (!diffs.isEmpty())
        {
            throw new AssertionError(name + Json.encode(args) + " differs from the prototype at " + decimals
                    + " decimal(s):\n  " + String.join("\n  ", diffs.subList(0, Math.min(diffs.size(), 20)))
                    + (diffs.size() > 20 ? "\n  … " + (diffs.size() - 20) + " more" : ""));
        }
    }

    /** Fails when {@code value} is or contains NaN, an infinity or {@code undefined}. */
    public static void assertFinite(String what, Object value)
    {
        List<String> bad = new ArrayList<>();

        nonFinite("$", normalize(value), bad);

        if (!bad.isEmpty())
        {
            throw new AssertionError(what + " is not finite at " + String.join(", ", bad));
        }
    }

    /** {@code value} as JavaScript's {@code value.toFixed(decimals)} shows it. */
    public static String display(Number value, int decimals)
    {
        BigDecimal exact = value instanceof BigDecimal b ? b
                : value instanceof Double || value instanceof Float ? new BigDecimal(value.doubleValue())
                : BigDecimal.valueOf(value.longValue());

        String shown = exact.setScale(decimals, RoundingMode.HALF_UP).toPlainString();

        return shown.matches("-0(\\.0*)?") ? shown.substring(1) : shown;
    }

    @Override
    public void close()
    {
        try
        {
            in.close();
        }
        catch (IOException ignored)
        {
            // the process is going away anyway
        }

        process.destroy();
    }

    // ------------------------------------------------------------------ internals

    private Object request(JsonObject request)
    {
        long id = ++nextId;

        String line;

        try
        {
            in.write(request.put("id", id).encode());
            in.newLine();
            in.flush();

            line = out.readLine();
        }
        catch (IOException e)
        {
            throw new IllegalStateException("The oracle process failed: " + e.getMessage(), e);
        }

        if (line == null)
        {
            throw new IllegalStateException("The oracle process exited (see the error output above)");
        }

        JsonObject response = new JsonObject(line);

        if (response.getLong("id", -1L) != id)
        {
            throw new IllegalStateException("Oracle answered out of order: " + line);
        }

        if (!response.getBoolean("ok", false))
        {
            throw new IllegalArgumentException("Prototype error for " + request.encode() + ": " + response.getString("error"));
        }

        return decode(response.getValue("value"));
    }

    /** Turns the server's {"$nonFinite": "NaN"} markers back into Double values. */
    private static Object decode(Object value)
    {
        if (value instanceof JsonObject o)
        {
            if (o.size() == 1 && o.containsKey("$nonFinite"))
            {
                return switch (o.getString("$nonFinite"))
                {
                    case "Infinity" -> Double.POSITIVE_INFINITY;
                    case "-Infinity" -> Double.NEGATIVE_INFINITY;
                    default -> Double.NaN;
                };
            }

            JsonObject copy = new JsonObject();

            o.forEach(e -> copy.put(e.getKey(), decode(e.getValue())));

            return copy;
        }

        if (value instanceof JsonArray a)
        {
            JsonArray copy = new JsonArray();

            a.forEach(v -> copy.add(decode(v)));

            return copy;
        }

        return value;
    }

    @SuppressWarnings("unchecked")
    private static Object normalize(Object value)
    {
        if (value instanceof Map<?, ?> m && !(value instanceof JsonObject))
        {
            return new JsonObject((Map<String, Object>) m);
        }

        if (value instanceof List<?> l)
        {
            return new JsonArray((List<Object>) l);
        }

        return value;
    }

    private static void nonFinite(String path, Object value, List<String> bad)
    {
        value = normalize(value);

        if (value instanceof Double d && (d.isNaN() || d.isInfinite()) || value instanceof Float f && (f.isNaN() || f.isInfinite()))
        {
            bad.add(path + " = " + value);
        }
        else if (value instanceof JsonObject o)
        {
            if (o.size() == 1 && Boolean.TRUE.equals(o.getValue("$undefined")))
            {
                bad.add(path + " = undefined");

                return;
            }

            o.forEach(e -> nonFinite(path + "." + e.getKey(), e.getValue(), bad));
        }
        else if (value instanceof JsonArray a)
        {
            for (int i = 0; i < a.size(); i++)
            {
                nonFinite(path + "[" + i + "]", a.getValue(i), bad);
            }
        }
    }

    private static void diff(String path, Object expected, Object actual, int decimals, List<String> diffs)
    {
        expected = normalize(expected);
        actual = normalize(actual);

        if (expected instanceof Number e && actual instanceof Number a)
        {
            String shownE = display(e, decimals);
            String shownA = display(a, decimals);

            if (!shownE.equals(shownA))
            {
                diffs.add(path + ": prototype " + shownE + " (" + e + "), port " + shownA + " (" + a + ")");
            }

            return;
        }

        if (expected instanceof JsonObject e && actual instanceof JsonObject a)
        {
            Set<String> keys = new TreeSet<>(e.fieldNames());

            keys.addAll(a.fieldNames());

            for (String k : keys)
            {
                if (!e.containsKey(k) || !a.containsKey(k))
                {
                    diffs.add(path + "." + k + ": only in the " + (e.containsKey(k) ? "prototype" : "port"));
                }
                else
                {
                    diff(path + "." + k, e.getValue(k), a.getValue(k), decimals, diffs);
                }
            }

            return;
        }

        if (expected instanceof JsonArray e && actual instanceof JsonArray a)
        {
            if (e.size() != a.size())
            {
                diffs.add(path + ": prototype has " + e.size() + " item(s), port " + a.size());

                return;
            }

            for (int i = 0; i < e.size(); i++)
            {
                diff(path + "[" + i + "]", e.getValue(i), a.getValue(i), decimals, diffs);
            }

            return;
        }

        boolean same = expected == null ? actual == null : expected.equals(actual);

        if (!same)
        {
            diffs.add(path + ": prototype " + Json.encode(expected) + ", port " + Json.encode(actual));
        }
    }

    private static Path repoRoot()
    {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent())
        {
            if (Files.exists(dir.resolve("seed/lib/oracle-server.mjs")))
            {
                return dir;
            }
        }

        throw new IllegalStateException("Cannot find seed/lib/oracle-server.mjs above " + Path.of("").toAbsolutePath());
    }
}
