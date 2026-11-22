package com.pulse.modules.integrations;

import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.ingest.ConnectorRegistry;
import com.pulse.platform.ingest.SeedConnector;
import com.pulse.platform.ingest.SeedFiles;
import com.pulse.platform.ingest.SeedLoader;
import com.pulse.platform.ingest.SyncLogRepository;
import com.pulse.platform.ingest.SyncService;
import com.pulse.platform.module.ModuleContext;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Backend of the Integrations screen (task 5.4, spec admin/integrations-connectors), Phase 1: every tool
 * is seeded. Tiles derive their state from the stored settings and the sync log; credential forms
 * validate their fields and store only a masked placeholder; Sync now runs the tool's connector (the
 * seed connector until group 24 registers live ones) through {@code IngestService}, writes the sync
 * log and invalidates the calculation cache. No outbound request is made.
 */
final class IntegrationsService
{
    static final String JIRA = "jira";

    static final List<String> SYNC_MODES = List.of("webhooks_hourly", "hourly", "nightly");

    /** A token that expires within this many days puts its tile in the "Token expires in N days" state. */
    static final int EXPIRY_WARNING_DAYS = 14;

    private static final int LOG_LIMIT = 50;

    private static final int MAX_FIELD = 500;

    private static final int MAX_SECRET = 8_192;

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private static final Pattern NON_ALNUM = Pattern.compile("[^A-Za-z0-9]");

    private final ModuleContext ctx;

    private final ConnectorRegistry connectors;

    private Future<IntegrationCatalogue> catalogue;

    IntegrationsService(ModuleContext ctx, ConnectorRegistry connectors)
    {
        this.ctx = ctx;
        this.connectors = connectors;
    }

    /** The registry over the platform and module seed mappers; live connectors register on it. */
    static ConnectorRegistry connectors(ModuleContext ctx)
    {
        ClassLoader loader = IntegrationsService.class.getClassLoader();

        return new ConnectorRegistry(new SeedConnector(ctx.vertx(), new SeedFiles(loader),
                SeedLoader.mappers(ctx.registry().modules())));
    }

    private Future<IntegrationCatalogue> catalogue()
    {
        if (catalogue == null)
        {
            ClassLoader loader = getClass().getClassLoader();

            catalogue = ctx.vertx().executeBlocking(() -> IntegrationCatalogue.load(new SeedFiles(loader)), false);
        }

        return catalogue;
    }

    // ------------------------------------------------------------------ reads

    /** {@code {tiles: [...]}} in catalogue order. */
    Future<JsonObject> tiles()
    {
        return state().map(s ->
        {
            JsonArray tiles = new JsonArray();

            s.catalogue().all().forEach(t -> tiles.add(tile(t, s)));

            return new JsonObject().put("tiles", tiles);
        });
    }

    /** One tool: tile, auth methods and form fields, masked stored values, scopes, settings, recent syncs. */
    Future<JsonObject> tool(String id)
    {
        return state().compose(s ->
        {
            JsonObject t = known(s.catalogue(), id);
            JsonObject settings = s.settings().getOrDefault(id, new JsonObject());
            JsonArray scopes = t.getJsonArray("scopes");

            // Phase 1: the connection test is simulated, so the reported scopes are the catalogued ones
            JsonArray broad = new JsonArray(scopes.stream().map(String::valueOf)
                    .filter(sc -> sc.toLowerCase(Locale.ROOT).contains("write")).toList());

            return new SyncLogRepository(ctx.sql()).recent(id, 10).map(syncs -> new JsonObject()
                    .put("tile", tile(t, s))
                    .put("methods", t.getJsonArray("methods"))
                    .put("method", settings.getString("method"))
                    .put("credentials", settings.getJsonObject("credentials", new JsonObject()))
                    .put("savedAt", settings.getString("credentials_saved_at"))
                    .put("savedBy", settings.getString("saved_by_name"))
                    .put("testedAt", settings.getString("tested_at"))
                    .put("testOk", settings.getBoolean("test_ok"))
                    .put("tokenExpiresOn", settings.getString("token_expires_on"))
                    .put("rotateDays", t.getInteger("rotateDays"))
                    .put("scopes", scopes)
                    .put("reportedScopes", scopes)
                    .put("reportedScopesSimulated", true)
                    .put("scopeWarning", broad.isEmpty() ? null
                            : "Write scope is not needed: Pulse only reads (" + String.join(", ",
                            broad.stream().map(String::valueOf).toList()) + ")")
                    .put("syncMode", settings.getString("sync_mode", SYNC_MODES.get(0)))
                    .put("syncModes", new JsonArray(SYNC_MODES))
                    .put("config", JIRA.equals(id) ? jiraConfig(settings) : null)
                    .put("syncLog", syncs));
        });
    }

