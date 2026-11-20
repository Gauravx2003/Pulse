package com.pulse.platform.db;

import com.pulse.platform.db.SqlScript.Kind;
import io.vertx.core.Future;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies pending migrations (and, with {@link Kind#SEED}, seed files) in ascending version order,
 * so V1xx, V2xx and V3xx always load in number order whatever module they belong to.
 *
 * <ul>
 *   <li>One transaction per run, guarded by a transaction-level advisory lock: concurrent starts
 *       wait, and a failing script rolls back the whole run.</li>
 *   <li>Applied scripts are recorded in {@code pulse_schema_history} with their checksum. Editing an
 *       applied script fails startup (forward-only; add a new version instead).</li>
 *   <li>A pending version lower than one already applied is still applied, with a warning, because
 *       tracks merge daily and their ranges interleave.</li>
 * </ul>
 */
public final class MigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    public static final String HISTORY_TABLE = "pulse_schema_history";
    private static final long LOCK_KEY = 7_270_001L;

    private static final String CREATE_HISTORY = """
            CREATE TABLE IF NOT EXISTS pulse_schema_history (
                kind        text        NOT NULL,
                version     integer     NOT NULL,
                description text        NOT NULL,
                script      text        NOT NULL,
                checksum    text        NOT NULL,
                applied_at  timestamptz NOT NULL DEFAULT now(),
                PRIMARY KEY (kind, version)
            )""";

    private MigrationRunner() {
    }

    /** Runs pending migrations; resolves to the scripts applied by this run. */
    public static Future<List<SqlScript>> migrate(PgPool pool, ScriptCatalog catalog) {
        return apply(pool, catalog.migrations(), Kind.MIGRATION);
    }

    /**
     * Runs pending seed files; resolves to the scripts applied by this run. Demo seeds
     * ({@code S<n>__demo_*.sql}) run only when {@code includeDemo} is true (PULSE_DEMO_SEED, task 2.4);
     * a skipped demo seed stays pending and applies once the flag is turned on.
     */
    public static Future<List<SqlScript>> seed(PgPool pool, ScriptCatalog catalog, boolean includeDemo) {
        return apply(pool, catalog.seeds(), Kind.SEED, includeDemo);
    }

    static Future<List<SqlScript>> apply(PgPool pool, List<SqlScript> scripts, Kind kind) {
        return apply(pool, scripts, kind, true);
    }

    static Future<List<SqlScript>> apply(PgPool pool, List<SqlScript> scripts, Kind kind, boolean includeDemo) {
        return pool.withTransaction(conn -> conn.preparedQuery("SELECT pg_advisory_xact_lock($1)")
                .execute(Tuple.of(LOCK_KEY))
                .compose(locked -> conn.query(CREATE_HISTORY).execute())
                .compose(created -> conn.preparedQuery(
                                "SELECT version, checksum FROM pulse_schema_history WHERE kind = $1")
                        .execute(Tuple.of(kind.name())))
                .compose(rows -> {
                    Map<Integer, String> applied = new HashMap<>();
                    for (Row row : rows) {
                        applied.put(row.getInteger("version"), row.getString("checksum"));
                    }
                    List<SqlScript> pending = pendingScripts(scripts, applied, kind).stream()
                            .filter(script -> {
                                if (script.demo() && !includeDemo) {
                                    log.info("Skipping demo seed {} ({}): PULSE_DEMO_SEED is off",
                                            script.label(), script.resource());
                                    return false;
                                }
                                return true;
                            })
                            .toList();
                    return applyInOrder(conn, pending);
                }))
                .onSuccess(done -> log.info("{}: {} applied, {} total", kind.label(), done.size(), scripts.size()));
    }

    private static List<SqlScript> pendingScripts(List<SqlScript> scripts, Map<Integer, String> applied, Kind kind) {
        int highestApplied = applied.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<SqlScript> pending = new ArrayList<>();
        for (SqlScript script : scripts) {
            String checksum = applied.get(script.version());
            if (checksum == null) {
                if (script.version() < highestApplied) {
                    log.warn("Applying {} out of order (highest applied {}{})",
                            script.label(), kind.prefix(), highestApplied);
                }
                pending.add(script);
            } else if (!checksum.equals(script.checksum())) {
                throw new IllegalStateException(script.label() + " (" + script.resource()
                        + ") was changed after it was applied. Applied " + kind.label()
                        + " are forward-only: revert the edit and add a new version, or reset the dev database.");
            }
        }
        applied.keySet().stream()
                .filter(v -> scripts.stream().noneMatch(s -> s.version() == v))
                .forEach(v -> log.warn("{}{} is recorded as applied but no longer exists on the classpath",
                        kind.prefix(), v));
        return pending;
    }

    private static Future<List<SqlScript>> applyInOrder(SqlConnection conn, List<SqlScript> pending) {
        List<SqlScript> done = new ArrayList<>();
        Future<Void> chain = Future.succeededFuture();
        for (SqlScript script : pending) {
            chain = chain.compose(v -> applyOne(conn, script).onSuccess(ok -> done.add(script)));
        }
        return chain.map(v -> done);
    }

    private static Future<Void> applyOne(SqlConnection conn, SqlScript script) {
        log.info("Applying {} {} ({})", script.label(), script.description(), script.resource());
        return conn.query(script.sql()).execute()
                .compose(r -> conn.preparedQuery("INSERT INTO pulse_schema_history "
                                + "(kind, version, description, script, checksum) VALUES ($1, $2, $3, $4, $5)")
                        .execute(Tuple.of(script.kind().name(), script.version(), script.description(),
                                script.resource(), script.checksum())))
                .<Void>mapEmpty()
                .recover(err -> Future.failedFuture(new IllegalStateException(
                        script.label() + " (" + script.resource() + ") failed: " + err.getMessage(), err)));
    }
}
