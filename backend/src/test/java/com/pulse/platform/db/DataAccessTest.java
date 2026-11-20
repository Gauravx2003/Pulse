package com.pulse.platform.db;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 1.6: SqlExecutor, BaseRepository and ListQuery against PostgreSQL. */
class DataAccessTest {

    private TestDb db;
    private SqlExecutor sql;
    private ItemRepository repo;

    /** A module-style repository: SQL only here. */
    static final class ItemRepository extends BaseRepository {
        ItemRepository(SqlExecutor sql) {
            super(sql);
        }

        Future<Integer> add(String name, String level) {
            return execute("INSERT INTO items (name, level) VALUES ($1, $2)", Tuple.of(name, level));
        }

        Future<JsonObject> list(MultiMap params, String... onlyLevels) {
            ListQuery q = ListQuery.from("SELECT id, name, level FROM items")
                    .filter("level", "level").search("name")
                    .sort("name", "name").defaultSort("name").tieBreaker("id");
            if (onlyLevels.length > 0) {
                q.where("level = ANY(?)", (Object) onlyLevels);
            }
            return page(q, params);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        db = TestDb.open();
        PgPool pool = db.ownerPool();
        sql = new SqlExecutor(pool);
        repo = new ItemRepository(sql);
        await(sql.execute("""
                CREATE TABLE items (
                    id serial PRIMARY KEY, uid uuid DEFAULT gen_random_uuid(), name text NOT NULL, level text,
                    joined date DEFAULT '2025-01-06', at timestamptz DEFAULT '2026-09-28T10:00:00Z',
                    amount numeric(12,2) DEFAULT 18.50, meta jsonb DEFAULT '{"a":1}', tags text[] DEFAULT '{x,y}',
                    active boolean DEFAULT true, note text)""", Tuple.tuple()));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void executeQueryOneAndQueryList() throws Exception {
        assertEquals(1, await(repo.add("Priya", "L3")));
        assertEquals(1, await(repo.add("Arjun", "L4")));
        assertEquals(2, await(sql.execute("UPDATE items SET note = $1", Tuple.of("x"))));

        JsonObject one = await(sql.queryOne("SELECT name, level FROM items WHERE name = $1", Tuple.of("Priya")));
        assertEquals("Priya", one.getString("name"));
        assertNull(await(sql.queryOne("SELECT name FROM items WHERE name = $1", Tuple.of("nobody"))));

        JsonArray all = await(sql.queryList("SELECT name FROM items ORDER BY name"));
        assertEquals(new JsonArray().add(new JsonObject().put("name", "Arjun")).add(new JsonObject().put("name", "Priya")), all);
    }

    @Test
    void valuesAreConvertedToJsonTypes() throws Exception {
        await(repo.add("Priya", null));
        JsonObject row = await(sql.queryOne("SELECT * FROM items"));
        assertEquals(36, row.getString("uid").length());
        assertEquals("2025-01-06", row.getString("joined"));
        assertTrue(row.getString("at").startsWith("2026-09-28T10:00"), row.getString("at"));
        assertEquals(new BigDecimal("18.50"), row.getValue("amount"));
        assertEquals(1, row.getJsonObject("meta").getInteger("a"));
        assertEquals(new JsonArray().add("x").add("y"), row.getJsonArray("tags"));
        assertEquals(true, row.getBoolean("active"));
        assertTrue(row.containsKey("level") && row.getValue("level") == null);
        assertEquals(row, new JsonObject(row.encode()), "row must survive JSON encoding");
        assertInstanceOf(String.class, row.getValue("joined"));
        assertEquals(LocalDate.parse("2025-01-06"), LocalDate.parse(row.getString("joined")));
    }

    @Test
    void transactionCommitsOnSuccess() throws Exception {
        await(sql.inTransaction(tx -> new ItemRepository(tx).add("A", "L1")
                .compose(n -> new ItemRepository(tx).add("B", "L1"))));
        assertEquals(2L, count());
    }

    @Test
    void transactionRollsBackOnFailure() throws Exception {
        ExecutionException e = assertThrows(ExecutionException.class, () -> await(sql.inTransaction(tx ->
                new ItemRepository(tx).add("A", "L1")
                        .compose(n -> tx.execute("INSERT INTO items (name) VALUES (NULL)", Tuple.tuple())))));
        assertTrue(e.getCause().getMessage().contains("null value"), e.getCause().getMessage());
        assertEquals(0L, count());
    }

    @Test
    void pageFiltersSortsAndCounts() throws Exception {
        for (String[] p : new String[][]{{"Priya", "L3"}, {"Arjun", "L4"}, {"Neha", "L2"}, {"Kiran", "L3"}, {"Dev", "L3"}}) {
            await(repo.add(p[0], p[1]));
        }
        JsonObject page = await(repo.list(params(Map.of("level", "L3", "sort", "-name", "pageSize", "2"))));
        assertEquals(3L, page.getLong("total"));
        assertEquals(2, page.getInteger("pageSize"));
        assertFalse(page.getBoolean("pageSizeCapped"));
        assertEquals("Priya", page.getJsonArray("items").getJsonObject(0).getString("name"));
        assertEquals("Kiran", page.getJsonArray("items").getJsonObject(1).getString("name"));

        JsonObject second = await(repo.list(params(Map.of("level", "L3", "sort", "-name", "pageSize", "2", "page", "2"))));
        assertEquals(1, second.getJsonArray("items").size());
        assertEquals("Dev", second.getJsonArray("items").getJsonObject(0).getString("name"));

        JsonObject search = await(repo.list(params(Map.of("q", "N"))));
        assertEquals(3L, search.getLong("total")); // Neha, Arjun, Kiran: case-insensitive

        JsonObject scoped = await(repo.list(params(Map.of()), "L2", "L4"));
        assertEquals(2L, scoped.getLong("total")); // Neha, Arjun: the policy-scope hook
    }

    @Test
    void capOnPageSizeIsReported() throws Exception {
        await(repo.add("Priya", "L3"));
        JsonObject page = await(repo.list(params(Map.of("pageSize", "10000"))));
        assertEquals(ListQuery.MAX_PAGE_SIZE, page.getInteger("pageSize"));
        assertTrue(page.getBoolean("pageSizeCapped"));
    }

    @Test
    void unknownFilterAndSortInjectionFailWithoutTouchingTheDatabase() throws Exception {
        await(repo.add("Priya", "L3"));
        ExecutionException unknown = assertThrows(ExecutionException.class,
                () -> await(repo.list(params(Map.of("password_hash", "x")))));
        assertInstanceOf(QueryValidationException.class, unknown.getCause());

        ExecutionException injection = assertThrows(ExecutionException.class,
                () -> await(repo.list(params(Map.of("sort", "name; DROP TABLE items")))));
        assertInstanceOf(QueryValidationException.class, injection.getCause());

        JsonObject evil = await(repo.list(params(Map.of("level", "L3' OR '1'='1"))));
        assertEquals(0L, evil.getLong("total"));
        assertEquals(1L, count(), "table must be intact");
    }

    private long count() throws Exception {
        return await(sql.queryOne("SELECT count(*) AS n FROM items")).getLong("n");
    }

    private static MultiMap params(Map<String, String> m) {
        return ListQueryTest.params(m);
    }
}