    Future<JsonObject> syncLog(String tool)
    {
        return new SyncLogRepository(ctx.sql()).recent(tool, LOG_LIMIT).map(rows -> new JsonObject().put("items", rows));
    }

    Future<JsonObject> credentialLog()
    {
        return new IntegrationsRepository(ctx.sql()).credentialLog(LOG_LIMIT).map(rows -> new JsonObject().put("items", rows));
    }

    Future<JsonObject> jiraConfig()
    {
        return new IntegrationsRepository(ctx.sql()).settings(JIRA)
                .map(row -> jiraConfig(row == null ? new JsonObject() : row));
    }

    // ------------------------------------------------------------------ writes

    /**
     * Validates the form of {@code method} and stores the values with every secret replaced by a masked
     * placeholder; logs "Credentials added/changed" and audits it. Never echoes a secret.
     */
    Future<JsonObject> saveCredentials(JsonObject user, String tool, String method, JsonObject values)
    {
        return catalogue().compose(c ->
        {
            JsonObject t = known(c, tool);

            if (!t.getBoolean("seeded"))
            {
                throw ApiException.unprocessable("connector_planned",
                        t.getString("name") + " is planned; it can't be connected in this version.");
            }

            JsonArray fields = c.fields(tool, method);

            if (fields == null)
            {
                throw ApiException.badRequest("validation_failed", "Unknown sign-in method for "
                        + t.getString("name") + ".").field("method");
            }

            JsonObject masked = validateAndMask(tool, fields, values);
            UUID actor = UUID.fromString(user.getString("id"));

            return ctx.sql().inTransaction(tx ->
            {
                IntegrationsRepository repo = new IntegrationsRepository(tx);

                return repo.saveCredentials(tool, method, masked, actor).compose(added ->
                {
                    String message = (added ? "Credentials added" : "Credentials changed") + " (" + method + ")";

                    return repo.log(tool, added ? "added" : "changed", message, actor)
                            .compose(v -> AuditService.record(tx, AuditService.entry(user,
                                    AuditEvents.CREDENTIAL_CHANGED, "integration", tool, new JsonObject()
                                            .put("change", added ? "added" : "changed")
                                            .put("method", method)
                                            .put("fields", new JsonArray(List.copyOf(masked.fieldNames()))))));
                });
            });
        }).compose(v -> tool(tool));
    }

    Future<JsonObject> disconnect(JsonObject user, String tool)
    {
        return catalogue().compose(c ->
        {
            known(c, tool);

            UUID actor = UUID.fromString(user.getString("id"));

            return ctx.sql().inTransaction(tx ->
            {
                IntegrationsRepository repo = new IntegrationsRepository(tx);

                return repo.disconnect(tool).compose(n ->
                {
                    if (n == 0)
                    {
                        return Future.failedFuture(ApiException.conflict("not_connected",
                                c.tool(tool).getString("name") + " is not connected."));
                    }

                    return repo.log(tool, "disconnected", "Disconnected", actor)
                            .compose(v -> AuditService.record(tx, AuditService.entry(user,
                                    AuditEvents.CREDENTIAL_CHANGED, "integration", tool,
                                    new JsonObject().put("change", "disconnected"))));
                });
            });
        }).compose(v -> tool(tool));
    }

    Future<JsonObject> saveSyncMode(JsonObject user, String tool, String syncMode)
    {
        return catalogue().compose(c ->
        {
            known(c, tool);

            return ctx.sql().inTransaction(tx -> new IntegrationsRepository(tx).saveSyncMode(tool, syncMode)
                    .compose(v -> AuditService.record(tx, AuditService.entry(user, IntegrationsModule.SETTINGS_CHANGED,
                            "integration", tool, new JsonObject().put("syncMode", syncMode)))));
        }).compose(v -> tool(tool));
    }

    /** Jira configuration: boards, story points field, sync mode and the ignore-bot-edits switch. */
    Future<JsonObject> saveJiraConfig(JsonObject user, JsonObject body)
    {
        JsonObject config = new JsonObject()
                .put("boards", body.getJsonArray("boards"))
                .put("storyPointsField", body.getString("storyPointsField"))
                .put("ignoreBotEdits", body.getBoolean("ignoreBotEdits"));
        String syncMode = body.getString("syncMode");

        return ctx.sql().inTransaction(tx -> new IntegrationsRepository(tx).saveConfig(JIRA, config, syncMode)
                        .compose(v -> AuditService.record(tx, AuditService.entry(user, IntegrationsModule.SETTINGS_CHANGED,
                                "integration", JIRA, config.copy().put("syncMode", syncMode)))))
                .compose(v -> jiraConfig());
    }

