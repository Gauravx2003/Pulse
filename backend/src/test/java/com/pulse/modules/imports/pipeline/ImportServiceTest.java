package com.pulse.modules.imports.pipeline;

import com.pulse.modules.imports.type.ImportTypeRegistry;
import com.pulse.modules.imports.type.NotesImportType;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static com.pulse.modules.imports.pipeline.ImportsTestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spec platform/csv-import-framework, every pipeline scenario (task 16.2), on real PostgreSQL as the app role. */
class ImportServiceTest
{
    private ImportsTestDb db;

    private ImportService service;

    private JsonObject importer;

    private JsonObject otherUser;

    private JsonObject admin;

    @BeforeEach
    void setUp() throws Exception
    {
        db = ImportsTestDb.open();

        service = new ImportService(db.vertx, new SqlExecutor(db.app),
                ImportTypeRegistry.of(List.of(new NotesImportType())));

        importer = actor(db.newUser("hr@pulse.test"), NotesImportType.PERMISSION);

        otherUser = actor(db.newUser("rahul@pulse.test"), NotesImportType.PERMISSION);

        admin = actor(db.newUser("admin@pulse.test"), NotesImportType.PERMISSION, ImportService.ADMIN_PERMISSION);
    }

    @AfterEach
    void tearDown() throws Exception
    {
        if (db != null)
        {
            db.close();
        }
    }

    private static JsonObject actor(String userId, String... permissions)
    {
        return new JsonObject().put("id", userId).put("role", "Admin").put("permissions", JsonArray.of((Object[]) permissions));
    }

    private JsonObject check(String csv, String mode, JsonObject who) throws Exception
    {
        return await(service.check("test_notes", mode, "notes.csv", csv.getBytes(StandardCharsets.UTF_8), who));
    }

    private JsonObject commit(JsonObject checked, JsonObject who) throws Exception
    {
        return await(service.commit("test_notes", checked.getString("checkId"), who));
    }

    private static ApiException failure(Future<?> future)
    {
        ExecutionException e = assertThrows(ExecutionException.class, () -> await(future));

        return assertInstanceOf(ApiException.class, e.getCause());
    }

    private String dueOf(String title) throws Exception
    {
        var rows = await(db.owner.query("SELECT due::text FROM test_notes WHERE title = '" + title + "'").execute());

        return rows.size() == 0 ? "<missing>" : rows.iterator().next().getString(0);
    }

    // ------------------------------------------------------------------ Three-step import

    @Test
    void checkDoesNotWriteAndCancelRemovesTheStagedFile() throws Exception
    {
        JsonObject checked = check("title,due\nA,2026-09-01\nB,\n", null, importer);

        assertEquals(2, checked.getInteger("ready"));

        assertEquals(0, db.count("SELECT count(*) FROM test_notes"));

        assertEquals(1, db.count("SELECT count(*) FROM import_checks"));

        await(service.cancel(checked.getString("checkId"), importer));

        assertEquals(0, db.count("SELECT count(*) FROM import_checks"));

        assertEquals(0, db.count("SELECT count(*) FROM test_notes"));
    }

    @Test
    void rowsWithErrorsAreReportedByLineAndOnlyReadyRowsImport() throws Exception
    {
        StringBuilder csv = new StringBuilder("title,due\n");

        for (int i = 1; i <= 100; i++)
        {
            String due = (i == 5 || i == 40 || i == 77) ? "31-02-2026" : "2026-09-01";

            csv.append("Note ").append(i).append(',').append(due).append('\n');
        }

        JsonObject checked = check(csv.toString(), null, importer);

        assertEquals(100, checked.getInteger("rowsRead"));

        assertEquals(97, checked.getInteger("ready"));

        assertEquals(3, checked.getInteger("errors"));

        JsonObject firstError = checked.getJsonArray("errorList").getJsonObject(0);

        assertEquals(6, firstError.getInteger("line"));

        assertEquals("due", firstError.getString("column"));

        assertEquals(12, checked.getJsonArray("preview").size());

        JsonObject done = commit(checked, importer);

        assertEquals(97, done.getInteger("imported"));

        assertEquals(97, db.count("SELECT count(*) FROM test_notes"));

        assertEquals(0, db.count("SELECT count(*) FROM import_checks"));

        assertEquals(97, db.count("SELECT count(*) FROM import_before_images"));
    }

