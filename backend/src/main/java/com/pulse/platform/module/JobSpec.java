package com.pulse.platform.module;

import io.vertx.core.Future;

import java.util.function.Function;

/**
 * A scheduled job declared by a module and run in-process by the scheduler verticle.
 *
 * @param name     unique job name, e.g. {@code "work.stale-signal-refresh"}
 * @param schedule cron expression (evaluated by the scheduler)
 * @param task     the job body; must not block the event loop
 */
public record JobSpec(String name, String schedule, Function<ModuleContext, Future<Void>> task) {
}