    /**
     * Sync now: runs the tool's connector through {@code IngestService}, writes the sync log entry,
     * publishes the cache invalidation, and logs a failed run in the credential activity log.
     * Resolves to {@code {sync, tile}}.
     */
    Future<JsonObject> syncNow(JsonObject user, String tool)
    {
        return state().compose(s ->
        {
            JsonObject t = known(s.catalogue(), tool);

            if (s.settings().getOrDefault(tool, new JsonObject()).getString("method") == null)
            {
                throw ApiException.conflict("not_connected", t.getString("name") + " is not connected.");
            }

            UUID actor = UUID.fromString(user.getString("id"));

            // Phase 1 connectors read everything; live connectors (group 24) pass their stored cursor
            return new SyncService(ctx.vertx(), ctx.sql()).run(connectors.forTool(tool), tool, null, actor)
                    .compose(entry -> "failed".equals(entry.getString("outcome"))
                            ? new IntegrationsRepository(ctx.sql()).log(tool, "sync_failed",
                            "Sync failed: " + entry.getString("message"), actor).map(entry)
                            : Future.succeededFuture(entry));
        }).compose(entry -> state().map(s -> new JsonObject()
                .put("sync", entry)
                .put("tile", tile(s.catalogue().tool(tool), s))));
    }

    // ------------------------------------------------------------------ tile state

    private record State(IntegrationCatalogue catalogue, Map<String, JsonObject> settings,
                         Map<String, JsonObject> latest, Map<String, JsonObject> success)
    {
    }

    private Future<State> state()
    {
        SyncLogRepository logs = new SyncLogRepository(ctx.sql());

        return Future.all(catalogue(), new IntegrationsRepository(ctx.sql()).settings(), logs.latestPerTool(),
                        logs.latestSuccessPerTool())
                .map(all -> new State(all.resultAt(0), byTool(all.resultAt(1)), byTool(all.resultAt(2)),
                        byTool(all.resultAt(3))));
    }

    private static Map<String, JsonObject> byTool(JsonArray rows)
    {
        Map<String, JsonObject> out = new HashMap<>();

        rows.forEach(o -> out.put(((JsonObject) o).getString("tool"), (JsonObject) o));

        return out;
    }

    /**
     * One tile: "Connected · synced N min ago", "Token expires in N days", "Sync failed · code" or
     * "Not connected", with the action Settings, Fix or Connect.
     */
    private JsonObject tile(JsonObject t, State s)
    {
        String id = t.getString("id");
        JsonObject settings = s.settings().getOrDefault(id, new JsonObject());
        boolean connected = settings.getString("method") != null;

        JsonObject tile = new JsonObject()
                .put("id", id)
                .put("mark", t.getString("mark"))
                .put("name", t.getString("name"))
                .put("reads", t.getString("reads"))
                .put("feeds", t.getString("feeds"))
                .put("methods", new JsonArray(t.getJsonArray("methods").stream()
                        .map(m -> ((JsonObject) m).getString("name")).toList()))
                .put("live", connectors.registered(id))
                .put("connected", connected);

        if (!connected)
        {
            boolean planned = !t.getBoolean("seeded");

            return tile.put("state", "not_connected")
                    .put("status", "Not connected")
                    .put("action", "connect")
                    .put("connectEnabled", !planned)
                    .put("note", planned ? "Planned: not available in this version" : null);
        }

        Instant synced = latest(instant(s.success().get(id)), instant(s.success().get(SeedConnector.ALL_TOOLS)));
        JsonObject last = s.latest().get(id);

        tile.put("syncedAt", synced == null ? null : synced.toString());

        // the newest run of the tool itself failed: only a successful run of that tool clears it
        if (last != null && "failed".equals(last.getString("outcome")))
        {
            return tile.put("state", "sync_failed")
                    .put("status", "Sync failed · " + last.getString("message"))
                    .put("action", "fix");
        }

        Long days = expiresInDays(settings);

        if (days != null && days <= EXPIRY_WARNING_DAYS)
        {
            return tile.put("state", "token_expiring")
                    .put("expiresInDays", days)
                    .put("status", days < 0 ? "Token expired" : "Token expires in " + days + (days == 1 ? " day" : " days"))
                    .put("action", "fix");
        }

        return tile.put("state", "connected")
                .put("status", synced == null ? "Connected" : "Connected · synced " + ago(synced))
                .put("action", "settings");
    }

    /** Days until the stored token's known expiry, or null when none is known. */
    private Long expiresInDays(JsonObject settings)
    {
        String expires = settings.getString("token_expires_on");

        return expires == null ? null : ChronoUnit.DAYS.between(ctx.calc().clock().today(), LocalDate.parse(expires));
    }

