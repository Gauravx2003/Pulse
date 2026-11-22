package com.pulse.platform.http;

import io.vertx.core.Handler;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side schema for a JSON request body (task 3.6, spec security-baseline "Input validation"):
 * declared fields only (an unknown field is rejected), types checked, string lengths and list sizes
 * bounded. A violation fails with 400 {@code invalid_field} and names the field in
 * {@code error.field}. Declare the schema once, mount its handler before the route's handler and read
 * the checked body back:
 *
 * <pre>{@code
 * private static final BodySchema CREATE = BodySchema.object()
 *         .string("title", 200).required("title")
 *         .oneOf("status", "Draft", "Active")
 *         .uuid("ownerId")
 *         .stringList("tags", 20, 40);
 *
 * router.post("/okrs").handler(policy.requires("okr.own")).handler(CREATE.handler()).handler(ctx ->
 *         service.create(BodySchema.body(ctx)) ...);
 * }</pre>
 *
 * <p>JSON {@code null} counts as absent. Validation rejects; it never rewrites values.
 */
public final class BodySchema
{
    /** Context key of the validated body (JsonObject). */
    public static final String KEY = "pulse.body";

    @FunctionalInterface
    private interface Rule
    {
        /** Null when {@code value} is valid, else the problem ("must be ..."). */
        String check(String path, Object value);
    }

    private final Map<String, Rule> fields = new LinkedHashMap<>();

    private final Set<String> required = new LinkedHashSet<>();

    private BodySchema()
    {
    }

    public static BodySchema object()
    {
        return new BodySchema();
    }

    // ------------------------------------------------------------------ declarations

    public BodySchema string(String name, int maxLength)
    {
        return rule(name, (path, v) ->
        {
            if (!(v instanceof String s))
            {
                return "must be text";
            }

            return s.length() > maxLength ? "must be at most " + maxLength + " characters" : null;
        });
    }

    public BodySchema integer(String name, long min, long max)
    {
        return rule(name, (path, v) ->
        {
            if (!(v instanceof Integer || v instanceof Long))
            {
                return "must be a whole number";
            }

            long n = ((Number) v).longValue();

            return n < min || n > max ? "must be between " + min + " and " + max : null;
        });
    }

    public BodySchema number(String name, double min, double max)
    {
        return rule(name, (path, v) ->
        {
            if (!(v instanceof Number n) || Double.isNaN(n.doubleValue()) || Double.isInfinite(n.doubleValue()))
            {
                return "must be a number";
            }

            return n.doubleValue() < min || n.doubleValue() > max ? "must be between " + min + " and " + max : null;
        });
    }

    public BodySchema bool(String name)
    {
        return rule(name, (path, v) -> v instanceof Boolean ? null : "must be true or false");
    }

    public BodySchema uuid(String name)
    {
        return rule(name, (path, v) -> isUuid(v) ? null : "must be an id");
    }

    /** An ISO date, {@code 2026-09-28}. */
    public BodySchema date(String name)
    {
        return rule(name, (path, v) ->
        {
            if (!(v instanceof String s) || s.length() != 10)
            {
                return "must be a date (YYYY-MM-DD)";
            }

            try
            {
                LocalDate.parse(s);

                return null;
            }
            catch (DateTimeParseException e)
            {
                return "must be a date (YYYY-MM-DD)";
            }
        });
    }

    public BodySchema oneOf(String name, String... allowed)
    {
        List<String> values = List.of(allowed);

        return rule(name, (path, v) -> v instanceof String s && values.contains(s)
                ? null
                : "must be one of " + String.join(", ", values));
    }

    public BodySchema stringList(String name, int maxItems, int maxLength)
    {
        return list(name, maxItems, (path, v) ->
        {
            if (!(v instanceof String s))
            {
                return "must be text";
            }

            return s.length() > maxLength ? "must be at most " + maxLength + " characters" : null;
        });
    }

    public BodySchema uuidList(String name, int maxItems)
    {
        return list(name, maxItems, (path, v) -> isUuid(v) ? null : "must be an id");
    }

    /** A nested object checked against {@code schema} (unknown nested fields rejected too). */
    public BodySchema object(String name, BodySchema schema)
    {
        return rule(name, (path, v) ->
        {
            if (!(v instanceof JsonObject o))
            {
                return "must be an object";
            }

            schema.check(path + ".", o);

            return null;
        });
    }

