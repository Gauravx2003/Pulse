package com.pulse.platform.module;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.ingest.SeedMapper;
import com.pulse.platform.policy.VisibilityRelation;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

import java.util.List;
import java.util.Map;

/**
 * Descriptor every feature module implements.
 *
 * <p>Discovery: create {@code backend/modules/<id>/} (owned by one track, design.md decision 10)
 * and put a public, no-arg class whose simple name ends in {@code Module} there, in package
 * {@code com.pulse.modules.<id>}. {@link ModuleRegistry} finds it by classpath scan, so no
 * shared registry file is ever edited. Sibling folders {@code migrations/} and {@code seed/}
 * are packaged onto the classpath under {@code modules/<id>/}.
 */
public interface PulseModule {

    /** Unique, lowercase id, e.g. {@code "okrs"}. Routes mount under {@code /api/v1/<id>}. */
    String id();

    /** Permission keys this module introduces into the catalogue. */
    default List<String> permissions() {
        return List.of();
    }

    /**
     * Menu entries; {@code GET /me} shows the ones the caller has permission for. The 21 screens of
     * the screen map are already declared by the platform ({@link com.pulse.platform.nav.Navigation});
     * a module lists only screens beyond them, and repeating a screen's key or path fails start-up.
     */
    default List<MenuEntry> menu() {
        return List.of();
    }

    /**
     * Called once for every {@link ModuleContext} (each HTTP verticle instance and the scheduler),
     * for all modules, before any routes are mounted. Register live connectors here with
     * {@code ctx.connectors().register(...)} (task 24.3). Keep it cheap and free of I/O.
     */
    default void start(ModuleContext ctx)
    {
    }

    /** Mount this module's handlers on its own sub-router (already scoped to {@code /api/v1/<id>}). */
    default void routes(Router router, ModuleContext ctx) {
    }

    /** Classpath directory holding this module's SQL migrations (consumed by the migration runner). */
    default String migrationsDir() {
        return "modules/" + id() + "/migrations";
    }

    /** Classpath directory holding this module's seed files (consumed by the seed loader). */
    default String seedDir() {
        return "modules/" + id() + "/seed";
    }

    /**
     * Seed mappers for this module's ingest tables (task 5.4): the {@link com.pulse.platform.ingest.SeedConnector}
     * reads {@code seed/extracted/*.json} through them at start-up (demo seed on) and on Sync now of a seeded
     * tool, and {@link com.pulse.platform.ingest.IngestService} upserts the records. Ingest tables are never
     * seeded by direct inserts.
     */
    default List<SeedMapper> seedMappers()
    {
        return List.of();
    }

    /** Scheduled jobs run by the scheduler verticle (task 5.5); see {@link JobSpec}. */
    default List<JobSpec> jobs() {
        return List.of();
    }

    /**
     * Raw tool-event tables this module owns, purged by the retention job after their window
     * (spec platform/privacy-retention); see {@link RetentionRule}.
     */
    default List<RetentionRule> retention() {
        return List.of();
    }

    /**
     * Exceptions to the base visibility rules that this module owns, e.g. "a mentor sees their mentees'
     * training data" ({@link com.pulse.platform.policy.PolicyService}).
     */
    default List<VisibilityRelation> visibilityRelations() {
        return List.of();
    }

    /**
     * Audit events this module records beyond the platform catalogue
     * ({@link com.pulse.platform.audit.AuditEvents}), named {@code area.action}.
     */
    default List<String> auditEvents() {
        return List.of();
    }

    /**
     * Separation-of-duties actions this module adds to the platform list
     * ({@link com.pulse.platform.policy.PolicyService#SELF_REFUSED}): action key to the sentence shown
     * when someone tries it on their own record, e.g. {@code "kudos.give" -> "You can't give yourself
     * kudos."}. Refuse with {@code ctx.policy().refuseSelf(user, action, ...)}, which writes the
     * "refused" audit entry and answers 422 {@code self_action_refused}. A key the platform or another
     * module already declares fails start-up.
     */
    default Map<String, String> selfRefusedActions() {
        return Map.of();
    }

    /** Providers that contribute items to the Action Center. */
    default List<ActionProvider> actionProviders() {
        return List.of();
    }

    /**
     * Menu badge counts this module owns ({@link BadgeProvider}, spec platform/notifications "Menu
     * badges"), e.g. stale work items or 360° reviews waiting. Use the same query as the screen's list.
     */
    default List<BadgeProvider> badges()
    {
        return List.of();
    }

    /**
     * Erases this module's personal data of a deleted user (spec platform/privacy-retention "User
     * deletion and anonymization", task 5.1). The users module calls every module's hook inside the
     * delete transaction, after the platform has erased the account and the profile; a failed future
     * rolls the whole deletion back.
     *
     * <p>Delete what the spec says is removed (for example OKRs, extra-work records, free-text pulse
     * notes, 1:1 notes, kudos text the user wrote), unlink the user as a 360° reviewer and scrub
     * comments about them, and keep aggregates keyed by the ids, which are now opaque pseudonymous
     * ids. Never copy a name, email or free text anywhere.
     *
     * @param tx      the delete transaction; build repositories from it
     * @param subject {@code {userId, personId}}; {@code personId} is null for an account without a
     *                performance profile
     */
    default Future<Void> erasePersonalData(SqlExecutor tx, JsonObject subject)
    {
        return Future.succeededFuture();
    }
}
