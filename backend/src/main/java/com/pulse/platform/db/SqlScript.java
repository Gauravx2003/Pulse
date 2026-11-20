package com.pulse.platform.db;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One migration ({@code V<n>__<description>.sql}) or seed ({@code S<n>__<description>.sql}) file.
 * Infrastructure value, not a domain DTO: domain data still travels as JsonObject/JsonArray.
 *
 * @param kind        migration or seed
 * @param version     number inside the owning track's range (V100–V199 Track 1, V200–V299 Track 2,
 *                    V300–V399 Track 3)
 * @param description text after {@code __}
 * @param source      owner of the file: {@code platform} or a module id
 * @param resource    classpath path of the file, used in messages and the history table
 * @param sql         file content
 * @param checksum    SHA-256 of the content; an applied script must never change
 */
public record SqlScript(Kind kind, int version, String description, String source, String resource,
                        String sql, String checksum) {

    public enum Kind {
        MIGRATION("V", "migrations"),
        SEED("S", "seed");

        private final String prefix;
        private final String label;

        Kind(String prefix, String label) {
            this.prefix = prefix;
            this.label = label;
        }

        public String prefix() {
            return prefix;
        }

        public String label() {
            return label;
        }
    }

    private static final Pattern FILE_NAME = Pattern.compile("^([VS])(\\d+)__([A-Za-z0-9_]+)\\.sql$");

    /** Parses a file name; throws when it does not follow the convention for {@code kind}. */
    public static SqlScript parse(Kind kind, String source, String resource, String sql) {
        String fileName = resource.substring(resource.lastIndexOf('/') + 1);
        Matcher m = FILE_NAME.matcher(fileName);
        if (!m.matches() || !m.group(1).equals(kind.prefix())) {
            throw new IllegalStateException("Bad " + kind.label() + " file name '" + resource + "': expected "
                    + kind.prefix() + "<number>__<description>.sql, e.g. " + kind.prefix() + "200__work_items.sql");
        }
        int version = Integer.parseInt(m.group(2));
        return new SqlScript(kind, version, m.group(3), source, resource, sql, sha256(sql));
    }

    /** Short label such as {@code V205}. */
    public String label() {
        return kind.prefix() + version;
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
