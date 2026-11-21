package com.pulse.platform.module;

import com.pulse.platform.policy.VisibilityRelation;
import io.vertx.ext.web.Router;

import java.util.List;

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

    /** Scheduled jobs run by the scheduler verticle. */
    default List<JobSpec> jobs() {
        return List.of();
    }

    /**
     * Exceptions to the base visibility rules that this module owns, e.g. "a mentor sees their mentees'
     * training data" ({@link com.pulse.platform.policy.PolicyService}).
     */
    default List<VisibilityRelation> visibilityRelations() {
        return List.of();
    }

    /** Providers that contribute items to the Action Center. */
    default List<ActionProvider> actionProviders() {
        return List.of();
    }
}
