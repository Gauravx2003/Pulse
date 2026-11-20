package com.pulse.platform.db;

import io.vertx.core.MultiMap;
import io.vertx.sqlclient.Tuple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a paged, filtered and sorted list query from a fixed base SELECT and the request's query
 * parameters (task 1.6). Only allow-listed filter and sort names reach the SQL, and only as the
 * column expression the repository declared; every value is a bound parameter.
 *
 * <pre>{@code
 * ListQuery q = ListQuery.from("SELECT p.id, p.name, p.level FROM people p")
 *         .filter("level", "p.level")              // ?level=L3   or ?level=L3,L4
 *         .filter("department", "p.department")
 *         .search("p.name")                         // ?q=pri  (ILIKE on the listed columns)
 *         .sort("name", "p.name").sort("joined", "p.joined_on")
 *         .defaultSort("name")                      // ?sort=-joined for descending
 *         .tieBreaker("p.id")
 *         .where("p.user_id IS NOT NULL")
 *         .where("p.id = ANY(?)", visibleIds);      // the policy scope of task 3.5 plugs in here
 * repository.page(q, ctx.queryParams());           // {items, page, pageSize, total, pageSizeCapped}
 * }</pre>
 *
 * <p>Reserved parameters: {@code page} (1-based), {@code pageSize}, {@code sort}, {@code q}. Any
 * other parameter must be a declared filter, otherwise {@link QueryValidationException} (400).
 * Extra conditions use {@code ?} placeholders, renumbered to {@code $n}; write the jsonb
 * {@code ?} operator as {@code jsonb_exists(...)} inside them.
 */
public final class ListQuery {

    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_PAGE_SIZE = 200;
    public static final Set<String> RESERVED = Set.of("page", "pageSize", "sort", "q");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\?");

    private final String baseSelect;
    private final Map<String, String> filters = new LinkedHashMap<>();
    private final Map<String, String> sorts = new LinkedHashMap<>();
    private final List<String> searchColumns = new ArrayList<>();
    private final List<String> conditions = new ArrayList<>();
    private final List<Object> conditionValues = new ArrayList<>();
    private String defaultSort;
    private String tieBreaker = "id";
    private int defaultPageSize = DEFAULT_PAGE_SIZE;
    private int maxPageSize = MAX_PAGE_SIZE;

    private ListQuery(String baseSelect) {
        this.baseSelect = Objects.requireNonNull(baseSelect, "baseSelect").strip();
    }

    /** {@code baseSelect} is a SELECT ... FROM ... [JOIN ...] without WHERE, ORDER BY or LIMIT. */
    public static ListQuery from(String baseSelect) {
        return new ListQuery(baseSelect);
    }

    /** Allows {@code ?param=value} (or a comma-separated list) as an equality filter on {@code column}. */
    public ListQuery filter(String param, String column) {
        if (RESERVED.contains(param)) {
            throw new IllegalArgumentException("'" + param + "' is a reserved parameter");
        }
        filters.put(param, column);
        return this;
    }

    /** Columns matched case-insensitively by {@code ?q=}. */
    public ListQuery search(String... columns) {
        searchColumns.addAll(List.of(columns));
        return this;
    }

    /** Allows {@code ?sort=name} / {@code ?sort=-name} on {@code column}. */
    public ListQuery sort(String name, String column) {
        sorts.put(name, column);
        return this;
    }

    public ListQuery defaultSort(String sort) {
        this.defaultSort = sort;
        return this;
    }

    /** Unique column appended to every ORDER BY so pages are stable; default {@code id}. */
    public ListQuery tieBreaker(String column) {
        this.tieBreaker = column;
        return this;
    }

    /** A fixed condition from the repository or policy service; {@code ?} placeholders, values bound. */
    public ListQuery where(String condition, Object... values) {
        long placeholders = PLACEHOLDER.matcher(condition).results().count();
        if (placeholders != values.length) {
            throw new IllegalArgumentException("Condition '" + condition + "' has " + placeholders
                    + " placeholder(s) but " + values.length + " value(s)");
        }
        conditions.add(condition);
        conditionValues.addAll(List.of(values));
        return this;
    }