    @Test
    void fileLevelProblemsAndRequestErrorsHaveTheirStatus() throws Exception
    {
        ApiException missing = failure(service.check("test_notes", null, "x.csv", "due\n2026-09-01\n".getBytes(StandardCharsets.UTF_8), importer));

        assertEquals(422, missing.status());

        assertTrue(missing.getMessage().startsWith("Missing column: title."));

        assertEquals(404, failure(service.check("nope", null, "x.csv", new byte[]{'a'}, importer)).status());

        assertEquals(403, failure(service.check("test_notes", null, "x.csv", new byte[]{'a'}, actor(importer.getString("id")))).status());

        assertEquals(400, failure(service.check("test_notes", "explode", "x.csv", new byte[]{'a'}, importer)).status());
    }

    // ------------------------------------------------------------------ Check id binding and expiry

    @Test
    void anotherUsersCheckIdLooksLikeAMissingOne() throws Exception
    {
        JsonObject checked = check("title\nA\n", null, importer);

        ApiException stolen = failure(service.commit("test_notes", checked.getString("checkId"), otherUser));

        ApiException unknown = failure(service.commit("test_notes", "00000000-0000-0000-0000-000000000000", otherUser));

        assertEquals(404, stolen.status());

        assertEquals(unknown.code(), stolen.code());

        assertEquals(unknown.getMessage(), stolen.getMessage());

        assertEquals(0, db.count("SELECT count(*) FROM test_notes"));
    }

    @Test
    void doubleSubmitImportsOnceAndReturnsTheFirstResult() throws Exception
    {
        JsonObject checked = check("title\nA\nB\n", null, importer);

        JsonObject first = commit(checked, importer);

        JsonObject second = commit(checked, importer);

        assertEquals(first, second);

        assertEquals(2, db.count("SELECT count(*) FROM test_notes"));

        assertEquals(1, db.count("SELECT count(*) FROM import_log"));

        assertEquals(1, db.count("SELECT count(*) FROM audit_log WHERE action = 'import.committed'"));
    }

    @Test
    void simultaneousDoubleSubmitStillImportsOnce() throws Exception
    {
        JsonObject checked = check("title\nA\nB\nC\n", null, importer);

        Future<JsonObject> one = service.commit("test_notes", checked.getString("checkId"), importer);

        Future<JsonObject> two = service.commit("test_notes", checked.getString("checkId"), importer);

        await(Future.all(one, two));

        assertEquals(one.result().getString("importId"), two.result().getString("importId"));

        assertEquals(3, db.count("SELECT count(*) FROM test_notes"));
    }

    @Test
    void expiredCheckIsRefusedAndCleanedUp() throws Exception
    {
        JsonObject checked = check("title\nA\n", null, importer);

        db.run("UPDATE import_checks SET created_at = now() - interval '31 minutes', expires_at = now() - interval '1 minute'");

        assertEquals(404, failure(service.commit("test_notes", checked.getString("checkId"), importer)).status());

        check("title\nB\n", null, importer);

        assertEquals(1, db.count("SELECT count(*) FROM import_checks"));
    }

    // ------------------------------------------------------------------ Undo

    @Test
    void undoWithinTheWindowRestoresThePriorState() throws Exception
    {
        commit(check("title,due\nKeep,2026-09-01\nChange,2026-09-01\n", null, importer), importer);

        JsonObject second = commit(check("title,due\nChange,2026-10-15\nFresh,2026-11-01\n", null, importer), importer);

        assertEquals("2026-10-15", dueOf("Change"));

        JsonObject undone = await(service.undo(second.getString("importId"), importer));

        assertEquals("undone", undone.getString("undoState"));

        assertEquals(2, undone.getInteger("restored"));

        assertEquals("2026-09-01", dueOf("Change"));

        assertEquals("<missing>", dueOf("Fresh"));

        assertEquals(2, db.count("SELECT count(*) FROM test_notes"));

        assertEquals(1, db.count("SELECT count(*) FROM audit_log WHERE action = 'import.undone' AND outcome = 'success'"));

        ApiException again = failure(service.undo(second.getString("importId"), importer));

        assertEquals("already_undone", again.code());
    }

