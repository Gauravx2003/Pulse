package com.pulse.modules.connectors.jira;

import com.pulse.platform.live.BaseUrls;
import com.pulse.platform.live.LiveConnector;
import com.pulse.platform.live.LiveContext;
import com.pulse.platform.live.LiveHttpException;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The live, read-only Jira Server / Data Center connector, REST API v2 (task 26.2, change
 * pulse-v2-live-connectors design.md decision 6). Tile id and row source are both {@code jira}, so the
 * read views of V290 and V292 match its accounts.
 *
 * <ul>
 *   <li><b>Auth.</b> Basic {@code base64(username:password)} by default, or Bearer with a Data Center
 *       personal access token.</li>
 *   <li><b>Origin.</b> Exactly the base URL's scheme, host and port (and its context path).</li>
 *   <li><b>Test connection.</b> {@code /rest/api/2/myself}, then {@code /rest/api/2/field} for the story
 *       points and sprint field proposals; ok only when both answer 200.</li>
 *   <li><b>Fetch.</b> {@code /rest/api/2/search} by project and {@code updated}, 100 per page with the
 *       changelog, until {@code startAt + issues >= total}. Each page becomes one batch of sprints,
 *       issues and issue events. The cursor is the greatest {@code updated} seen; the next run asks
 *       from one minute before it (JQL has minute precision) and the upsert absorbs the repeats.</li>
 * </ul>
 */
public class JiraConnector implements LiveConnector
{
    public static final String TOOL = "jira";

    static final String BASIC = "basic";

    static final String BEARER = "bearer";

    static final int PAGE_SIZE = 100;

    static final String UNAUTHORIZED_HINT = "401 Unauthorized, check the username and password or token.";

