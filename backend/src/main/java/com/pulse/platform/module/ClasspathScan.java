package com.pulse.platform.module;

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
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds self-registering classes by classpath scan: every class file under {@code basePath}, one
 * folder deep, whose path matches a pattern. Used for feature modules ({@link ModuleRegistry}) and
 * formula modules ({@code com.pulse.calc.core.FormulaRegistry}). Works for exploded class directories
 * (IDE, tests; every root, so test classes are found too) and the fat jar (Docker).
 *
 * <p>Scanning does blocking I/O: call it via {@code executeBlocking} on a running server.
 */
public final class ClasspathScan
{
    private static final Logger log = LoggerFactory.getLogger(ClasspathScan.class);

    private ClasspathScan()
    {
    }

    /**
     * Class names under {@code basePackage} whose resource path matches {@code pattern}. The pattern's
     * group 1 is the sub-package and group 2 the simple class name, as in
     * {@code ^com/pulse/modules/([a-z0-9_]+)/([A-Za-z0-9_]*Module)\.class$}.
     *
     * @return fully qualified names, sorted
     */
    public static Set<String> classNames(ClassLoader loader, String basePackage, Pattern pattern)
    {
        String basePath = basePackage.replace('.', '/');

        Set<String> out = new TreeSet<>();

        try
        {
            Enumeration<URL> roots = loader.getResources(basePath);

            while (roots.hasMoreElements())
            {
                URL root = roots.nextElement();

                switch (root.getProtocol())
                {
                    case "file" -> scanDirectory(root, basePath, basePackage, pattern, out);
                    case "jar" -> scanJar(root, basePackage, pattern, out);
                    default -> log.warn("Skipping unsupported classpath root {}", root);
                }
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("Classpath scan of " + basePackage + " failed", e);
        }

        return out;
    }

    private static void scanDirectory(URL root, String basePath, String basePackage, Pattern pattern, Set<String> out)
            throws IOException
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

        try (Stream<Path> files = Files.walk(base, 2))
        {
            files.filter(Files::isRegularFile).forEach(p ->
                    collect(basePath + "/" + base.relativize(p).toString().replace('\\', '/'), basePackage, pattern, out));
        }
    }

    private static void scanJar(URL root, String basePackage, Pattern pattern, Set<String> out) throws IOException
    {
        JarURLConnection conn = (JarURLConnection) root.openConnection();

        conn.setUseCaches(false);

        try (JarFile jar = conn.getJarFile())
        {
            Enumeration<JarEntry> entries = jar.entries();

            while (entries.hasMoreElements())
            {
                collect(entries.nextElement().getName(), basePackage, pattern, out);
            }
        }
    }

    private static void collect(String resourcePath, String basePackage, Pattern pattern, Set<String> out)
    {
        Matcher m = pattern.matcher(resourcePath);

        if (m.matches())
        {
            out.add(basePackage + "." + m.group(1) + "." + m.group(2));
        }
    }
}
