package com.pulse.platform.live;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.streams.WriteStream;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.codec.BodyCodec;
import io.vertx.ext.web.codec.spi.BodyStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The only way a live connector reaches the network (task 24.3, design.md decision 3): a wrapper of
 * the Vert.x {@link WebClient} bound to the origins the connector declared for its config.
 *
 * <p>Every request is checked before anything leaves the process:
 * <ul>
 *   <li>GET only.</li>
 *   <li>Scheme, host and port equal one declared origin exactly ({@code http} and {@code https} are
 *       both possible when declared).</li>
 *   <li>The path stays under the origin's {@code pathPrefix}, with no dot segments or encoded
 *       separators; no user info or fragment.</li>
 *   <li>The host is resolved per request and the connection goes to the checked address. A host name
 *       resolving to a loopback, link-local, metadata or unspecified address is refused; a literal
 *       IP is used as configured (link-local, metadata and unspecified literals are refused too).</li>
 * </ul>
 * Then: redirects are never followed (a 3xx fails with {@link LiveHttpException#REDIRECT}), the
 * connect timeout is 5 s, the read (idle) timeout 15 s, the body is capped at 10 MB, and the log line
 * holds the tool, the path and the status only: never the query, a header or the body.
 *
 * <p>Responses resolve to {@code {status, headers: {lower-case name: value}, body}} where {@code body}
 * is the parsed JSON ({@link JsonObject}, {@link JsonArray}) or null when empty or not JSON.
 */
public class GuardedHttp implements AutoCloseable
{
    public static final int CONNECT_TIMEOUT_MS = 5_000;

    public static final int READ_TIMEOUT_MS = 15_000;

    public static final long MAX_BYTES = 10L * 1024 * 1024;

    /** Bodies above this size are parsed off the event loop. */
    private static final int INLINE_PARSE_BYTES = 64 * 1024;

    private static final Logger log = LoggerFactory.getLogger(GuardedHttp.class);

    /** Host name resolution; blocking, so it always runs in {@code executeBlocking}. */
    @FunctionalInterface
    public interface Resolver
    {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final Vertx vertx;

    private final String tool;

    private final JsonArray origins;

    private final Resolver resolver;

    private final int readTimeoutMs;

    private final long maxBytes;

    private final WebClient client;

    private final AtomicBoolean closed = new AtomicBoolean();

    /** A client for {@code tool} bound to {@code allowedOrigins}, with the standard limits. */
    public GuardedHttp(Vertx vertx, String tool, JsonArray allowedOrigins)
    {
        this(vertx, tool, allowedOrigins, new JsonObject(), InetAddress::getAllByName);
    }

    /**
     * As above with limits from {@code options}: {@code connectTimeoutMs}, {@code readTimeoutMs},
     * {@code maxBytes} (each at most the standard value), and a resolver (tests).
     */
    public GuardedHttp(Vertx vertx, String tool, JsonArray allowedOrigins, JsonObject options, Resolver resolver)
    {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.tool = Objects.requireNonNull(tool, "tool");
        this.origins = normalizeOrigins(allowedOrigins);
        this.resolver = Objects.requireNonNull(resolver, "resolver");

        JsonObject o = options == null ? new JsonObject() : options;

        int connectTimeoutMs = (int) Math.min(o.getLong("connectTimeoutMs", (long) CONNECT_TIMEOUT_MS),
                CONNECT_TIMEOUT_MS);

        this.readTimeoutMs = (int) Math.min(o.getLong("readTimeoutMs", (long) READ_TIMEOUT_MS), READ_TIMEOUT_MS);

        this.maxBytes = Math.min(o.getLong("maxBytes", MAX_BYTES), MAX_BYTES);

        this.client = WebClient.create(vertx, new WebClientOptions()
                .setFollowRedirects(false)
                .setConnectTimeout(connectTimeoutMs)
                .setIdleTimeout(0)
                .setVerifyHost(true)
                .setForceSni(true)
                .setTryUseCompression(false)
                .setKeepAlive(true)
                .setUserAgent("Pulse live connector"));
    }

    /** The declared origins, normalized: {@code [{scheme, host, port, pathPrefix}]}. */
    public JsonArray origins()
    {
        return origins.copy();
    }

    /** GET {@code url} (absolute) with {@code headers}; resolves for any non-3xx status. */
    public Future<JsonObject> get(String url, JsonObject headers)
    {
        return send("GET", url, headers);
    }

    /** As {@link #get}, failing with {@link LiveHttpException} for any status outside 2xx. */
    public Future<JsonObject> getOk(String url, JsonObject headers)
    {
        return get(url, headers).compose(res ->
        {
            int status = res.getInteger("status");

            return status >= 200 && status < 300
                    ? Future.succeededFuture(res)
                    : Future.failedFuture(LiveHttpException.status(status, res.getString("reason")));
        });
    }

    /**
     * Sends {@code method url}. Anything but GET is refused before sending; so is a request outside
     * the declared origins. Prefer {@link #get}; this entry point exists so the refusal is explicit.
     */
    public Future<JsonObject> send(String method, String url, JsonObject headers)
    {
        return admit(method, url).compose(target -> execute(target, headers));
    }

    @Override
    public void close()
    {
        if (closed.compareAndSet(false, true))
        {
            client.close();
        }
    }

    // ------------------------------------------------------------------ checks

    /**
     * Checks {@code method url} against every rule and resolves the address to connect to, without
     * sending anything: {@code {scheme, host, port, uri, path, address}}.
     */
    Future<JsonObject> admit(String method, String url)
    {
        if (closed.get())
        {
            return Future.failedFuture(LiveHttpException.refused(LiveHttpException.CONNECTION, "client closed"));
        }

        if (!"GET".equals(method))
        {
            return refuse(LiveHttpException.METHOD, "Only GET requests are allowed");
        }

        JsonObject target;

        try
        {
            target = target(url);
        }
        catch (IllegalArgumentException e)
        {
            return refuse(LiveHttpException.PATH, e.getMessage());
        }

        JsonObject origin = matchOrigin(target);

        if (origin == null)
        {
            return refuse(LiveHttpException.ORIGIN, "Origin not declared by the connector");
        }

        String prefix = origin.getString("pathPrefix", "");

        String path = target.getString("path");

        if (!prefix.isEmpty() && !(path.equals(prefix) || path.startsWith(prefix + "/")))
        {
            return refuse(LiveHttpException.PATH, "Path outside the configured context path");
        }

        return address(target.getString("host")).map(address -> target.put("address", address));
    }

    private Future<String> address(String host)
    {
        InetAddress literal = AddressRules.literal(host);

        if (AddressRules.isLiteral(host))
        {
            if (literal == null || AddressRules.never(literal))
            {
                return refuse(LiveHttpException.ADDRESS, "Address not allowed");
            }

            // configured literally: loopback and LAN literals are used as configured
            return Future.succeededFuture(literal.getHostAddress());
        }

        if (AddressRules.loopbackName(host))
        {
            return refuse(LiveHttpException.ADDRESS, "Address not allowed");
        }

        return vertx.executeBlocking(() -> resolver.resolve(host), false)
                .recover(err -> Future.failedFuture(LiveHttpException.failed(LiveHttpException.CONNECTION,
                        "Host name not found")))
                .compose(addresses ->
                {
                    if (addresses == null || addresses.length == 0)
                    {
                        return Future.failedFuture(LiveHttpException.failed(LiveHttpException.CONNECTION,
                                "Host name not found"));
                    }

                    // one bad answer refuses the host: a rebinding name must not get a second chance
                    for (InetAddress a : addresses)
                    {
                        if (AddressRules.blockedByName(a))
                        {
                            return refuse(LiveHttpException.ADDRESS, "Host resolves to an address that is not "
                                    + "allowed");
                        }
                    }

                    return Future.succeededFuture(addresses[0].getHostAddress());
                });
    }

    private JsonObject matchOrigin(JsonObject target)
    {
        for (int i = 0; i < origins.size(); i++)
        {
            JsonObject o = origins.getJsonObject(i);

            if (o.getString("scheme").equals(target.getString("scheme"))
                    && o.getString("host").equals(target.getString("host"))
                    && o.getInteger("port").equals(target.getInteger("port")))
            {
                return o;
            }
        }

        return null;
    }

    /** {@code {scheme, host, port, path, uri}} of an absolute request URL; refuses what can't be checked. */
    static JsonObject target(String url)
    {
        if (url == null || url.chars().anyMatch(c -> c <= 0x20 || c == '\\' || c >= 0x7f))
        {
            throw new IllegalArgumentException("Malformed URL");
        }

        URI uri;

        try
        {
            uri = new URI(url);
        }
        catch (URISyntaxException e)
        {
            throw new IllegalArgumentException("Malformed URL");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);

        if (!scheme.equals("http") && !scheme.equals("https"))
        {
            throw new IllegalArgumentException("Only absolute http or https URLs");
        }

        if (uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getHost() == null)
        {
            throw new IllegalArgumentException("Malformed URL");
        }

        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();

        if (!BaseUrls.safePath(path))
        {
            throw new IllegalArgumentException("Path not allowed");
        }

        int port = uri.getPort() == -1 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();

        String query = uri.getRawQuery();

        return new JsonObject()
                .put("scheme", scheme)
                .put("host", uri.getHost().toLowerCase(Locale.ROOT))
                .put("port", port)
                .put("path", path)
                .put("uri", query == null ? path : path + "?" + query);
    }

    private static JsonArray normalizeOrigins(JsonArray declared)
    {
        JsonArray out = new JsonArray();

        if (declared == null)
        {
            return out;
        }

        for (int i = 0; i < declared.size(); i++)
        {
            JsonObject o = declared.getJsonObject(i);

            String scheme = o.getString("scheme", "").toLowerCase(Locale.ROOT);

            if (!scheme.equals("http") && !scheme.equals("https"))
            {
                throw new IllegalArgumentException("Origin scheme must be http or https: " + scheme);
            }

            String host = Objects.requireNonNull(o.getString("host"), "origin host").toLowerCase(Locale.ROOT);

            Integer port = o.getInteger("port");

            String prefix = o.getString("pathPrefix", "");

            while (prefix.endsWith("/"))
            {
                prefix = prefix.substring(0, prefix.length() - 1);
            }

            if (!prefix.isEmpty() && !BaseUrls.safePath(prefix))
            {
                throw new IllegalArgumentException("Origin path prefix not allowed");
            }

            out.add(new JsonObject()
                    .put("scheme", scheme)
                    .put("host", host)
                    .put("port", port != null ? port : (scheme.equals("https") ? 443 : 80))
                    .put("pathPrefix", prefix));
        }

        return out;
    }

    private static <T> Future<T> refuse(String code, String reason)
    {
        return Future.failedFuture(LiveHttpException.refused(code, reason));
    }

    // ------------------------------------------------------------------ sending

    private Future<JsonObject> execute(JsonObject target, JsonObject headers)
    {
        String host = target.getString("host");

        int port = target.getInteger("port");

        String path = target.getString("path");

        RequestOptions options = new RequestOptions()
                .setHost(AddressRules.unbracket(host))
                .setPort(port)
                .setURI(target.getString("uri"))
                .setSsl("https".equals(target.getString("scheme")))
                .setFollowRedirects(false);

        var request = client.request(HttpMethod.GET,
                        SocketAddress.inetSocketAddress(port, target.getString("address")), options)
                .followRedirects(false)
                .idleTimeout(readTimeoutMs)
                .as(capped(maxBytes));

        if (headers != null)
        {
            for (String name : headers.fieldNames())
            {
                Object value = headers.getValue(name);

                if (value != null && !name.equalsIgnoreCase("host"))
                {
                    request.putHeader(name, value.toString());
                }
            }
        }

        return request.send()
                .recover(err -> Future.failedFuture(translate(err)))
                .onFailure(err -> log.warn("live {} GET {} -> {}", tool, path,
                        err instanceof LiveHttpException e ? e.code() : "error"))
                .compose(res ->
                {
                    log.info("live {} GET {} -> {}", tool, path, res.statusCode());

                    if (res.statusCode() >= 300 && res.statusCode() < 400)
                    {
                        return Future.failedFuture(LiveHttpException.status(res.statusCode(), "Redirect not followed"));
                    }

                    return toJson(res);
                });
    }

    private Future<JsonObject> toJson(HttpResponse<Buffer> res)
    {
        JsonObject headers = new JsonObject();

        res.headers().names().forEach(name -> headers.put(name.toLowerCase(Locale.ROOT),
                String.join(", ", res.headers().getAll(name))));

        Buffer body = res.body();

        JsonObject out = new JsonObject()
                .put("status", res.statusCode())
                .put("reason", res.statusMessage())
                .put("headers", headers);

        if (body == null || body.length() == 0)
        {
            return Future.succeededFuture(out.putNull("body"));
        }

        if (body.length() <= INLINE_PARSE_BYTES)
        {
            return Future.succeededFuture(out.put("body", parse(body)));
        }

        return vertx.executeBlocking(() -> out.put("body", parse(body)), false);
    }

    private static Object parse(Buffer body)
    {
        try
        {
            Object value = Json.decodeValue(body);

            return value instanceof JsonObject || value instanceof JsonArray ? value : null;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }

    private static LiveHttpException translate(Throwable err)
    {
        if (err instanceof LiveHttpException e)
        {
            return e;
        }

        if (err instanceof TimeoutException || err instanceof io.netty.channel.ConnectTimeoutException
                || (err.getClass().getSimpleName().contains("Timeout")))
        {
            return LiveHttpException.failed(LiveHttpException.TIMEOUT, "No answer in time");
        }

        return LiveHttpException.failed(LiveHttpException.CONNECTION, "Connection failed");
    }

    // ------------------------------------------------------------------ size cap

    /** A body codec that buffers at most {@code max} bytes and fails with {@code too_large} beyond. */
    static BodyCodec<Buffer> capped(long max)
    {
        return handler -> handler.handle(Future.succeededFuture(new CappedStream(max)));
    }

    private static final class CappedStream implements BodyStream<Buffer>
    {
        private final long max;

        private final Buffer buffer = Buffer.buffer();

        private final Promise<Buffer> promise = Promise.promise();

        private CappedStream(long max)
        {
            this.max = max;
        }

        @Override
        public Future<Buffer> result()
        {
            return promise.future();
        }

        @Override
        public void handle(Throwable err)
        {
            promise.tryFail(err);
        }

        @Override
        public WriteStream<Buffer> exceptionHandler(Handler<Throwable> handler)
        {
            return this;
        }

        @Override
        public Future<Void> write(Buffer data)
        {
            if (promise.future().isComplete())
            {
                // over the cap already: drop the rest
                return Future.succeededFuture();
            }

            if (buffer.length() + (long) data.length() > max)
            {
                LiveHttpException tooLarge = LiveHttpException.failed(LiveHttpException.TOO_LARGE,
                        "Answer larger than the size limit");

                promise.tryFail(tooLarge);

                return Future.failedFuture(tooLarge);
            }

            buffer.appendBuffer(data);

            return Future.succeededFuture();
        }

        @Override
        public void write(Buffer data, Handler<AsyncResult<Void>> handler)
        {
            Future<Void> f = write(data);

            if (handler != null)
            {
                f.onComplete(handler);
            }
        }

        @Override
        public void end(Handler<AsyncResult<Void>> handler)
        {
            promise.tryComplete(buffer);

            if (handler != null)
            {
                handler.handle(Future.succeededFuture());
            }
        }

        @Override
        public WriteStream<Buffer> setWriteQueueMaxSize(int maxSize)
        {
            return this;
        }

        @Override
        public boolean writeQueueFull()
        {
            return false;
        }

        @Override
        public WriteStream<Buffer> drainHandler(Handler<Void> handler)
        {
            return this;
        }
    }
}