    private static final Pattern PROJECT_KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,49}");

    private static final List<String> STORY_POINT_NAMES = List.of("story points", "story point estimate");

    private static final String SPRINT_SCHEMA = "com.pyxis.greenhopper.jira:gh-sprint";

    /** Issue fields the mapping reads; the configured story points and sprint fields are added. */
    private static final List<String> FIELDS = List.of("summary", "issuetype", "status", "assignee", "creator",
            "reporter", "created", "updated", "duedate", "resolutiondate", "timeoriginalestimate", "timeestimate",
            "timespent", "description", "comment");

    @Override
    public String tool()
    {
        return TOOL;
    }

    @Override
    public String source()
    {
        return TOOL;
    }

    // ------------------------------------------------------------------ form

    @Override
    public JsonObject configSchema()
    {
        JsonObject basicOnly = new JsonObject().put("field", "authMode").put("value", BASIC);

        return new JsonObject().put("fields", new JsonArray()
                .add(field("baseUrl", "Base URL", "url", true,
                        "Your Jira Server address, e.g. http://172.16.10.6:8080. Use https where you can: over http the "
                                + "password travels unencrypted."))
                .add(field("authMode", "Authentication", "choice", true,
                        "Basic: username and password. Bearer: a Jira Data Center personal access token.")
                        .put("options", new JsonArray()
                                .add(new JsonObject().put("value", BASIC).put("label", "Username and password"))
                                .add(new JsonObject().put("value", BEARER).put("label", "Personal access token")))
                        .put("default", BASIC))
                .add(field("username", "Username", "text", false, "The read-only Jira user Pulse signs in as.")
                        .put("dependsOn", basicOnly))
                .add(field("projectKeys", "Project keys", "list", true, "Only issues of these projects are read, e.g. PUL, OPS.")
                        .put("pattern", PROJECT_KEY.pattern()))
                .add(field("storyPointsField", "Story points field", "text", false,
                        "Proposed by Test connection, e.g. customfield_10106. Empty: story points are not read.")
                        .put("pattern", "customfield_[0-9]+"))
                .add(field("sprintField", "Sprint field", "text", false,
                        "Proposed by Test connection, e.g. customfield_10108. Empty: sprints are not read.")
                        .put("pattern", "customfield_[0-9]+"))
                .add(field("expiresOn", "Password or token expires on", "date", false,
                        "Optional. Jira does not report it; Pulse reminds you before this date."))
                .add(field("ignoreBotEdits", "Ignore edits by bots and non-assignees", "boolean", false,
                        "Last activity then comes from the assignee's own changes, not Jira's updated time.")
                        .put("default", true)));
    }

    private static JsonObject field(String name, String label, String type, boolean required, String help)
    {
        return new JsonObject().put("name", name).put("label", label).put("type", type).put("required", required)
                .put("help", help);
    }

    @Override
    public JsonObject secretSpec()
    {
        return new JsonObject()
                .put("name", "secret")
                .put("label", "Password / Token")
                .put("help", "Basic: the Jira user's password. Bearer: a personal access token (Profile → Personal "
                        + "Access Tokens). Use a read-only user; Pulse only reads.")
                .put("authModes", new JsonArray().add(BASIC).add(BEARER));
    }

    @Override
    public JsonArray allowedOrigins(JsonObject config)
    {
        return new JsonArray().add(BaseUrls.origin(config.getString("baseUrl")));
    }

    @Override
    public JsonArray requiredScopes()
    {
        return new JsonArray();
    }

    // ------------------------------------------------------------------ requests

    /** The request headers: Basic or Bearer authorization and JSON. */
    static JsonObject headers(LiveContext ctx)
    {
        JsonObject config = ctx.config();

        String authorization = BEARER.equals(config.getString("authMode"))
                ? "Bearer " + ctx.secret()
                : "Basic " + Base64.getEncoder().encodeToString(
                        (config.getString("username", "") + ":" + ctx.secret()).getBytes(StandardCharsets.UTF_8));

        return new JsonObject().put("Authorization", authorization).put("Accept", "application/json");
    }

    private static String base(LiveContext ctx)
    {
        return BaseUrls.normalize(ctx.config().getString("baseUrl"));
    }

    private static String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    // ------------------------------------------------------------------ test connection

    @Override
    public Future<JsonObject> testConnection(LiveContext ctx)
    {
        String base = base(ctx);

        JsonObject headers = headers(ctx);

        String expires = ctx.config().getString("expiresOn");

        return ctx.http().get(base + "/rest/api/2/myself", headers).compose(myself ->
        {
            int status = myself.getInteger("status");

            if (status != 200)
            {
                return Future.succeededFuture(result(false, status, null, new JsonObject(), expires, hint(status)));
            }

            JsonObject user = myself.getValue("body") instanceof JsonObject b ? b : new JsonObject();

            JsonObject account = new JsonObject()
                    .put("username", user.getString("name"))
                    .put("displayName", user.getString("displayName"))
                    .put("email", user.getString("emailAddress"));

            return ctx.http().get(base + "/rest/api/2/field", headers).map(fields ->
            {
                int fieldStatus = fields.getInteger("status");

                JsonArray all = fields.getValue("body") instanceof JsonArray a ? a : new JsonArray();

                return result(fieldStatus == 200, fieldStatus, account, proposals(all), expires, hint(fieldStatus));
            });
        });
    }

    private static JsonObject result(boolean ok, int status, JsonObject account, JsonObject fields, String expires, String hint)
    {
        return new JsonObject()
                .put("ok", ok)
                .put("status", status)
                .put("account", account)
                .put("scopes", new JsonArray())
                .put("scopeNote", "Jira Server reports no scopes: use a read-only user.")
                .put("fields", fields)
                .put("expiresAt", expires)
                .put("hint", hint);
    }

    private static String hint(int status)
    {
        return switch (status)
        {
            case 200 -> null;
            case 401 -> UNAUTHORIZED_HINT;
            case 403 -> "403 Forbidden: the user can't use the REST API (CAPTCHA after failed logins, or no access).";
            case 404 -> "404 Not found: check the base URL and its context path.";
            default -> status + " from Jira: try again later.";
        };
    }

    /**
     * Field proposals keyed by config field: {@code {storyPointsField: [{id, name}], sprintField: [{id, name}]}},
     * best candidate first.
     */
    static JsonObject proposals(JsonArray fields)
    {
        JsonArray storyPoints = new JsonArray();

        JsonArray sprint = new JsonArray();

        for (Object item : fields)
        {
            if (!(item instanceof JsonObject field) || !Boolean.TRUE.equals(field.getBoolean("custom", true)))
            {
                continue;
            }

            String name = field.getString("name", "");

            String custom = field.getJsonObject("schema", new JsonObject()).getString("custom", "");

            JsonObject proposal = new JsonObject().put("id", field.getString("id")).put("name", name);

            if (STORY_POINT_NAMES.contains(name.toLowerCase(Locale.ROOT)))
            {
                storyPoints.add(proposal);
            }

            if (SPRINT_SCHEMA.equals(custom))
            {
                sprint.add(proposal);
            }
        }

        return new JsonObject().put("storyPointsField", storyPoints).put("sprintField", sprint);
    }

    // ------------------------------------------------------------------ fetch

    /** The issue search JQL: the configured projects, updated since one minute before the cursor. */
    static String jql(JsonObject config, String since)
    {
        StringBuilder projects = new StringBuilder();

        for (Object key : config.getJsonArray("projectKeys", new JsonArray()))
        {
            String text = String.valueOf(key).strip().toUpperCase(Locale.ROOT);

            if (!PROJECT_KEY.matcher(text).matches())
            {
                throw new IllegalArgumentException("Invalid project key: " + text);
            }

            projects.append(projects.isEmpty() ? "" : ", ").append('"').append(text).append('"');
        }

        if (projects.isEmpty())
        {
            throw new IllegalArgumentException("No project keys configured");
        }

        String scope = "project in (" + projects + ")";

        return (since == null ? scope : scope + " AND updated >= \"" + JiraMapper.jqlSince(since) + "\"") + " ORDER BY updated ASC";
    }

    private static String fieldList(JsonObject config)
    {
        StringBuilder out = new StringBuilder(String.join(",", FIELDS));

        for (String name : List.of("storyPointsField", "sprintField"))
        {
            String id = config.getString(name);

            if (id != null && !id.isBlank())
            {
                out.append(',').append(id.strip());
            }
        }

        return out.toString();
    }

    @Override
    public Future<JsonObject> fetchPages(LiveContext ctx, String since, Function<JsonObject, Future<Void>> sink)
    {
        JsonObject config = ctx.config();

        String query;

        try
        {
            query = "/rest/api/2/search?jql=" + encode(jql(config, since)) + "&maxResults=" + PAGE_SIZE
                    + "&expand=changelog&fields=" + encode(fieldList(config));
        }
        catch (RuntimeException e)
        {
            return Future.failedFuture(LiveHttpException.failed(LiveHttpException.BAD_PAYLOAD, e.getMessage()));
        }

        return page(ctx, base(ctx) + query, headers(ctx), 0, since, sink);
    }

    private Future<JsonObject> page(LiveContext ctx, String url, JsonObject headers, int startAt, String cursor,
                                    Function<JsonObject, Future<Void>> sink)
    {
        return ctx.http().getOk(url + "&startAt=" + startAt, headers).compose(res ->
        {
            JsonObject body = res.getValue("body") instanceof JsonObject b ? b : null;

            JsonArray issues = body == null ? null : body.getJsonArray("issues");

            if (issues == null)
            {
                return Future.failedFuture(LiveHttpException.failed(LiveHttpException.BAD_PAYLOAD,
                        "The search answer has no issues array"));
            }

            int total = body.getInteger("total", 0);

            String latest = cursor;

            for (Object item : issues)
            {
                String updated = JiraMapper.iso(((JsonObject) item).getJsonObject("fields", new JsonObject()).getString("updated"));

                if (updated != null && (latest == null || OffsetDateTime.parse(updated).isAfter(OffsetDateTime.parse(latest))))
                {
                    latest = updated;
                }
            }

            String next = latest;

            int nextStart = startAt + issues.size();

            boolean more = !issues.isEmpty() && nextStart < total;

            Future<Void> written = issues.isEmpty()
                    ? Future.succeededFuture()
                    : resolveAccounts(ctx, issues).compose(v -> sink.apply(batch(ctx.config(), base(ctx), issues)));

            return written.compose(v -> more
                    ? page(ctx, url, headers, nextStart, next, sink)
                    : Future.succeededFuture(new JsonObject().put("cursor", next)));
        });
    }

    /** Records every user of the page once in external_accounts (assignees, changelog and comment authors). */
    private static Future<Void> resolveAccounts(LiveContext ctx, JsonArray issues)
    {
        Map<String, JsonObject> users = new LinkedHashMap<>();

        for (Object item : issues)
        {
            for (Object user : JiraMapper.users((JsonObject) item))
            {
                JsonObject account = JiraMapper.account((JsonObject) user);

                if (account != null)
                {
                    users.merge(account.getString("accountId"), account,
                            (a, b) -> a.getString("email") != null ? a : b);
                }
            }
        }

        Future<Void> chain = Future.succeededFuture();

        for (JsonObject account : users.values())
        {
            chain = chain.compose(v -> ctx.accounts().resolve(TOOL, account).mapEmpty());
        }

        return chain;
    }

    /** One normalized batch: the dated sprints, then the issues, then their events (parents first). */
    static JsonObject batch(JsonObject config, String baseUrl, JsonArray issues)
    {
        String storyPointsField = config.getString("storyPointsField");

        String sprintField = config.getString("sprintField");

        boolean ignoreBotEdits = config.getBoolean("ignoreBotEdits", true);

        Map<String, JsonObject> sprints = new LinkedHashMap<>();

        JsonArray issueRecords = new JsonArray();

        JsonArray events = new JsonArray();

        for (Object item : issues)
        {
            JsonObject hit = (JsonObject) item;

            JsonArray issueSprints = JiraMapper.sprints(hit.getJsonObject("fields", new JsonObject()), sprintField);

            JsonObject current = null;

            for (Object s : issueSprints)
            {
                JsonObject record = JiraMapper.sprintRecord((JsonObject) s);

                if (record != null)
                {
                    sprints.putIfAbsent(record.getString("external_id"), record);
                }

                current = record;
            }

            issueRecords.add(JiraMapper.issue(hit, baseUrl, storyPointsField, current, issueSprints.size()));

            events.addAll(JiraMapper.events(hit, ignoreBotEdits));
        }

        JsonArray entities = new JsonArray();

        if (!sprints.isEmpty())
        {
            entities.add(new JsonObject().put("entity", "sprints").put("records", new JsonArray(List.copyOf(sprints.values()))));
        }

        entities.add(new JsonObject().put("entity", "issues").put("records", issueRecords));
        entities.add(new JsonObject().put("entity", "issue_events").put("records", events));

        return new JsonObject().put("source", TOOL).put("entities", entities);
    }
}
