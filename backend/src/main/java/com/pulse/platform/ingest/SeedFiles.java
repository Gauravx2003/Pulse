package com.pulse.platform.ingest;

import io.vertx.core.json.JsonArray;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Read access to the extracted seed data, packaged on the classpath under {@value #DIR} from the
 * repository's {@code seed/extracted/} (see {@code seed/README.md}). Files are parsed once and cached.
 * Reading is blocking I/O: use it only from {@link SeedMapper#map}, which runs on a worker thread.
 */
public final class SeedFiles
{
    public static final String DIR = "seed/extracted";

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");

    private final ClassLoader loader;

    private final Map<String, JsonArray> cache = new ConcurrentHashMap<>();

    public SeedFiles(ClassLoader loader)
    {
        this.loader = loader;
    }

    /** The rows of {@code seed/extracted/<name>.json}; fails when the file is missing. */
    public JsonArray array(String name)
    {
        if (!NAME.matcher(name).matches())
        {
            throw new IllegalArgumentException("Bad seed file name '" + name + "'");
        }

        return cache.computeIfAbsent(name, this::read).copy();
    }

    private JsonArray read(String name)
    {
        String resource = DIR + "/" + name + ".json";

        try (InputStream in = loader.getResourceAsStream(resource))
        {
            if (in == null)
            {
                throw new IllegalStateException("Missing seed file " + resource
                        + " (run `node seed/extract.mjs`; the build copies seed/extracted onto the classpath)");
            }

            return new JsonArray(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
    }
}
