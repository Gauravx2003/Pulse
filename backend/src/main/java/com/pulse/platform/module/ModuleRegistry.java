package com.pulse.platform.module;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Discovers {@link PulseModule}s by scanning the classpath for
 * {@code com/pulse/modules/<id>/<Name>Module.class}. Works for both exploded
 * class directories (IDE, tests) and the fat jar (Docker).
 *
 * <p>Scanning does blocking I/O: call {@link #discover()} via {@code executeBlocking}.
 */
public final class ModuleRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModuleRegistry.class);

    public static final String BASE_PACKAGE = "com.pulse.modules";
    private static final String BASE_PATH = BASE_PACKAGE.replace('.', '/');
    private static final Pattern MODULE_CLASS =
            Pattern.compile("^" + Pattern.quote(BASE_PATH) + "/([a-z0-9_]+)/([A-Za-z0-9_]*Module)\\.class$");

    private final Map<String, PulseModule> modules;

    private ModuleRegistry(Map<String, PulseModule> modules) {
        this.modules = modules;
    }

    public static ModuleRegistry of(List<PulseModule> modules) {
        Map<String, PulseModule> byId = new LinkedHashMap<>();
        modules.stream()
                .sorted(Comparator.comparing(PulseModule::id))
                .forEach(m -> {
                    PulseModule previous = byId.putIfAbsent(m.id(), m);
                    if (previous != null) {
                        throw new IllegalStateException("Duplicate module id '" + m.id() + "': "
                                + previous.getClass().getName() + " and " + m.getClass().getName());
                    }
                });
        return new ModuleRegistry(byId);
    }

    public static ModuleRegistry discover() {
        return discover(Thread.currentThread().getContextClassLoader());
    }

    public static ModuleRegistry discover(ClassLoader loader) {
        Set<String> classNames = new TreeSet<>();
        try {
            Enumeration<URL> roots = loader.getResources(BASE_PATH);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                switch (root.getProtocol()) {
                    case "file" -> scanDirectory(root, classNames);
                    case "jar" -> scanJar(root, classNames);
                    default -> log.warn("Skipping unsupported module classpath root {}", root);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Module scan failed", e);
        }

        List<PulseModule> found = new ArrayList<>();
        for (String className : classNames) {
            PulseModule module = instantiate(loader, className);
            if (module != null) {
                found.add(module);
            }
        }
        ModuleRegistry registry = of(found);
        log.info("Discovered {} module(s): {}", registry.modules.size(), registry.modules.keySet());
        return registry;
    }

    private static void scanDirectory(URL root, Set<String> out) throws IOException {
        Path base;
        try {
            base = Paths.get(root.toURI());
        } catch (URISyntaxException e) {
            throw new IOException(e);
        }
        try (Stream<Path> files = Files.walk(base, 2)) {
            files.filter(Files::isRegularFile).forEach(p -> {
                String rel = BASE_PATH + "/" + base.relativize(p).toString().replace('\\', '/');
                collect(rel, out);
            });
        }
    }

    private static void scanJar(URL root, Set<String> out) throws IOException {
        JarURLConnection conn = (JarURLConnection) root.openConnection();
        conn.setUseCaches(false);
        try (JarFile jar = conn.getJarFile()) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                collect(entries.nextElement().getName(), out);
            }
        }
    }

    private static void collect(String resourcePath, Set<String> out) {
        Matcher m = MODULE_CLASS.matcher(resourcePath);
        if (m.matches()) {
            out.add(BASE_PACKAGE + "." + m.group(1) + "." + m.group(2));
        }
    }

    private static PulseModule instantiate(ClassLoader loader, String className) {
        try {
            Class<?> type = Class.forName(className, true, loader);
            if (!PulseModule.class.isAssignableFrom(type) || type.isInterface()
                    || java.lang.reflect.Modifier.isAbstract(type.getModifiers())) {
                return null;
            }
            return (PulseModule) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate module " + className
                    + " (it needs a public no-arg constructor)", e);
        }
    }

    public Collection<PulseModule> modules() {
        return modules.values();
    }

    /** Full permission catalogue contributed by all modules, in discovery order. */
    public Set<String> permissions() {
        Set<String> all = new LinkedHashSet<>();
        modules.values().forEach(m -> all.addAll(m.permissions()));
        return all;
    }

    /** Menu visible to a user holding {@code granted}, grouped and ordered. */
    public JsonArray menuFor(Set<String> granted) {
        Map<String, JsonArray> groups = new LinkedHashMap<>();
        modules.values().stream()
                .flatMap(m -> m.menu().stream())
                .filter(e -> e.permission() == null || granted.contains(e.permission()))
                .sorted(Comparator.comparing(MenuEntry::group).thenComparingInt(MenuEntry::order)
                        .thenComparing(MenuEntry::key))
                .forEach(e -> groups.computeIfAbsent(e.group(), g -> new JsonArray()).add(e.toJson()));
        JsonArray menu = new JsonArray();
        groups.forEach((group, items) -> menu.add(new JsonObject().put("group", group).put("items", items)));
        return menu;
    }
}
