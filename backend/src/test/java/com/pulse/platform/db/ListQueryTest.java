package com.pulse.platform.db;

import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListQueryTest {

    static ListQuery people() {
        return ListQuery.from("SELECT p.id, p.name FROM people p")
                .filter("level", "p.level")
                .search("p.name")
                .sort("name", "p.name")
                .defaultSort("name")
                .tieBreaker("p.id");
    }

    static MultiMap params(Map<String, String> m) {
        MultiMap params = MultiMap.caseInsensitiveMultiMap();
        m.forEach(params::add);
        return params;
    }

    @Test
    void buildsParameterizedPageAndCountStatements() {
        ListQuery.Built b = people().where("p.user_id IS NOT NULL").where("p.id = ANY(?)", (Object) new String[]{"a"})
                .build(params(Map.of("level", "L3,L4", "q", "pri", "sort", "-name", "page", "2", "pageSize", "10")));
        assertEquals("SELECT p.id, p.name FROM people p WHERE (p.user_id IS NOT NULL) AND (p.id = ANY($1)) "
                + "AND p.level::text = ANY($2) AND (p.name::text ILIKE $3) ORDER BY p.name DESC, p.id LIMIT $4 OFFSET $5",
                b.pageSql());
        assertEquals("SELECT count(*) AS total FROM (SELECT p.id, p.name FROM people p WHERE (p.user_id IS NOT NULL) "
                + "AND (p.id = ANY($1)) AND p.level::text = ANY($2) AND (p.name::text ILIKE $3)) AS counted", b.countSql());
        assertArrayEquals(new String[]{"L3", "L4"}, (String[]) b.pageParams().getValue(1));
        assertEquals("%pri%", b.pageParams().getValue(2));
        assertEquals(10, b.pageParams().getValue(3));
        assertEquals(10L, b.pageParams().getValue(4));
        assertEquals(3, b.countParams().size());
    }

    @Test
    void defaultsAndCap() {
        ListQuery.Built plain = people().build(params(Map.of()));
        assertEquals(1, plain.page());
        assertEquals(ListQuery.DEFAULT_PAGE_SIZE, plain.pageSize());
        assertFalse(plain.pageSizeCapped());
        assertTrue(plain.pageSql().endsWith("ORDER BY p.name ASC, p.id LIMIT $1 OFFSET $2"), plain.pageSql());

        ListQuery.Built huge = people().build(params(Map.of("pageSize", "10000")));
        assertEquals(ListQuery.MAX_PAGE_SIZE, huge.pageSize());
        assertTrue(huge.pageSizeCapped());
    }

    @Test
    void unknownFilterIsRejected() {
        QueryValidationException e = assertThrows(QueryValidationException.class,
                () -> people().build(params(Map.of("salary", "1"))));
        assertEquals("salary", e.field());
    }

    @Test
    void sortInjectionIsRejected() {
        QueryValidationException e = assertThrows(QueryValidationException.class,
                () -> people().build(params(Map.of("sort", "name; DROP TABLE people"))));
        assertEquals("sort", e.field());
        assertThrows(QueryValidationException.class,
                () -> people().build(params(Map.of("sort", "(SELECT password_hash FROM users)"))));
    }

    @Test
    void filterValuesNeverReachTheSql() {
        String evil = "L3' OR '1'='1";
        ListQuery.Built b = people().build(params(Map.of("level", evil)));
        assertFalse(b.pageSql().contains("OR '1'"), b.pageSql());
        assertEquals(evil, b.pageParams().getValue(0));
    }

    @Test
    void badPagingValuesAreRejected() {
        assertEquals("page", assertThrows(QueryValidationException.class,
                () -> people().build(params(Map.of("page", "0")))).field());
        assertEquals("pageSize", assertThrows(QueryValidationException.class,
                () -> people().build(params(Map.of("pageSize", "ten")))).field());
    }

    @Test
    void searchWildcardsAreEscaped() {
        ListQuery.Built b = people().build(params(Map.of("q", "50%_off")));
        assertEquals("%50\\%\\_off%", b.pageParams().getValue(0));
    }

    @Test
    void conditionPlaceholderCountMustMatch() {
        assertThrows(IllegalArgumentException.class, () -> people().where("p.id = ?"));
        assertThrows(IllegalArgumentException.class, () -> people().filter("page", "p.page"));
    }
}
