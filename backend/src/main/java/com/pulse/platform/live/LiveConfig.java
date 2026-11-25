package com.pulse.platform.live;

import com.pulse.platform.http.ApiException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Generic check of the values an Admin submits for a live connector (task 24.5, design.md decisions 1
 * and 3), against the connector's own {@code configSchema()} and {@code secretSpec()}. Nothing here
 * knows a tool.
 *
 * <p>A config field is {@code {name, label, type, required, pattern, help, options, dependsOn}}:
 * <ul>
 *   <li>{@code type}: {@code text}, {@code url}, {@code list}, {@code date}, {@code boolean} or
 *       {@code choice}.</li>
 *   <li>{@code url} values follow the shared base URL rules ({@link BaseUrls#validate}). They are
 *       stored normalized, and an {@code http} URL adds a warning.</li>
 *   <li>{@code list} takes an array of strings, or one comma-separated string.</li>
 *   <li>{@code choice} takes one of {@code options}, given as strings or {@code {value, label}}.</li>
 *   <li>{@code pattern} applies to a text value and to each list item.</li>
 *   <li>{@code dependsOn: {field, values: [...]}} (or {@code value}) makes the field count only
 *       while that other field holds one of the values, for example a username needed only for the
 *       {@code basic} auth mode. When the dependency does not hold, the value is dropped.</li>
 * </ul>
 *
 * <p>Errors are 400 {@code validation_failed}, naming {@code config.<name>} or {@code secret}. The
 * secret never appears in a message.
 */
public final class LiveConfig
{
    public static final int MAX_TEXT = 500;

    public static final int MAX_LIST_ITEMS = 50;

    public static final int MAX_SECRET = 8_192;

    private static final Set<String> TYPES = Set.of("text", "url", "list", "date", "boolean", "choice");

    private LiveConfig()
    {
    }

    /**
     * Checks {@code config} and {@code secret} for {@code connector}. Resolves to
     * {@code {config, warnings: [{field, code, message}]}}, where {@code config} holds only the
     * declared fields that count, in schema order, normalized. Throws {@link ApiException} (400).
     */
    public static JsonObject validate(LiveConnector connector, JsonObject config, String secret,
                                      boolean allowLoopback)
    {
        JsonObject submitted = config == null ? new JsonObject() : config;

        JsonArray fields = fields(connector);

        Set<String> declared = new HashSet<>();

        fields.forEach(f -> declared.add(((JsonObject) f).getString("name")));

        for (String name : submitted.fieldNames())
        {
            if (!declared.contains(name))
            {
                throw invalid("config." + name, "Unknown field.");
            }
        }

        JsonObject out = new JsonObject();

        JsonArray warnings = new JsonArray();

        for (Object o : fields)
        {
            JsonObject field = (JsonObject) o;

            String name = field.getString("name");

            if (!applies(field, submitted))
            {
                continue;
            }

            Object value = field(field, submitted.getValue(name), allowLoopback, warnings);

            if (value == null)
            {
                if (field.getBoolean("required", false))
                {
                    throw invalid("config." + name, label(field) + " is required.");
                }

                continue;
            }

            out.put(name, value);
        }

        secret(connector.secretSpec(), secret);

        return new JsonObject().put("config", out).put("warnings", warnings);
    }

    /**
     * An optional expiry date or timestamp: null when blank, else the value as entered when it is an
     * ISO date ({@code 2026-12-31}) or an ISO timestamp with an offset.
     */
    public static String expiresAt(String value)
    {
        if (value == null || value.isBlank())
        {
            return null;
        }

        String v = value.strip();

        try
        {
            LocalDate.parse(v);

            return v;
        }
        catch (DateTimeParseException ignored)
        {
            // not a plain date: try a timestamp
        }

        try
        {
            OffsetDateTime.parse(v);

            return v;
        }
        catch (DateTimeParseException e)
        {
            throw invalid("expiresAt", "Enter the expiry as a date (YYYY-MM-DD).");
        }
    }

    // ------------------------------------------------------------------ fields

    private static JsonArray fields(LiveConnector connector)
    {
        JsonObject schema = connector.configSchema();

        JsonArray fields = schema == null ? null : schema.getJsonArray("fields");

        if (fields == null)
        {
            return new JsonArray();
        }

        for (Object o : fields)
        {
            JsonObject f = (JsonObject) o;

            if (f.getString("name") == null || !TYPES.contains(f.getString("type")))
            {
                throw new IllegalStateException("Live connector '" + connector.tool()
                        + "' declares a config field without a name or with an unknown type: " + f.getString("name"));
            }
        }

        return fields;
    }

    /** False when the field depends on another field that does not hold one of the listed values. */
    private static boolean applies(JsonObject field, JsonObject submitted)
    {
        JsonObject dependsOn = field.getJsonObject("dependsOn");

        if (dependsOn == null)
        {
            return true;
        }

        Object actual = submitted.getValue(dependsOn.getString("field"));

        String current = actual == null ? null : String.valueOf(actual).strip();

        JsonArray values = dependsOn.getJsonArray("values");

        if (values == null)
        {
            values = new JsonArray().add(dependsOn.getValue("value"));
        }

        return values.stream().anyMatch(v -> v != null && String.valueOf(v).equals(current));
    }

    /** The normalized value of one field, or null when it is empty. */
    private static Object field(JsonObject field, Object raw, boolean allowLoopback, JsonArray warnings)
    {
        String name = field.getString("name");

        String key = "config." + name;

        switch (field.getString("type"))
        {
            case "boolean":
            {
                if (raw == null)
                {
                    return null;
                }

                if (raw instanceof Boolean b)
                {
                    return b;
                }

                throw invalid(key, label(field) + " must be true or false.");
            }
            case "list":
            {
                JsonArray items = list(key, field, raw);

                return items.isEmpty() ? null : items;
            }
            default:
                break;
        }

        if (raw != null && !(raw instanceof String))
        {
            throw invalid(key, label(field) + " must be text.");
        }

        String value = raw == null ? "" : ((String) raw).strip();

        if (value.isEmpty())
        {
            return null;
        }

        if (value.length() > MAX_TEXT)
        {
            throw invalid(key, label(field) + " is too long.");
        }

        switch (field.getString("type"))
        {
            case "url":
            {
                JsonObject checked = BaseUrls.validate(value, allowLoopback);

                if (!checked.getBoolean("valid"))
                {
                    throw invalid(key, checked.getString("message"));
                }

                checked.getJsonArray("warnings").forEach(w -> warnings.add(((JsonObject) w).copy().put("field", name)));

                return checked.getString("url");
            }
            case "date":
            {
                try
                {
                    return LocalDate.parse(value).toString();
                }
                catch (DateTimeParseException e)
                {
                    throw invalid(key, label(field) + " must be a date (YYYY-MM-DD).");
                }
            }
            case "choice":
            {
                if (!options(field).contains(value))
                {
                    throw invalid(key, "Choose one of the offered values for " + label(field) + ".");
                }

                return value;
            }
            default:
            {
                matches(key, field, value);

                return value;
            }
        }
    }

    private static JsonArray list(String key, JsonObject field, Object raw)
    {
        JsonArray out = new JsonArray();

        if (raw == null)
        {
            return out;
        }

        List<?> items;

        if (raw instanceof JsonArray a)
        {
            items = a.getList();
        }
        else if (raw instanceof String s)
        {
            items = List.of(s.split(","));
        }
        else
        {
            throw invalid(key, label(field) + " must be a list.");
        }

        for (Object item : items)
        {
            if (!(item instanceof String s))
            {
                throw invalid(key, label(field) + " must be a list of text values.");
            }

            String v = s.strip();

            if (v.isEmpty())
            {
                continue;
            }

            if (v.length() > MAX_TEXT)
            {
                throw invalid(key, label(field) + " has a value that is too long.");
            }

            matches(key, field, v);

            if (!out.contains(v))
            {
                out.add(v);
            }
        }

        if (out.size() > MAX_LIST_ITEMS)
        {
            throw invalid(key, label(field) + " has too many values (at most " + MAX_LIST_ITEMS + ").");
        }

        return out;
    }

    private static Set<String> options(JsonObject field)
    {
        Set<String> out = new HashSet<>();

        JsonArray options = field.getJsonArray("options", new JsonArray());

        for (Object o : options)
        {
            out.add(o instanceof JsonObject j ? j.getString("value") : String.valueOf(o));
        }

        return out;
    }

    private static void matches(String key, JsonObject field, String value)
    {
        String pattern = field.getString("pattern");

        if (pattern == null || pattern.isEmpty())
        {
            return;
        }

        if (!compile(pattern).matcher(value).matches())
        {
            throw invalid(key, label(field) + " has the wrong format.");
        }
    }

    // ------------------------------------------------------------------ secret

    private static void secret(JsonObject spec, String secret)
    {
        JsonObject s = spec == null ? new JsonObject() : spec;

        String label = s.getString("label", "Secret");

        if (secret == null || secret.isBlank())
        {
            throw invalid("secret", "Enter the " + label.toLowerCase(Locale.ROOT) + ".");
        }

        if (secret.length() > MAX_SECRET)
        {
            throw invalid("secret", label + " is too long.");
        }

        String pattern = s.getString("pattern");

        if (pattern != null && !pattern.isEmpty() && !compile(pattern).matcher(secret).matches())
        {
            // never echo the value
            throw invalid("secret", label + " has the wrong format.");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Pattern compile(String pattern)
    {
        try
        {
            return Pattern.compile(pattern);
        }
        catch (PatternSyntaxException e)
        {
            throw new IllegalStateException("A live connector declares an invalid pattern: " + pattern, e);
        }
    }

    private static String label(JsonObject field)
    {
        return field.getString("label", field.getString("name"));
    }

    private static ApiException invalid(String field, String message)
    {
        return ApiException.badRequest("validation_failed", message).field(field);
    }
}
