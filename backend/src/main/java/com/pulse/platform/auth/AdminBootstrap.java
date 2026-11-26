package com.pulse.platform.auth;

import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.config.RuntimeProfile;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The first Admin of a database without the demo seed (change pulse-v2-live-connectors, task 24.9). Without
 * the demo seed a new database holds only roles and rules, and nobody can sign in. With
 * {@code PULSE_ADMIN_EMAIL} and {@code PULSE_ADMIN_PASSWORD} set, start-up creates that account as an
 * Active Admin, once:
 *
 * <ul>
 *   <li>Only while the database has no active Admin. Once one exists the settings are ignored: they never
 *       reset a password, change a role or add a second Admin.</li>
 *   <li>The password follows {@link PasswordPolicy} and is hashed with {@link PasswordHasher} off the event
 *       loop. It is never logged; the log names the email only.</li>
 *   <li>An email that is already taken by a user who is not an active Admin is refused, so a setting can't
 *       promote an existing account.</li>
 *   <li>A demo address ({@code @pulse.demo}) is refused under the production profile.</li>
 *   <li>The creation is audited as {@code user.created} with no actor.</li>
 * </ul>
 *
 * <p>{@code PULSE_ADMIN_NAME} sets the display name (default "Pulse Admin"). Config keys {@code adminEmail},
 * {@code adminPassword} and {@code adminName} win over the environment (for tests).
 */
public final class AdminBootstrap
{
    public static final String EMAIL_ENV = "PULSE_ADMIN_EMAIL";

    public static final String PASSWORD_ENV = "PULSE_ADMIN_PASSWORD";

    public static final String NAME_ENV = "PULSE_ADMIN_NAME";

    static final String DEFAULT_NAME = "Pulse Admin";

    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private static final Logger LOG = LoggerFactory.getLogger(AdminBootstrap.class);

    private AdminBootstrap()
    {
    }

    /** Creates the first Admin when asked to and none exists; resolves to the created user's id, or null. */
    public static Future<String> run(Vertx vertx, PgPool pool, JsonObject config, RuntimeProfile profile)
    {
        String email = setting(config, "adminEmail", EMAIL_ENV);

        if (email == null)
        {
            return Future.succeededFuture(null);
        }

        String normalized = email.toLowerCase(Locale.ROOT);

        String password = secret(config, "adminPassword", PASSWORD_ENV);

        String name = setting(config, "adminName", NAME_ENV);

        String displayName = name == null ? DEFAULT_NAME : name;

        if (!EMAIL.matcher(normalized).matches())
        {
            return refuse(EMAIL_ENV + " is not an email address.");
        }

        if (profile.production() && normalized.endsWith(RuntimeProfile.DEMO_EMAIL_DOMAIN))
        {
            return refuse(EMAIL_ENV + " can't be a demo address (" + RuntimeProfile.DEMO_EMAIL_DOMAIN
                    + ") under the production profile.");
        }

        SqlExecutor sql = new SqlExecutor(pool);

        return sql.queryOne("SELECT count(*) AS n FROM users u JOIN roles r ON r.id = u.role_id "
                        + "WHERE r.name = 'Admin' AND u.status = 'Active' AND u.deleted_at IS NULL", Tuple.tuple())
                .compose(admins ->
                {
                    if (admins.getLong("n") > 0)
                    {
                        LOG.info("{} is set but an Admin already exists: nothing created", EMAIL_ENV);

                        return Future.succeededFuture(null);
                    }

                    if (password == null)
                    {
                        return refuse(PASSWORD_ENV + " is required with " + EMAIL_ENV + " while no Admin exists.");
                    }

                    String weak = PasswordPolicy.violation(password, normalized, displayName);

                    if (weak != null)
                    {
                        return refuse(PASSWORD_ENV + ": " + weak);
                    }

                    return create(vertx, sql, normalized, displayName, password);
                });
    }

    private static Future<String> create(Vertx vertx, SqlExecutor sql, String email, String name, String password)
    {
        return sql.queryOne("SELECT id FROM users WHERE lower(email) = $1", Tuple.of(email)).compose(taken ->
        {
            if (taken != null)
            {
                return refuse("A user with " + EMAIL_ENV + " already exists and is not an active Admin; "
                        + "make them Admin on the Users screen of another Admin, or use another email.");
            }

            return PasswordHasher.hash(vertx, password).compose(hash -> sql.inTransaction(tx -> tx
                    .queryOne("INSERT INTO users (email, name, role_id, status, password_hash) "
                                    + "SELECT $1, $2, id, 'Active', $3 FROM roles WHERE name = 'Admin' RETURNING id::text AS id",
                            Tuple.of(email, name, hash))
                    .compose(row ->
                    {
                        if (row == null)
                        {
                            return refuse("The Admin role is missing: the base seed did not run.");
                        }

                        String id = row.getString("id");

                        JsonObject summary = new JsonObject().put("email", email).put("role", "Admin")
                                .put("source", "bootstrap");

                        return AuditService.record(tx, AuditService.entry(null, AuditEvents.USER_CREATED, "user", id, summary))
                                .map(v -> id);
                    })))
                    .onSuccess(id -> LOG.info("Created the first Admin {} from {}", email, EMAIL_ENV));
        });
    }

    private static <T> Future<T> refuse(String message)
    {
        return Future.failedFuture(new IllegalStateException("Refusing to start: " + message));
    }

    /** The password exactly as given (spaces are part of it); null when unset or empty. */
    private static String secret(JsonObject config, String key, String env)
    {
        Object fromConfig = config == null ? null : config.getValue(key);

        String value = fromConfig != null ? fromConfig.toString() : System.getenv(env);

        return value == null || value.isEmpty() ? null : value;
    }

    private static String setting(JsonObject config, String key, String env)
    {
        Object fromConfig = config == null ? null : config.getValue(key);

        String value = fromConfig != null ? fromConfig.toString() : System.getenv(env);

        return value == null || value.isBlank() ? null : value.strip();
    }
}