    @Test
    void undoOfReplaceAllBringsDeletedRowsBack() throws Exception
    {
        commit(check("title,due\nOld,2026-09-01\nStay,2026-09-02\n", null, importer), importer);

        JsonObject replace = commit(check("title,due\nStay,2026-09-02\nNew,2026-09-03\n", "replace_all", importer), importer);

        assertEquals("<missing>", dueOf("Old"));

        await(service.undo(replace.getString("importId"), importer));

        assertEquals("2026-09-01", dueOf("Old"));

        assertEquals("<missing>", dueOf("New"));
    }

    @Test
    void undoIsRefusedWhenARecordChangedLater() throws Exception
    {
        JsonObject done = commit(check("title\nA\nB\n", null, importer), importer);

        db.run("UPDATE test_notes SET due = '2026-12-01', version = version + 1 WHERE title = 'B'");

        ApiException refused = failure(service.undo(done.getString("importId"), importer));

        assertEquals(409, refused.status());

        assertEquals("undo_conflict", refused.code());

        assertTrue(refused.getMessage().startsWith("Undo refused: 1 record was changed"), refused.getMessage());

        assertTrue(refused.getMessage().contains("test_notes "), refused.getMessage());

        assertEquals(2, db.count("SELECT count(*) FROM test_notes"));
    }

    @Test
    void undoAfterTwentyFourHoursIsRefused() throws Exception
    {
        JsonObject done = commit(check("title\nA\n", null, importer), importer);

        db.run("UPDATE import_log SET undo_until = now() - interval '1 minute'");

        assertEquals("undo_expired", failure(service.undo(done.getString("importId"), importer)).code());
    }

    @Test
    void onlyTheImporterOrAnAdminCanUndo() throws Exception
    {
        JsonObject done = commit(check("title\nA\n", null, importer), importer);

        assertEquals(404, failure(service.undo(done.getString("importId"), otherUser)).status());

        await(service.undo(done.getString("importId"), admin));

        assertEquals(0, db.count("SELECT count(*) FROM test_notes"));
    }

    // ------------------------------------------------------------------ Concurrency and history

    @Test
    void concurrentCommitsOfOneTypeApplyOneAfterTheOther() throws Exception
    {
        JsonObject a = check("title,due\nShared,2026-09-01\n", null, importer);

        JsonObject b = check("title,due\nOther,2026-09-02\n", null, otherUser);

        Future<JsonObject> first = service.commit("test_notes", a.getString("checkId"), importer);

        Future<JsonObject> second = service.commit("test_notes", b.getString("checkId"), otherUser);

        await(Future.all(first, second));

        assertEquals(1, first.result().getInteger("imported"));

        assertEquals(1, second.result().getInteger("imported"));

        assertEquals(2, db.count("SELECT count(*) FROM import_log"));

        assertEquals(2, db.count("SELECT count(*) FROM test_notes"));
    }

    @Test
    void historyIsNewestFirstAndScopedToTheViewer() throws Exception
    {
        commit(check("title\nA\n", null, importer), importer);

        commit(check("title\nB\n", null, otherUser), otherUser);

        JsonObject mine = await(service.history(MultiMap.caseInsensitiveMultiMap(), importer));

        assertEquals(1, mine.getLong("total"));

        JsonObject all = await(service.history(MultiMap.caseInsensitiveMultiMap(), admin));

        assertEquals(2, all.getLong("total"));

        JsonObject newest = all.getJsonArray("items").getJsonObject(0);

        assertEquals("rahul@pulse.test", newest.getString("user_name"));

        assertEquals("available", newest.getString("undo_state"));

        assertEquals(1, newest.getInteger("imported"));
    }
}
