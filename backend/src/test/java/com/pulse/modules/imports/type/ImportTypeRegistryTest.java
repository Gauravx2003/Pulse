package com.pulse.modules.imports.type;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 16.2: modules join the import-type registry without editing a shared file. */
class ImportTypeRegistryTest
{
    @Test
    void discoversImportTypesByClassName()
    {
        ImportTypeRegistry registry = ImportTypeRegistry.discover(getClass().getClassLoader());

        assertTrue(registry.find("test_notes").isPresent());

        assertTrue(registry.find("test_notes").get() instanceof NotesImportType);
    }

    @Test
    void duplicateIdsAreRefused()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ImportTypeRegistry.of(List.of(new NotesImportType(), new NotesImportType())));

        assertTrue(e.getMessage().contains("Duplicate import type id 'test_notes'"));
    }

    @Test
    void incompleteDefinitionsAreRefused()
    {
        assertThrows(IllegalStateException.class, () -> ImportTypeRegistry.of(List.of(new NotesImportType()
        {
            @Override
            public String id()
            {
                return "Bad Id";
            }
        })));

        assertThrows(IllegalStateException.class, () -> ImportTypeRegistry.of(List.of(new NotesImportType()
        {
            @Override
            public JsonArray modes()
            {
                return new JsonArray();
            }
        })));

        assertThrows(IllegalStateException.class, () -> ImportTypeRegistry.of(List.of(new NotesImportType()
        {
            @Override
            public JsonArray modes()
            {
                return JsonArray.of(new JsonObject().put("key", "x"));
            }
        })));

        assertThrows(IllegalStateException.class, () -> ImportTypeRegistry.of(List.of(new NotesImportType()
        {
            @Override
            public List<String> targetTables()
            {
                return List.of();
            }
        })));
    }

    @Test
    void permissionsAndModesAreResolved()
    {
        ImportTypeRegistry registry = ImportTypeRegistry.of(List.of(new NotesImportType()));

        ImportType notes = registry.find("test_notes").orElseThrow();

        assertEquals(1, registry.allowedFor(List.of("test.notes.import", "other")).size());

        assertTrue(registry.allowedFor(List.of("other")).isEmpty());

        assertEquals("add_update", ImportTypeRegistry.defaultMode(notes));

        assertTrue(ImportTypeRegistry.hasMode(notes, "add_only"));

        assertFalse(ImportTypeRegistry.hasMode(notes, "replace_all"));

        assertTrue(registry.find("missing").isEmpty());
    }
}
