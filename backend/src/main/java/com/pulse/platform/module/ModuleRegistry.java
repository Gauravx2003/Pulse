package com.pulse.platform.module;

import com.pulse.platform.policy.VisibilityRelation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Discovers {@link PulseModule}s by scanning the classpath for
 * {@code com/pulse/modules/<id>/<Name>Module.class}. Works for both exploded
 * class directories (IDE, tests) and the fat jar (Docker).
 *
 * <p>Scanning does blocking I/O: call {@link #discover()} via {@code executeBlocking}.
 */
public final class ModuleRegistry
{

    private static final Logger log = LoggerFactory.getLogger(ModuleRegistry.class);

    public static final String BASE_PACKAGE = "com.pulse.modules";

    private static final String BASE_PATH = BASE_PACKAGE.replace('.', '/');

    private static final Pattern MODULE_CLASS =
            Pattern.compile("^" + Pattern.quote(BASE_PATH) + "/([a-z0-9_]+)/([A-Za-z0-9_]*Module)\\.class$");

    private final Map<String, PulseModule> modules;

    private ModuleRegistry(Map<String, PulseModule> modules)
    {

        this.modules = modules;
    }

    public static ModuleRegistry of(List<PulseModule> modules)
    {

        Map<String, PulseModule> byId = new LinkedHashMap<>();
        modules.stream()
                .sorted(Comparator.comparing(PulseModule::id))
                .forEach(m -> {
                    PulseModule previous = byId.putIfAbsent(m.id(), m);
                    if (previous != null)
                    {
                        throw new IllegalStateException("Duplicate module id '" + m.id() + "': "
                                + previous.getClass().getName() + " and " + m.getClass().getName());
                    }
                });
        return new ModuleRegistry(byId);
    }

    public static ModuleRegistry discover()
    {

        return discover(Thread.currentThread().getContextClassLoader());
    }

    public static ModuleRegistry discover(ClassLoader loader)
    {

        List<PulseModule> found = new ArrayList<>();
        for (String className : ClasspathScan.classNames(loader, BASE_PACKAGE, MODULE_CLASS))
        {
            PulseModule module = instantiate(loader, className);
            if (module != null)
            {
                found.add(module);
            }
        }
        ModuleRegistry registry = of(found);
        log.info("Discovered {} module(s): {}", registry.modules.size(), registry.modules.keySet());
        return registry;
    }

    private static PulseModule instantiate(ClassLoader loader, String className)
    {

        try
        {
            Class<?> type = Class.forName(className, true, loader);
            if (!PulseModule.class.isAssignableFrom(type) || type.isInterface()
                    || java.lang.reflect.Modifier.isAbstract(type.getModifiers()))
            {
                return null;
            }
            return (PulseModule) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("Cannot instantiate module " + className
                    + " (it needs a public no-arg constructor)", e);
        }
    }

    public Collection<PulseModule> modules()
    {

        return modules.values();
    }

    /**
     * Permissions contributed by all modules, in discovery order (the full catalogue, with the built-in
     * permissions, is {@link com.pulse.platform.auth.PermissionCatalogue}).
     */
    public Set<String> permissions()
    {

        Set<String> all = new LinkedHashSet<>();
        modules.values().forEach(m -> all.addAll(m.permissions()));
        return all;
    }

    /** Audit events declared by all modules. */
    public List<String> auditEvents()
    {
        List<String> all = new ArrayList<>();

        modules.values().forEach(m -> all.addAll(m.auditEvents()));

        return all;
    }

    /**
     * Separation-of-duties actions declared by all modules ({@link PulseModule#selfRefusedActions()});
     * the same key in two modules fails.
     */
    public Map<String, String> selfRefusedActions()
    {
        Map<String, String> all = new LinkedHashMap<>();

        modules.values().forEach(m -> m.selfRefusedActions().forEach((action, message) -> {
            if (all.putIfAbsent(action, message) != null)
            {
                throw new IllegalStateException("Separation-of-duties action '" + action
                        + "' is declared by more than one module (again by '" + m.id() + "')");
            }
        }));

        return all;
    }

    /** Visibility relations contributed by all modules, in discovery order. */
    public List<VisibilityRelation> visibilityRelations()
    {
        List<VisibilityRelation> all = new ArrayList<>();

        modules.values().forEach(m -> all.addAll(m.visibilityRelations()));

        return all;
    }

    /** Action Center providers of all modules, in discovery order. */
    public List<ActionProvider> actionProviders()
    {
        List<ActionProvider> all = new ArrayList<>();

        modules.values().forEach(m -> all.addAll(m.actionProviders()));

        return all;
    }

    /** Menu badge providers of all modules, in discovery order. */
    public List<BadgeProvider> badgeProviders()
    {
        List<BadgeProvider> all = new ArrayList<>();

        modules.values().forEach(m -> all.addAll(m.badges()));

        return all;
    }

    /** Scheduled jobs of all modules, in discovery order (task 5.5); the scheduler rejects a repeated name. */
    public List<JobSpec> jobs()
    {
        List<JobSpec> all = new ArrayList<>();

        modules.values().forEach(m -> all.addAll(m.jobs()));

        return all;
    }

    /** Raw-event tables of all modules that the retention purge empties after their window. */
    public List<RetentionRule> retention()
    {
        List<RetentionRule> all = new ArrayList<>();

        modules.values().forEach(m -> all.addAll(m.retention()));

        return all;
    }
}
