package com.pulse.platform.live.testkit;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves recorded fixtures of one tool on {@code http://127.0.0.1:<port>} (task 24.4, design.md
 * decision 8). Automated live connector tests talk only to this server; nothing leaves the machine.
 *
 * <p>Fixtures live on the test classpath under {@code live-fixtures/<tool>/}, described by
 * {@code routes.json} (format in {@code docs/live-connectors.md}):
 *
 * <pre>{@code
 * {
 *   "routes": [
 *     { "path": "/rest/api/2/myself", "file": "myself.json" },
 *     { "path": "/rest/api/2/search", "query": { "startAt": "0" },   "file": "search.json" },
 *     { "path": "/rest/api/2/search", "query": { "startAt": "100" }, "file": "search-page2.json",
 *       "status": 200, "headers": { "X-Custom": "v" } }
 *   ],
 *   "errors": {
 *     "401": { "file": "error-401.json", "headers": { "WWW-Authenticate": "Basic realm=\"jira\"" } }
 *   }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A route matches on method ({@code GET} by default), exact path and every listed query
 *       parameter (a value of {@code "*"} only requires the parameter). The first match wins; no match
 *       answers 404.</li>
 *   <li>{@code {{baseUrl}}} in a body or header value is replaced by {@link #baseUrl()}, so recorded
 *       {@code self} and {@code Link} URLs point back at this server.</li>
 *   <li>Anything but GET is recorded and answered 405.</li>
 *   <li>Faults replace the fixture answer: {@link #status}, {@link #stall} and {@link #redirect}, either
 *       on the n-th request since the last {@link #reset} ({@link #faultAt}) or on every request to a
 *       path ({@link #faultOn}). A status fault answers with the tool's recorded error body from
 *       {@code errors} when there is one.</li>
 * </ul>
 *
 * <p>Every request is recorded as {@code {n, method, path, query, uri, headers}} ({@link #requests}),
 * with lower-case header names. Recorded headers hold the credentials the connector sent: assert on
 * them, never print them.
 */
public final class LiveMockServer implements AutoCloseable
{
    public static final String FIXTURES_ROOT = "live-fixtures/";

    private static final String BASE_URL = "{{baseUrl}}";

    private final String tool;

    private final JsonArray routes;

    private final JsonObject errors;

    /** file name -> content */
    private final Map<String, String> files;

    private final List<JsonObject> requests = new ArrayList<>();

    private final Map<Integer, JsonObject> faultsByNumber = new ConcurrentHashMap<>();

    private final Map<String, JsonObject> faultsByPath = new ConcurrentHashMap<>();

    private final AtomicInteger counter = new AtomicInteger();

    private HttpServer server;

    private String baseUrl;

    private LiveMockServer(String tool, JsonArray routes, JsonObject errors, Map<String, String> files)
    {
        this.tool = tool;
        this.routes = routes;
        this.errors = errors;
        this.files = files;
    }

    // ------------------------------------------------------------------ lifecycle

    /** Loads {@code live-fixtures/<tool>/routes.json} and its files, then listens on a free port. */
    public static Future<LiveMockServer> start(Vertx vertx, String tool)
    {
        return vertx.executeBlocking(() -> load(tool), false)
                .compose(mock -> mock.listen(vertx));
    }

    /** Loads and checks a tool's fixtures without starting a server; fails on any malformed entry. */
    public static LiveMockServer load(String tool)
    {
        String dir = FIXTURES_ROOT + tool + "/";

        String routesText = read(dir + "routes.json");

        if (routesText == null)
        {
            throw new IllegalArgumentException("No fixtures: " + dir + "routes.json is not on the test classpath");
        }

        JsonObject manifest = new JsonObject(routesText);

        JsonArray routes = manifest.getJsonArray("routes", new JsonArray());

        JsonObject errors = manifest.getJsonObject("errors", new JsonObject());

        if (routes.isEmpty())
        {
            throw new IllegalArgumentException(dir + "routes.json declares no routes");
        }

        Map<String, String> files = new ConcurrentHashMap<>();

        for (int i = 0; i < routes.size(); i++)
        {
            JsonObject route = routes.getJsonObject(i);

            String path = route.getString("path");

            if (path == null || !path.startsWith("/"))
            {
                throw new IllegalArgumentException(dir + "routes.json route " + i + " needs a path starting with /");
            }

            loadFile(dir, route, files);
        }

        for (String status : errors.fieldNames())
        {
            if (!status.matches("[1-5][0-9][0-9]"))
            {
                throw new IllegalArgumentException(dir + "routes.json error key must be an HTTP status: " + status);
            }

            loadFile(dir, errors.getJsonObject(status), files);
        }

        return new LiveMockServer(tool, routes, errors, files);
    }

    private static void loadFile(String dir, JsonObject entry, Map<String, String> files)
    {
        String file = entry.getString("file");

        if (file == null)
        {
            return;
        }

        if (file.contains("..") || file.startsWith("/"))
        {
            throw new IllegalArgumentException(dir + "routes.json file must be relative: " + file);
        }

        String content = read(dir + file);

        if (content == null)
        {
            throw new IllegalArgumentException("Fixture file missing: " + dir + file);
        }

        files.put(file, content);
    }

    private static String read(String resource)
    {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();

        try (InputStream in = (loader == null ? LiveMockServer.class.getClassLoader() : loader)
                .getResourceAsStream(resource))
        {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Cannot read " + resource, e);
        }
    }

    private Future<LiveMockServer> listen(Vertx vertx)
    {
        return vertx.createHttpServer()
                .requestHandler(this::handle)
                .listen(0, "127.0.0.1")
                .map(s ->
                {
                    server = s;
                    baseUrl = "http://127.0.0.1:" + s.actualPort();

                    return this;
                });
    }

    @Override
    public void close()
    {
        if (server != null)
        {
            server.close();
        }
    }

    // ------------------------------------------------------------------ test API

    public String tool()
    {
        return tool;
    }

    /** {@code http://127.0.0.1:<port>}, with no trailing slash. */
    public String baseUrl()
    {
        return baseUrl;
    }

    /** {@code {scheme, host, port}} of {@link #baseUrl()}. */
    public JsonObject origin()
    {
        return new JsonObject().put("scheme", "http").put("host", "127.0.0.1").put("port", server.actualPort());
    }

    /** Requests received since the last {@link #reset}, in order. */
    public synchronized List<JsonObject> requests()
    {
        List<JsonObject> out = new ArrayList<>();

        requests.forEach(r -> out.add(r.copy()));

        return out;
    }

    /** Forgets the recorded requests and every fault; request numbers start again at 1. */
    public synchronized LiveMockServer reset()
    {
        requests.clear();
        faultsByNumber.clear();
        faultsByPath.clear();
        counter.set(0);

        return this;
    }

    /** Answers the {@code n}-th request (1-based, counted since the last reset) with {@code fault}. */
    public LiveMockServer faultAt(int n, JsonObject fault)
    {
        faultsByNumber.put(n, fault.copy());

        return this;
    }

    /** Answers every request to {@code path} with {@code fault}. */
    public LiveMockServer faultOn(String path, JsonObject fault)
    {
        faultsByPath.put(path, fault.copy());

        return this;
    }

    /** A fault answering {@code status}, with the tool's recorded error body when there is one. */
    public static JsonObject status(int status)
    {
        return new JsonObject().put("type", "status").put("status", status);
    }

    /** A fault that never answers, so the client hits its read timeout. */
    public static JsonObject stall()
    {
        return new JsonObject().put("type", "stall");
    }

    /** A fault answering {@code 302 Location: location}. */
    public static JsonObject redirect(String location)
    {
        return new JsonObject().put("type", "redirect").put("location", location);
    }

    // ------------------------------------------------------------------ serving

    private void handle(HttpServerRequest req)
    {
        int n = counter.incrementAndGet();

        JsonObject query = new JsonObject();

        req.params().names().forEach(name -> query.put(name, req.getParam(name)));

        JsonObject headers = new JsonObject();

        req.headers().forEach(h -> headers.put(h.getKey().toLowerCase(Locale.ROOT), h.getValue()));

        synchronized (this)
        {
            requests.add(new JsonObject()
                    .put("n", n)
                    .put("method", req.method().name())
                    .put("path", req.path())
                    .put("query", query)
                    .put("uri", req.uri())
                    .put("headers", headers));
        }

        JsonObject fault = faultsByNumber.get(n);

        if (fault == null)
        {
            fault = faultsByPath.get(req.path());
        }

        if (fault != null)
        {
            answerFault(req, fault);

            return;
        }

        if (!"GET".equals(req.method().name()))
        {
            json(req, 405, null, new JsonObject().put("errorMessages",
                    new JsonArray().add("Method not allowed")).encode());

            return;
        }

        JsonObject route = match(req.method().name(), req.path(), query);

        if (route == null)
        {
            json(req, 404, null, new JsonObject().put("errorMessages",
                    new JsonArray().add("No fixture for " + req.path())).encode());

            return;
        }

        json(req, route.getInteger("status", 200), route.getJsonObject("headers"), body(route));
    }

    private JsonObject match(String method, String path, JsonObject query)
    {
        for (int i = 0; i < routes.size(); i++)
        {
            JsonObject route = routes.getJsonObject(i);

            if (!route.getString("method", "GET").equalsIgnoreCase(method) || !route.getString("path").equals(path))
            {
                continue;
            }

            JsonObject wanted = route.getJsonObject("query", new JsonObject());

            boolean all = true;

            for (String name : wanted.fieldNames())
            {
                String expected = String.valueOf(wanted.getValue(name));

                String actual = query.getString(name);

                if (actual == null || (!"*".equals(expected) && !expected.equals(actual)))
                {
                    all = false;

                    break;
                }
            }

            if (all)
            {
                return route;
            }
        }

        return null;
    }

    private void answerFault(HttpServerRequest req, JsonObject fault)
    {
        switch (fault.getString("type"))
        {
            case "stall" ->
            {
                // never answer: the client's read timeout ends the request
            }

            case "redirect" -> req.response()
                    .setStatusCode(302)
                    .putHeader("Location", fault.getString("location"))
                    .end();

            default ->
            {
                int status = fault.getInteger("status");

                JsonObject recorded = errors.getJsonObject(String.valueOf(status));

                if (recorded != null)
                {
                    json(req, status, recorded.getJsonObject("headers"), body(recorded));
                }
                else
                {
                    json(req, status, null, new JsonObject().put("errorMessages",
                            new JsonArray().add("HTTP " + status)).encode());
                }
            }
        }
    }

    private String body(JsonObject entry)
    {
        String file = entry.getString("file");

        String text;

        if (file != null)
        {
            text = files.get(file);
        }
        else if (entry.getValue("body") != null)
        {
            Object body = entry.getValue("body");

            text = body instanceof String s ? s : Json.encode(body);
        }
        else
        {
            text = "";
        }

        return text.replace(BASE_URL, baseUrl);
    }

    private void json(HttpServerRequest req, int status, JsonObject headers, String body)
    {
        HttpServerResponse res = req.response().setStatusCode(status).putHeader("Content-Type", "application/json");

        if (headers != null)
        {
            for (String name : headers.fieldNames())
            {
                res.putHeader(name, String.valueOf(headers.getValue(name)).replace(BASE_URL, baseUrl));
            }
        }

        res.end(body);
    }
}