    public ListQuery pageSizes(int defaultSize, int maxSize) {
        if (defaultSize < 1 || maxSize < defaultSize) {
            throw new IllegalArgumentException("need 1 <= default <= max");
        }
        this.defaultPageSize = defaultSize;
        this.maxPageSize = maxSize;
        return this;
    }

    /** Validates {@code params} and produces the page and count statements. */
    public Built build(MultiMap params) {
        for (String name : params.names()) {
            if (!RESERVED.contains(name) && !filters.containsKey(name)) {
                throw new QueryValidationException(name, "Unknown filter '" + name + "'. Allowed: "
                        + String.join(", ", filters.keySet()));
            }
        }
        List<String> where = new ArrayList<>();
        List<Object> values = new ArrayList<>();

        for (int i = 0; i < conditions.size(); i++) {
            // values are appended in order; renumbering happens below
            where.add("(" + conditions.get(i) + ")");
        }
        values.addAll(conditionValues);

        filters.forEach((param, column) -> {
            List<String> raw = params.getAll(param);
            if (raw.isEmpty()) {
                return;
            }
            List<String> wanted = raw.stream()
                    .flatMap(v -> List.of(v.split(",")).stream())
                    .map(String::strip)
                    .filter(v -> !v.isEmpty())
                    .toList();
            if (wanted.isEmpty()) {
                throw new QueryValidationException(param, "Filter '" + param + "' needs a value");
            }
            if (wanted.size() == 1) {
                where.add(column + "::text = ?");
                values.add(wanted.get(0));
            } else {
                where.add(column + "::text = ANY(?)");
                values.add(wanted.toArray(String[]::new));
            }
        });

        String q = params.get("q");
        if (q != null && !q.isBlank()) {
            if (searchColumns.isEmpty()) {
                throw new QueryValidationException("q", "This list does not support search");
            }
            String like = "%" + q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            List<String> ors = new ArrayList<>();
            for (String col : searchColumns) {
                ors.add(col + "::text ILIKE ?");
                values.add(like);
            }
            where.add("(" + String.join(" OR ", ors) + ")");
        }

        String whereSql = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        String orderSql = " ORDER BY " + orderBy(params.get("sort"));

        int page = intParam(params, "page", 1, 1);
        int requested = intParam(params, "pageSize", defaultPageSize, 1);
        int pageSize = Math.min(requested, maxPageSize);
        boolean capped = requested > maxPageSize;

        String pageSql = numbered(baseSelect + whereSql + orderSql + " LIMIT ? OFFSET ?");
        String countSql = numbered("SELECT count(*) AS total FROM (" + baseSelect + whereSql + ") AS counted");

        List<Object> pageValues = new ArrayList<>(values);
        pageValues.add(pageSize);
        pageValues.add((long) (page - 1) * pageSize);
        return new Built(pageSql, Tuple.from(pageValues), countSql, Tuple.from(values), page, pageSize, capped);
    }

    private String orderBy(String sortParam) {
        String sort = sortParam == null || sortParam.isBlank() ? defaultSort : sortParam.strip();
        if (sort == null) {
            return tieBreaker;
        }
        boolean desc = sort.startsWith("-");
        String name = desc ? sort.substring(1) : sort;
        String column = sorts.get(name);
        if (column == null) {
            throw new QueryValidationException("sort", "Unknown sort '" + name + "'. Allowed: "
                    + String.join(", ", sorts.keySet()));
        }
        return column + (desc ? " DESC" : " ASC") + ", " + tieBreaker;
    }

    private static int intParam(MultiMap params, String name, int fallback, int min) {
        String raw = params.get(name);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int v = Integer.parseInt(raw.strip());
            if (v < min) {
                throw new QueryValidationException(name, "'" + name + "' must be at least " + min);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new QueryValidationException(name, "'" + name + "' must be a whole number");
        }
    }

    /** Replaces {@code ?} with {@code $1, $2 ...} in order. */
    static String numbered(String sql) {
        Matcher m = PLACEHOLDER.matcher(sql);
        StringBuilder out = new StringBuilder();
        int n = 0;
        while (m.find()) {
            m.appendReplacement(out, "\\$" + (++n));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Statements ready to run. */
    public record Built(String pageSql, Tuple pageParams, String countSql, Tuple countParams,
                        int page, int pageSize, boolean pageSizeCapped) {
    }
}