    static String ago(Instant at)
    {
        long minutes = Math.max(0, Duration.between(at, Instant.now()).toMinutes());

        if (minutes < 1)
        {
            return "just now";
        }

        if (minutes < 60)
        {
            return minutes + " min ago";
        }

        if (minutes < 24 * 60)
        {
            return (minutes / 60) + " h ago";
        }

        return (minutes / (24 * 60)) + " d ago";
    }

    private static Instant instant(JsonObject row)
    {
        if (row == null)
        {
            return null;
        }

        String at = row.getString("finished_at") != null ? row.getString("finished_at") : row.getString("started_at");

        return at == null ? null : OffsetDateTime.parse(at).toInstant();
    }

    private static Instant latest(Instant a, Instant b)
    {
        if (a == null)
        {
            return b;
        }

        return b == null || a.isAfter(b) ? a : b;
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject known(IntegrationCatalogue c, String id)
    {
        JsonObject t = c.tool(id);

        if (t == null)
        {
            throw ApiException.notFound("not_found", "No such integration.");
        }

        return t;
    }

    private static JsonObject jiraConfig(JsonObject settings)
    {
        JsonObject config = settings.getJsonObject("config", new JsonObject());

        return new JsonObject()
                .put("boards", config.getJsonArray("boards", new JsonArray()))
                .put("storyPointsField", config.getString("storyPointsField", "customfield_10016"))
                .put("ignoreBotEdits", config.getBoolean("ignoreBotEdits", true))
                .put("syncMode", settings.getString("sync_mode", SYNC_MODES.get(0)))
                .put("syncModes", new JsonArray(SYNC_MODES));
    }

    /**
     * Checks every field of the form (required, type, length, the Jira site rule of spec "Safe outbound
     * requests") and returns the values to store, with each secret replaced by "••••" and its last four
     * letters or digits.
     */
    static JsonObject validateAndMask(String tool, JsonArray fields, JsonObject values)
    {
        JsonObject out = new JsonObject();
        Map<String, JsonObject> byKey = new HashMap<>();

        fields.forEach(f -> byKey.put(((JsonObject) f).getString("key"), (JsonObject) f));

        for (String key : values.fieldNames())
        {
            if (!byKey.containsKey(key))
            {
                throw ApiException.badRequest("validation_failed", "Unknown field.").field("values." + key);
            }
        }

        for (Object o : fields)
        {
            JsonObject f = (JsonObject) o;
            String key = f.getString("key");
            String type = f.getString("type");
            Object raw = values.getValue(key);
            String value = raw == null ? "" : String.valueOf(raw).strip();
            boolean secret = IntegrationCatalogue.SECRET_TYPES.contains(type);

            if (value.isEmpty())
            {
                if (f.getBoolean("required"))
                {
                    throw ApiException.badRequest("validation_failed", f.getString("label") + " is required.")
                            .field("values." + key);
                }

                continue;
            }

            if (value.length() > (secret ? MAX_SECRET : MAX_FIELD))
            {
                throw ApiException.badRequest("validation_failed", f.getString("label") + " is too long.")
                        .field("values." + key);
            }

            if ("url".equals(type))
            {
                checkUrl(tool, key, f.getString("label"), value);
            }

            if ("email".equals(type) && !EMAIL.matcher(value).matches())
            {
                throw ApiException.badRequest("validation_failed", "Enter a valid email address.")
                        .field("values." + key);
            }

            out.put(key, secret ? mask(value) : value);
        }

        return out;
    }

    private static void checkUrl(String tool, String key, String label, String value)
    {
        URI uri;

        try
        {
            uri = new URI(value);
        }
        catch (Exception e)
        {
            throw ApiException.badRequest("validation_failed", label + " must be a valid https address.")
                    .field("values." + key);
        }

        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);

        if (!"https".equalsIgnoreCase(uri.getScheme()) || host.isEmpty() || uri.getUserInfo() != null)
        {
            throw ApiException.badRequest("validation_failed", label + " must be a valid https address.")
                    .field("values." + key);
        }

        if (JIRA.equals(tool) && "site".equals(key) && !host.endsWith(".atlassian.net"))
        {
            throw ApiException.badRequest("validation_failed",
                    "Jira site must be an https address ending in .atlassian.net.").field("values." + key);
        }
    }

    /** "••••" and the last four letters or digits of a secret (just "••••" when it is short). */
    static String mask(String secret)
    {
        String alnum = NON_ALNUM.matcher(secret).replaceAll("");

        return alnum.length() < 12 ? "••••" : "••••" + alnum.substring(alnum.length() - 4);
    }
}
