package com.pulse.platform.db;

import com.pulse.platform.db.SqlScript.Kind;
import com.pulse.platform.module.PulseModule;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * All migration and seed scripts of the platform and every self-registered module, validated and
 * sorted by version (design.md decision 10).
 *
 * <ul>
 *   <li>Platform scripts live in {@code src/main/resources/db/migrations} and {@code db/seed}
 *       (Track 1, range 100–199).</li>
 *   <li>Module scripts live in {@code backend/modules/<id>/migrations} and {@code seed}
 *       (any track, range 100–399; each track stays inside its own hundred).</li>
 *   <li>Only {@code .sql} files are scripts; other files in a seed directory (for example JSON read
 *       by the seed connector) are ignored.</li>
 *   <li>A version number may appear only once per kind across all sources.</li>
 * </ul>
 *
 * <p>Loading reads the classpath: call {@link #load} inside {@code executeBlocking}.
 */
public final class ScriptCatalog {

    public static final int MIN_VERSION = 100;
    public static final int MAX_VERSION = 399;

    /** A place scripts come from, with the version range it may use. */
    public record Source(String name, String migrationsDir, String seedDir, int minVersion, int maxVersion) {
    }

    public static final Source PLATFORM = new Source("platform", "db/migrations", "db/seed", 100, 199);

    private final List<SqlScript> migrations;
    private final List<SqlScript> seeds;

    private ScriptCatalog(List<SqlScript> migrations, List<SqlScript> seeds) {
        this.migrations = migrations;
        this.seeds = seeds;
    }

    /** Platform source plus one source per module. */
    public static List<Source> sourcesFor(Collection<PulseModule> modules) {
        List<Source> sources = new ArrayList<>();
        sources.add(PLATFORM);
        for (PulseModule m : modules) {
            sources.add(new Source(m.id(), m.migrationsDir(), m.seedDir(), MIN_VERSION, MAX_VERSION));
        }
        return sources;
    }

    public static ScriptCatalog load(ClassLoader loader, List<Source> sources) {
        List<SqlScript> all = new ArrayList<>();
        for (Source source : sources) {
            all.addAll(read(loader, source, Kind.MIGRATION, source.migrationsDir()));
            all.addAll(read(loader, source, Kind.SEED, source.seedDir()));
        }
        return of(all);
    }

    /** Validates uniqueness and sorts; exposed for tests. */
    public static ScriptCatalog of(List<SqlScript> scripts) {
        return new ScriptCatalog(sorted(scripts, Kind.MIGRATION), sorted(scripts, Kind.SEED));
    }

    public List<SqlScript> migrations() {
        return migrations;
    }

    public List<SqlScript> seeds() {
        return seeds;
    }

    public List<SqlScript> scripts(Kind kind) {
        return kind == Kind.MIGRATION ? migrations : seeds;
    }

    private static List<SqlScript> sorted(List<SqlScript> scripts, Kind kind) {
        Map<Integer, SqlScript> byVersion = new HashMap<>();
        for (SqlScript s : scripts) {
            if (s.kind() != kind) {
                continue;
            }
            SqlScript previous = byVersion.putIfAbsent(s.version(), s);
            if (previous != null) {
                throw new IllegalStateException("Duplicate " + kind.label() + " version " + s.label() + ": "
                        + previous.resource() + " and " + s.resource());
            }
        }
        return byVersion.values().stream().sorted(Comparator.comparingInt(SqlScript::version)).toList();
    }

    private static List<SqlScript> read(ClassLoader loader, Source source, Kind kind, String dir) {
        List<SqlScript> out = new ArrayList<>();
        if (dir == null) {
            return out;
        }
        for (String resource : listSqlFiles(loader, dir)) {
            SqlScript script = SqlScript.parse(kind, source.name(), resource, readResource(loader, resource));
            if (script.version() < source.minVersion() || script.version() > source.maxVersion()) {
                throw new IllegalStateException(script.label() + " in " + resource + " is outside the range "
                        + kind.prefix() + source.minVersion() + "–" + kind.prefix() + source.maxVersion()
                        + " allowed for '" + source.name() + "' (Track 1: 100–199, Track 2: 200–299, Track 3: 300–399)");
            }
            out.add(script);
        }
        return out;
    }

    /** {@code .sql} files directly inside {@code dir}, merged over every classpath root. */
    static List<String> listSqlFiles(ClassLoader loader, String dir) {
        String prefix = dir.endsWith("/") ? dir : dir + "/";
        TreeSet<String> names = new TreeSet<>();
        try {
            Enumeration<URL> roots = loader.getResources(prefix.substring(0, prefix.length() - 1));
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                switch (root.getProtocol()) {
                    case "file" -> {
                        Path base = Paths.get(root.toURI());
                        try (Stream<Path> files = Files.list(base)) {
                            files.filter(Files::isRegularFile)
                                    .map(p -> prefix + p.getFileName())
                                    .forEach(names::add);
                        }
                    }
                    case "jar" -> {
                        JarURLConnection conn = (JarURLConnection) root.openConnection();
                        conn.setUseCaches(false);
                        try (JarFile jar = conn.getJarFile()) {
                            Enumeration<JarEntry> entries = jar.entries();
                            while (entries.hasMoreElements()) {
                                String name = entries.nextElement().getName();
                                if (name.startsWith(prefix) && name.indexOf('/', prefix.length()) < 0
                                        && name.length() > prefix.length()) {
                                    names.add(name);
                                }
                            }
                        }
                    }
                    default -> throw new IllegalStateException("Unsupported classpath root " + root);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list " + dir, e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        return names.stream().filter(n -> n.endsWith(".sql")).toList();
    }

    private static String readResource(ClassLoader loader, String resource) {
        try (InputStream in = loader.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
    }
}
