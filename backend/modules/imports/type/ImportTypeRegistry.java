package com.pulse.modules.imports.type;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Every {@link ImportType} the running application offers, found by classpath scan for
 * {@code com/pulse/modules/<module>/.../<Name>ImportType.class} (see {@link ImportType} for how a
 * module joins). Built once at startup and read-only afterwards, so it is safe to share across
 * event loops.
 *
 * <p>Scanning does blocking I/O: call {@link #discover} inside {@code vertx.executeBlocking(...)}.
 */
public final class ImportTypeRegistry
{
    private static final Logger LOG = LoggerFactory.getLogger(ImportTypeRegistry.class);

    private static final String BASE_PACKAGE = "com.pulse.modules";

    private static final String BASE_PATH = BASE_PACKAGE.replace('.', '/');

    /** Any depth under one module's package; nested classes ({@code $}) never match. */
    private static final Pattern IMPORT_TYPE_CLASS = Pattern.compile(
            "^" + Pattern.quote(BASE_PATH) + "/((?:[a-z0-9_]+/)+)([A-Za-z0-9_]+ImportType)\\.class$");

    private static final Pattern ID = Pattern.compile("^[a-z][a-z0-9_]*$");

    private final Map<String, ImportType> types;

    private ImportTypeRegistry(Map<String, ImportType> types)
    {
        this.types = Collections.unmodifiableMap(types);
    }

    public static ImportTypeRegistry discover(ClassLoader loader)
    {
        Set<String> classNames = new TreeSet<>();

        try
        {
            Enumeration<URL> roots = loader.getResources(BASE_PATH);

            while (roots.hasMoreElements())
            {
                URL root = roots.nextElement();

                switch (root.getProtocol())
                {
                    case "file" -> scanDirectory(root, classNames);
                    case "jar" -> scanJar(root, classNames);
                    default -> LOG.warn("Skipping unsupported import-type classpath root {}", root);
                }
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Import-type scan failed", e);
        }

        List<ImportType> found = new ArrayList<>();

        for (String className : classNames)
        {
            ImportType type = instantiate(loader, className);

            if (type != null)
            {
                found.add(type);
            }
        }

        ImportTypeRegistry registry = of(found);

        LOG.info("Discovered {} import type(s): {}", registry.types.size(), registry.types.keySet());

        return registry;
    }

    /**
     * Builds a registry from explicit instances (tests, and the end of {@link #discover}).
     *
     * @throws IllegalStateException when an id is invalid or used twice, or a definition is incomplete
     */
    public static ImportTypeRegistry of(Collection<ImportType> found)
    {
        Map<String, ImportType> byId = new LinkedHashMap<>();

        List<ImportType> sorted = new ArrayList<>(found);

        sorted.sort(Comparator.comparing(ImportType::id, Comparator.nullsFirst(Comparator.naturalOrder())));

        for (ImportType type : sorted)
        {
            verify(type);

            ImportType previous = byId.putIfAbsent(type.id(), type);

            if (previous != null)
            {
                throw new IllegalStateException("Duplicate import type id '" + type.id() + "': "
                        + previous.getClass().getName() + " and " + type.getClass().getName());
            }
        }

        return new ImportTypeRegistry(byId);
    }

    public Optional<ImportType> find(String id)
    {
        return Optional.ofNullable(types.get(id));
    }

    public Collection<ImportType> all()
    {
        return types.values();
    }

    /** Import types the holder of {@code granted} may run (one matching permission is enough). */
    public List<ImportType> allowedFor(Collection<String> granted)
    {
        List<ImportType> allowed = new ArrayList<>();

        for (ImportType type : types.values())
        {
            if (isAllowed(type, granted))
            {
                allowed.add(type);
            }
        }

        return allowed;
    }

    public static boolean isAllowed(ImportType type, Collection<String> granted)
    {
        for (String permission : type.permissions())
        {
            if (granted.contains(permission))
            {
                return true;
            }
        }

        return false;
    }

    /** True when {@code mode} is one of the type's mode keys. */
    public static boolean hasMode(ImportType type, String mode)
    {
        for (Object item : type.modes())
        {
            if (((JsonObject) item).getString("key").equals(mode))
            {
                return true;
            }
        }

        return false;
    }

    /** The first listed mode. */
    public static String defaultMode(ImportType type)
    {
        return type.modes().getJsonObject(0).getString("key");
    }

    private static void verify(ImportType type)
    {
        String name = type.getClass().getName();

        if (type.id() == null || !ID.matcher(type.id()).matches())
        {
            throw new IllegalStateException(name + ": id must be lower-case letters, digits and underscores, got '" + type.id() + "'");
        }

        if (type.name() == null || type.name().isBlank())
        {
            throw new IllegalStateException(name + ": name is required");
        }

        if (type.permissions() == null || type.permissions().isEmpty())
        {
            throw new IllegalStateException(name + ": at least one permission is required");
        }

        if (type.targetTables() == null || type.targetTables().isEmpty())
        {
            throw new IllegalStateException(name + ": at least one target table is required");
        }

        JsonArray columns = type.columns();

        if (columns == null || columns.isEmpty())
        {
            throw new IllegalStateException(name + ": at least one column is required");
        }

        JsonArray modes = type.modes();

        if (modes == null || modes.isEmpty())
        {
            throw new IllegalStateException(name + ": at least one mode is required");
        }

        for (Object item : modes)
        {
            if (!(item instanceof JsonObject mode) || mode.getString("key") == null || mode.getString("label") == null)
            {
                throw new IllegalStateException(name + ": every mode needs a key and a label");
            }
        }
    }

    private static void scanDirectory(URL root, Set<String> out) throws IOException
    {
        Path base;

        try
        {
            base = Paths.get(root.toURI());
        }
        catch (URISyntaxException e)
        {
            throw new IOException(e);
        }

        try (Stream<Path> files = Files.walk(base))
        {
            files.filter(Files::isRegularFile).forEach(path ->
            {
                String relative = BASE_PATH + "/" + base.relativize(path).toString().replace('\\', '/');

                collect(relative, out);
            });
        }
    }

    private static void scanJar(URL root, Set<String> out) throws IOException
    {
        JarURLConnection connection = (JarURLConnection) root.openConnection();

        connection.setUseCaches(false);

        try (JarFile jar = connection.getJarFile())
        {
            Enumeration<JarEntry> entries = jar.entries();

            while (entries.hasMoreElements())
            {
                collect(entries.nextElement().getName(), out);
            }
        }
    }

    private static void collect(String resourcePath, Set<String> out)
    {
        Matcher m = IMPORT_TYPE_CLASS.matcher(resourcePath);

        if (m.matches())
        {
            out.add(BASE_PACKAGE + "." + m.group(1).replace('/', '.') + m.group(2));
        }
    }

    private static ImportType instantiate(ClassLoader loader, String className)
    {
        try
        {
            Class<?> candidate = Class.forName(className, true, loader);

            if (!ImportType.class.isAssignableFrom(candidate) || candidate.isInterface()
                    || Modifier.isAbstract(candidate.getModifiers()))
            {
                return null;
            }

            return (ImportType) candidate.getDeclaredConstructor().newInstance();
        }
        catch (ReflectiveOperationException e)
        {
            throw new IllegalStateException("Cannot instantiate import type " + className
                    + " (it needs a public no-arg constructor)", e);
        }
    }
}
