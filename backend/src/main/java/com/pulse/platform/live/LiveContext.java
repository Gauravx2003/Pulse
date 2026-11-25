package com.pulse.platform.live;

import com.pulse.platform.accounts.ExternalAccountService;
import io.vertx.core.json.JsonObject;

import java.util.Objects;

/**
 * What a {@link LiveConnector} gets for one Test connection or one sync (task 24.3, design.md
 * decision 1). A service, not a data bean: the platform builds it, hands it to the connector and
 * closes it afterwards.
 *
 * <ul>
 *   <li>{@link #config()}: the saved (or submitted) non-secret config, validated against the
 *       connector's {@code configSchema}.</li>
 *   <li>{@link #secret()}: the decrypted secret, only for the duration of the call. Never log it,
 *       never put it in a batch, a result or an exception message.</li>
 *   <li>{@link #http()}: the guarded client bound to {@code allowedOrigins(config)}; the only way to
 *       reach the network.</li>
 *   <li>{@link #accounts()}: person matching; call {@code resolve} for every assignee and author.</li>
 * </ul>
 */
public interface LiveContext extends AutoCloseable
{
    /** The tile id the call runs for. */
    String tool();

    JsonObject config();

    String secret();

    GuardedHttp http();

    ExternalAccountService accounts();

    /** Releases the HTTP client. Idempotent. */
    @Override
    default void close()
    {
        http().close();
    }

    /** A context over the given parts; the platform (and tests, with a fake service) build it this way. */
    static LiveContext of(String tool, JsonObject config, String secret, GuardedHttp http,
                          ExternalAccountService accounts)
    {
        Objects.requireNonNull(tool, "tool");
        Objects.requireNonNull(http, "http");
        Objects.requireNonNull(accounts, "accounts");

        JsonObject frozen = config == null ? new JsonObject() : config.copy();

        return new LiveContext()
        {
            @Override
            public String tool()
            {
                return tool;
            }

            @Override
            public JsonObject config()
            {
                return frozen.copy();
            }

            @Override
            public String secret()
            {
                return secret;
            }

            @Override
            public GuardedHttp http()
            {
                return http;
            }

            @Override
            public ExternalAccountService accounts()
            {
                return accounts;
            }

            @Override
            public String toString()
            {
                // never print the secret
                return "LiveContext[" + tool + "]";
            }
        };
    }
}
