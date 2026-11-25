package com.pulse.platform.live;

import com.pulse.platform.ingest.ConnectorException;

/**
 * A guarded outbound request failed (task 24.3). {@link #code()} is what the tile shows after
 * "Sync failed · ": the HTTP status for an answer outside 2xx ({@code "401"}, {@code "429"},
 * {@code "500"}), or one of the refusal codes below. The message never contains a secret, a query
 * string or a header value.
 */
public final class LiveHttpException extends ConnectorException
{
    /** The request was refused before anything left the process: origin not declared. */
    public static final String ORIGIN = "origin";

    /** Refused before sending: not a GET. */
    public static final String METHOD = "method";

    /** Refused before sending: the path leaves the declared context path, or is malformed. */
    public static final String PATH = "path";

    /** Refused before sending: the host resolves to a loopback, link-local or metadata address. */
    public static final String ADDRESS = "address";

    /** The tool answered 3xx; redirects are never followed. */
    public static final String REDIRECT = "redirect";

    /** No answer within the connect or read timeout. */
    public static final String TIMEOUT = "timeout";

    /** The answer exceeded the response size cap. */
    public static final String TOO_LARGE = "too_large";

    /** The connection failed (refused, reset, TLS, DNS). */
    public static final String CONNECTION = "connection";

    /** The answer was not the JSON the connector expected. */
    public static final String BAD_PAYLOAD = "bad_payload";

    private final String code;

    private final boolean sent;

    private LiveHttpException(int status, String code, String reason, boolean sent)
    {
        super(status, reason);

        this.code = code;
        this.sent = sent;
    }

    /** Refused by the guard; nothing was sent. */
    public static LiveHttpException refused(String code, String reason)
    {
        return new LiveHttpException(0, code, reason, false);
    }

    /** The request was sent and failed without an HTTP status (timeout, too large, connection). */
    public static LiveHttpException failed(String code, String reason)
    {
        return new LiveHttpException(0, code, reason, true);
    }

    /** The tool answered with {@code status}. */
    public static LiveHttpException status(int status, String reason)
    {
        return new LiveHttpException(status, status >= 300 && status < 400 ? REDIRECT : String.valueOf(status),
                reason == null || reason.isBlank() ? "HTTP " + status : reason, true);
    }

    public String code()
    {
        return code;
    }

    /** False when the guard refused the request before it left the process. */
    public boolean sent()
    {
        return sent;
    }

    @Override
    public String display()
    {
        return status() > 0 ? getMessage() : code;
    }
}
