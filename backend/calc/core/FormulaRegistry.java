package com.pulse.calc.core;

import com.pulse.platform.module.ClasspathScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Finds the {@link FormulaModule}s by scanning the classpath for
 * {@code com/pulse/calc/<id>/<Name>Formulas.class} (task 4.10) and indexes their formulas by name. A
 * folder may hold several descriptors; each one's id is its folder. Fails on a duplicate formula name,
 * on a formula whose name does not start with its module id, and on a descriptor whose id is not its
 * folder.
 *
 * <p>Scanning does blocking I/O: call {@link #discover()} via {@code executeBlocking}.
 */
public final class FormulaRegistry
{
    private static final Logger log = LoggerFactory.getLogger(FormulaRegistry.class);

    public static final String BASE_PACKAGE = "com.pulse.calc";

    private static final Pattern DESCRIPTOR = Pattern.compile(
            "^" + Pattern.quote(BASE_PACKAGE.replace('.', '/')) + "/([a-z0-9_]+)/([A-Za-z0-9_]*Formulas)\\.class$");

    private final List<FormulaModule> modules;

    private final Map<String, Formula> formulas;

    private FormulaRegistry(List<FormulaModule> modules, Map<String, Formula> formulas)
    {
        this.modules = modules;
        this.formulas = formulas;
    }

    /** A registry of the given modules, checked as discovery checks them. */
    public static FormulaRegistry of(List<FormulaModule> found)
    {
        Map<String, Formula> formulas = new LinkedHashMap<>();

        List<FormulaModule> sorted = new ArrayList<>(found);

        sorted.sort(Comparator.comparing(FormulaModule::id).thenComparing(m -> m.getClass().getName()));

        for (FormulaModule m : sorted)
        {
            for (Formula f : m.formulas())
            {
                if (!f.module().equals(m.id()))
                {
                    throw new IllegalStateException("Formula '" + f.name() + "' of module '" + m.id()
                            + "' must be named '" + m.id() + ".<name>'");
                }

                if (formulas.putIfAbsent(f.name(), f) != null)
                {
                    throw new IllegalStateException("Duplicate formula '" + f.name() + "'");
                }
            }
        }

        return new FormulaRegistry(List.copyOf(sorted), formulas);
    }

    public static FormulaRegistry discover()
    {
        return discover(Thread.currentThread().getContextClassLoader());
    }

    public static FormulaRegistry discover(ClassLoader loader)
    {
        List<FormulaModule> found = new ArrayList<>();

        for (String className : ClasspathScan.classNames(loader, BASE_PACKAGE, DESCRIPTOR))
        {
            FormulaModule m = instantiate(loader, className);

            if (m == null)
            {
                continue;
            }

            String folder = className.substring(BASE_PACKAGE.length() + 1, className.lastIndexOf('.'));

            if (!folder.equals(m.id()))
            {
                throw new IllegalStateException("Formula module " + className + " must have id '" + folder + "', not '"
                        + m.id() + "'");
            }

            found.add(m);
        }

        FormulaRegistry registry = of(found);

        log.info("Discovered {} formula module(s) {} with {} formula(s)", registry.modules.size(),
                registry.modules.stream().map(FormulaModule::id).distinct().toList(), registry.formulas.size());

        return registry;
    }

    /** The formula, or null. */
    public Formula formula(String name)
    {
        return formulas.get(name);
    }

    public Collection<Formula> formulas()
    {
        return formulas.values();
    }

    public Collection<FormulaModule> modules()
    {
        return modules;
    }

    private static FormulaModule instantiate(ClassLoader loader, String className)
    {
        try
        {
            Class<?> type = Class.forName(className, true, loader);

            if (!FormulaModule.class.isAssignableFrom(type) || type.isInterface() || Modifier.isAbstract(type.getModifiers()))
            {
                return null;
            }

            return (FormulaModule) type.getDeclaredConstructor().newInstance();
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("Cannot instantiate formula module " + className
                    + " (it needs a public no-arg constructor)", e);
        }
    }
}