    /**
     * An object whose keys are not known up front (for example rule paths, checked by the service):
     * at most {@code maxFields} keys of at most 100 characters each. The values are not checked here.
     */
    public BodySchema anyObject(String name, int maxFields)
    {
        return rule(name, (path, v) ->
        {
            if (!(v instanceof JsonObject o))
            {
                return "must be an object";
            }

            if (o.size() > maxFields)
            {
                return "must have at most " + maxFields + " fields";
            }

            return o.fieldNames().stream().anyMatch(k -> k.length() > 100) ? "has a field name over 100 characters" : null;
        });
    }

    /** A list of objects, each checked against {@code schema}. */
    public BodySchema objectList(String name, int maxItems, BodySchema schema)
    {
        return list(name, maxItems, (path, v) ->
        {
            if (!(v instanceof JsonObject o))
            {
                return "must be an object";
            }

            schema.check(path + ".", o);

            return null;
        });
    }

    /** Fields that must be present and not null. */
    public BodySchema required(String... names)
    {
        for (String name : names)
        {
            if (!fields.containsKey(name))
            {
                throw new IllegalArgumentException("Declare '" + name + "' before marking it required");
            }

            required.add(name);
        }

        return this;
    }

    // ------------------------------------------------------------------ use

    /** Route handler: parses the body as a JSON object, validates it and stores it under {@link #KEY}. */
    public Handler<RoutingContext> handler()
    {
        return ctx ->
        {
            JsonObject body;

            try
            {
                body = ctx.body() == null || ctx.body().length() == 0 ? null : ctx.body().asJsonObject();
            }
            catch (DecodeException | ClassCastException e)
            {
                body = null;
            }

            if (body == null)
            {
                ctx.fail(400, ApiException.badRequest("invalid_body", "The request body must be a JSON object."));

                return;
            }

            try
            {
                ctx.put(KEY, validate(body));
            }
            catch (ApiException e)
            {
                ctx.fail(400, e);

                return;
            }

            ctx.next();
        };
    }

    /** The body checked by {@link #handler()}. */
    public static JsonObject body(RoutingContext ctx)
    {
        return ctx.get(KEY);
    }

    /** Returns {@code body} when valid; throws a 400 {@link ApiException} naming the first bad field. */
    public JsonObject validate(JsonObject body)
    {
        check("", body);

        return body;
    }

    private void check(String prefix, JsonObject body)
    {
        for (String name : body.fieldNames())
        {
            if (!fields.containsKey(name))
            {
                throw invalid(prefix + name, "Unknown field '" + prefix + name + "'.");
            }
        }

        for (Map.Entry<String, Rule> field : fields.entrySet())
        {
            String path = prefix + field.getKey();
            Object value = body.getValue(field.getKey());

            if (value == null)
            {
                if (required.contains(field.getKey()))
                {
                    throw invalid(path, "'" + path + "' is required.");
                }

                continue;
            }

            String problem = field.getValue().check(path, value);

            if (problem != null)
            {
                throw invalid(path, "'" + path + "' " + problem + ".");
            }
        }
    }

    private BodySchema rule(String name, Rule rule)
    {
        if (fields.putIfAbsent(name, rule) != null)
        {
            throw new IllegalArgumentException("Field '" + name + "' declared twice");
        }

        return this;
    }

    private BodySchema list(String name, int maxItems, Rule item)
    {
        return rule(name, (path, v) ->
        {
            if (!(v instanceof JsonArray a))
            {
                return "must be a list";
            }

            if (a.size() > maxItems)
            {
                return "must have at most " + maxItems + " items";
            }

            for (int i = 0; i < a.size(); i++)
            {
                String itemPath = path + "[" + i + "]";
                Object element = a.getValue(i);
                String problem = element == null ? "must not be empty" : item.check(itemPath, element);

                if (problem != null)
                {
                    throw invalid(itemPath, "'" + itemPath + "' " + problem + ".");
                }
            }

            return null;
        });
    }

    private static boolean isUuid(Object v)
    {
        if (!(v instanceof String s) || s.length() != 36)
        {
            return false;
        }

        try
        {
            UUID.fromString(s);

            return true;
        }
        catch (IllegalArgumentException e)
        {
            return false;
        }
    }

    private static ApiException invalid(String field, String message)
    {
        return ApiException.badRequest("invalid_field", message).field(field);
    }
}
