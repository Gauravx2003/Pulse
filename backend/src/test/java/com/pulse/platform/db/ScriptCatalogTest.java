package com.pulse.platform.db;

import com.pulse.platform.db.ScriptCatalog.Source;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptCatalogTest {

    static final Source PLATFORM = fixture("platform", 100, 199);
    static final Source WORK = fixture("work", 100, 399);
    static final Source PEOPLE = fixture("people", 100, 399);

    static Source fixture(String name, int min, int max) {
        return new Source(name, "dbfixture/" + name + "/migrations", "dbfixture/" + name + "/seed", min, max);
    }

    static ScriptCatalog load(Source... sources) {
        return ScriptCatalog.load(ScriptCatalogTest.class.getClassLoader(), List.of(sources));
    }

    @Test
    void allRangesSortByVersionWhateverTheDiscoveryOrder() {
        ScriptCatalog catalog = load(PEOPLE, WORK, PLATFORM);
        assertEquals(List.of(100, 150, 200, 210, 300),
                catalog.migrations().stream().map(SqlScript::version).toList());
        assertEquals(List.of(100, 205, 300),
                catalog.seeds().stream().map(SqlScript::version).toList());
        assertEquals("work", catalog.migrations().get(2).source());
    }

    @Test
    void nonSqlFilesInSeedDirectoriesAreIgnored() {
        ScriptCatalog catalog = load(PLATFORM);
        assertTrue(catalog.seeds().stream().allMatch(s -> s.resource().endsWith(".sql")));
        assertEquals(1, catalog.seeds().size());
    }

    @Test
    void duplicateVersionAcrossModulesIsRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> load(WORK, fixture("dup", 100, 399)));
        assertTrue(e.getMessage().contains("Duplicate migrations version V200"), e.getMessage());
    }

    @Test
    void versionOutsideTheSourceRangeIsRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> load(fixture("work", 100, 199)));
        assertTrue(e.getMessage().contains("outside the range"), e.getMessage());
    }

    @Test
    void badFileNameIsRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> load(fixture("badname", 100, 399)));
        assertTrue(e.getMessage().contains("Bad migrations file name"), e.getMessage());
    }

    @Test
    void seedPrefixIsNotAcceptedAsMigration() {
        assertThrows(IllegalStateException.class,
                () -> SqlScript.parse(SqlScript.Kind.MIGRATION, "x", "a/S100__x.sql", "SELECT 1"));
    }

    @Test
    void missingDirectoriesYieldNoScripts() {
        ScriptCatalog catalog = load(new Source("none", "dbfixture/none/migrations", "dbfixture/none/seed", 100, 399));
        assertTrue(catalog.migrations().isEmpty());
        assertTrue(catalog.seeds().isEmpty());
    }
}
