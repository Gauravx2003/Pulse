package com.pulse.calc.core;

import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only public entry to the calculation library (task 4.10, spec platform/calculation-engine,
 * design.md decision 5). Screens and endpoints read every number through {@link #get}; no module
 * computes a formula itself.
 *
 * <p><b>Formulas</b> come from the {@link FormulaModule} descriptors of {@code calc/core},
 * {@code calc/work} and {@code calc/people} ({@link FormulaRegistry}).
 *
 * <p><b>Cache.</b> A result is cached by formula, parameters, rule version and today. It is dropped
 * when one of the topics it read changes, its own or those of the formulas it called:
 * {@link #invalidate} locally, or {@link #publish} on the event bus ({@value #INVALIDATE_ADDRESS},
 * body {@code {"topics": [...]}}) after an import commit, a confirmation, an override or a rule change.
 * {@link #RULES} drops everything.
 *
 * <p><b>Closed periods.</b> A periodic formula ({@link Formula#periodKinds}) asked for a closed sprint
 * or quarter answers from that period's snapshot ({@link SnapshotStore}, kind {@code period}, result
 * {@code {"<formula>": {"<person id or _>": value}}}). {@link #closeEndedPeriods} freezes every ended
 * sprint and quarter that has periodic formulas and no snapshot yet, with the rule version in force;
 * a later rule edit does not change it.
 *
 * <p>Results leave {@link #get} guarded ({@link ResultGuard}): never null, NaN, an infinity or −0.
 */
public final class CalcService
{
    private static final Logger log = LoggerFactory.getLogger(CalcService.class);

    /** Event-bus address of invalidation events: {@code {"topics": ["work.issues"]}}. */
    public static final String INVALIDATE_ADDRESS = "calc.invalidate";

    /** Rules &amp; thresholds changed: every cached result is dropped. */
    public static final String RULES = "rules";

    /** Invalidation topic that drops every cached result, e.g. after a connector sync (task 5.4). */
    public static final String ALL = "all";

    /** Sprints or releases changed (the period calendar). */
    public static final String SPRINTS = "sprints";

    /** People, joining dates or reporting lines changed. */
    public static final String PEOPLE = "people";

    /** Seeded pillars, published 360° culture ratings or culture signals changed. */
    public static final String PERFORMANCE = "performance";

    /** A snapshot was stored or replaced. */
    public static final String SNAPSHOTS = "snapshots";

    /** Published by the imports module after a CSV import commits: {@code {type, importId}} (task 16.2). */
    public static final String IMPORT_COMMITTED_ADDRESS = "pulse.imports.committed";

    /** Published by the imports module after an import is undone: {@code {type, importId}}. */
    public static final String IMPORT_UNDONE_ADDRESS = "pulse.imports.undone";

    /** No person: the subject key of a periodic formula without a {@code person} parameter. */
    public static final String NO_SUBJECT = "_";

    private final SqlExecutor sql;

    private final PulseClock clock;

    private final FormulaRegistry registry;

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    private final Map<String, Future<JsonObject>> snapshots = new ConcurrentHashMap<>();

    private volatile Future<JsonObject> rules;

    private volatile Future<PeriodCalendar> calendar;

    private volatile LocalDate calendarDay;

    private final List<MessageConsumer<JsonObject>> consumers = new ArrayList<>();

    private record Entry(Future<Object> value, Set<String> topics)
    {
    }

    public CalcService(SqlExecutor sql, PulseClock clock, FormulaRegistry registry)
    {
        this.sql = sql;
        this.clock = clock;
        this.registry = registry;
    }

    /**
     * Listens for invalidation events on {@value #INVALIDATE_ADDRESS}, and for CSV import commits and
     * undos, which drop the topic {@link #importTopic importTopic(type)}; returns this.
     */
    public CalcService listen(Vertx vertx)
    {
        consumers.add(vertx.eventBus().<JsonObject>consumer(INVALIDATE_ADDRESS, msg ->
        {
            JsonArray topics = msg.body() == null ? null : msg.body().getJsonArray("topics");

            if (topics != null)
            {
                invalidate(topics.stream().map(String::valueOf).toArray(String[]::new));
            }
        }));

        for (String address : List.of(IMPORT_COMMITTED_ADDRESS, IMPORT_UNDONE_ADDRESS))
        {
            consumers.add(vertx.eventBus().<JsonObject>consumer(address, msg ->
            {
                String type = msg.body() == null ? null : msg.body().getString("type");

                if (type != null)
                {
                    invalidate(importTopic(type));
                }
            }));
        }

        return this;
    }

    /** The topic a CSV import of {@code type} changes ({@code "imports.holidays"}); list it in a formula's topics. */
    public static String importTopic(String type)
    {
        return "imports." + type;
    }

    /** Stops listening. */
    public Future<Void> close()
    {
        return Future.all(consumers.stream().map(MessageConsumer::unregister).toList()).mapEmpty();
    }

    /** Publishes an invalidation event, e.g. after an import commit: {@code publish(vertx, "people.attendance")}. */
    public static void publish(Vertx vertx, String... topics)
    {
        vertx.eventBus().publish(INVALIDATE_ADDRESS, new JsonObject().put("topics", new JsonArray(List.of(topics))));
    }

    public FormulaRegistry registry()
    {
        return registry;
    }

    public PulseClock clock()
    {
        return clock;
    }

    // ------------------------------------------------------------------ reads

    /**
     * A formula's guarded result. Fails with {@link IllegalArgumentException} for an unknown formula or
     * period key (the HTTP layer answers 400).
     */
    public Future<Object> get(String formula, JsonObject params)
    {
        return raw(formula, params == null ? new JsonObject() : params, null, null).map(ResultGuard::guard);
    }

    /** {@link #get} for a formula known to resolve to an object. */
    public Future<JsonObject> getObject(String formula, JsonObject params)
    {
        return get(formula, params).map(v -> v instanceof JsonObject o ? o : new JsonObject().put("value", v));
    }

    /** True when a module registers {@code formula}. */
    public boolean has(String formula)
    {
        return registry.formula(formula) != null;
    }

    // ------------------------------------------------------------------ invalidation

    /** Drops every cached result that read one of {@code topics}; {@link #RULES} and {@link #ALL} drop everything. */
    public void invalidate(String... topics)
    {
        Set<String> ts = Set.of(topics);

        if (ts.contains(RULES) || ts.contains(ALL))
        {
            rules = null;
            calendar = null;
            snapshots.clear();
            cache.clear();

            return;
        }

        if (ts.contains(SPRINTS))
        {
            calendar = null;
        }

        if (ts.contains(SNAPSHOTS))
        {
            snapshots.clear();
            cache.keySet().removeIf(k -> k.contains("|live:"));
        }

        cache.entrySet().removeIf(e -> e.getValue().topics().stream().anyMatch(ts::contains));
    }

    // ------------------------------------------------------------------ period close

    /**
     * Freezes every sprint and quarter that ended before today, has periodic formulas and has no
     * snapshot yet: each periodic formula for that kind, per person when it takes one, with the rule
     * version in force. A period another run froze first is skipped.
     *
     * @return the snapshot keys frozen by this call
     */
    public Future<JsonArray> closeEndedPeriods()
    {
        return rules().compose(r -> calendar().compose(cal -> raw("core.people", new JsonObject(), null, null).compose(people ->
        {
            List<JsonObject> ended = endedPeriods(cal);

            JsonArray frozen = new JsonArray();

            Future<Void> chain = Future.succeededFuture();

            for (JsonObject period : ended)
            {
                chain = chain.compose(v -> close(period, (JsonArray) people, r.getInteger("version"))
                        .map(key ->
                        {
                            if (key != null)
                            {
                                frozen.add(key);
                            }

                            return null;
                        }));
            }

            return chain.map(v ->
            {
                if (!frozen.isEmpty())
                {
                    invalidate(SNAPSHOTS);

                    log.info("Froze {} closed period(s): {}", frozen.size(), frozen);
                }

                return frozen;
            });
        })));
    }

    /** The snapshot key of a period: {@code s:S42} for a sprint, {@code q:2026-Q2} for a quarter. */
    public static String snapshotKey(JsonObject period)
    {
        if (PeriodCalendar.QUARTER.equals(period.getString("kind")))
        {
            LocalDate from = LocalDate.parse(period.getString("from"));

            return "q:" + from.getYear() + "-Q" + ((from.getMonthValue() - 1) / 3 + 1);
        }

        return period.getString("key");
    }

    /**
     * Recomputes a closed period with the rules and data in force and replaces its snapshot (spec
     * "Snapshots and rule versioning": an Admin recalculation of a named closed period with a written
     * reason). The old snapshot stays as history and the change is audited ({@code calc.recalculated}).
     * The caller checks that {@code actor} is an Admin.
     *
     * @return the new snapshot row; fails with {@link IllegalArgumentException} for a live period, a
     *         missing reason or a period with no periodic formulas, and {@link IllegalStateException}
     *         when the period was never frozen
     */
    public Future<JsonObject> recalculate(String periodKey, JsonObject actor, String reason)
    {
        return rules().compose(r -> calendar().compose(cal -> raw("core.people", new JsonObject(), null, null).compose(people ->
        {
            JsonObject period = cal.period(periodKey);

            if (period.getBoolean("live"))
            {
                return Future.failedFuture(new IllegalArgumentException("period: " + periodKey + " has not closed yet."));
            }

            String key = snapshotKey(period);

            return values(period, (JsonArray) people).compose(computed ->
            {
                if (computed == null)
                {
                    return Future.failedFuture(new IllegalArgumentException("period: " + periodKey
                            + " has no formulas to recalculate."));
                }

                return SnapshotStore.replace(sql, SnapshotStore.PERIOD, key, r.getInteger("version"),
                        inputs(period, computed, (JsonArray) people), computed, actor, reason);
            }).onSuccess(row -> invalidate(SNAPSHOTS));
        })));
    }

    private Future<String> close(JsonObject period, JsonArray people, int ruleVersion)
    {
        String key = snapshotKey(period);

        if (periodic(period.getString("kind")).isEmpty())
        {
            return Future.succeededFuture(null);
        }

        return snapshot(key).compose(existing ->
        {
            if (existing != null)
            {
                return Future.succeededFuture(null);
            }

            return values(period, people)
                    .compose(result -> SnapshotStore.freeze(sql, SnapshotStore.PERIOD, key, ruleVersion,
                            inputs(period, result, people), result, null))
                    .map(row -> key)
                    .recover(e -> e instanceof IllegalStateException
                            ? Future.succeededFuture(null)
                            : Future.failedFuture(e));
        });
    }

    private List<Formula> periodic(String kind)
    {
        return registry.formulas().stream().filter(f -> f.periodKinds().contains(kind)).toList();
    }

    /** Every periodic formula's live value for a period, per person when it takes one; null with none. */
    private Future<JsonObject> values(JsonObject period, JsonArray people)
    {
        List<Formula> periodic = periodic(period.getString("kind"));

        if (periodic.isEmpty())
        {
            return Future.succeededFuture(null);
        }

        String key = snapshotKey(period);

        JsonObject result = new JsonObject();

        Future<Void> chain = Future.succeededFuture();

        for (Formula f : periodic)
        {
            JsonObject values = new JsonObject();

            result.put(f.name(), values);

            List<String> subjects = new ArrayList<>();

            if (f.perPerson())
            {
                people.forEach(p -> subjects.add(((JsonObject) p).getString("id")));
            }
            else
            {
                subjects.add(NO_SUBJECT);
            }

            for (String subject : subjects)
            {
                JsonObject params = new JsonObject().put("period", key);

                if (!NO_SUBJECT.equals(subject))
                {
                    params.put("person", subject);
                }

                chain = chain.compose(v -> live(f, params).map(value ->
                {
                    values.put(subject, value);

                    return null;
                }));
            }
        }

        return chain.map(v -> result);
    }

    private JsonObject inputs(JsonObject period, JsonObject result, JsonArray people)
    {
        return new JsonObject()
                .put("period", period.copy())
                .put("formulas", new JsonArray(new ArrayList<>(result.fieldNames())))
                .put("people", people.size())
                .put("computedOn", today().toString());
    }

    /** Sprints that ended before today, then the quarters from the first sprint's to the one before today's. */
    private List<JsonObject> endedPeriods(PeriodCalendar cal)
    {
        LocalDate today = today();

        List<JsonObject> out = new ArrayList<>();

        LocalDate first = null;

        for (Object o : cal.sprints())
        {
            JsonObject s = (JsonObject) o;

            LocalDate from = LocalDate.parse(s.getString("from"));

            first = first == null || from.isBefore(first) ? from : first;

            if (LocalDate.parse(s.getString("to")).isBefore(today))
            {
                out.add(s);
            }
        }

        if (first != null)
        {
            LocalDate q = first.withDayOfMonth(1).withMonth((first.getMonthValue() - 1) / 3 * 3 + 1);

            while (q.plusMonths(3).minusDays(1).isBefore(today))
            {
                out.add(cal.period("q:" + q.getYear() + "-Q" + ((q.getMonthValue() - 1) / 3 + 1)));

                q = q.plusMonths(3);
            }
        }

        return out;
    }

    // ------------------------------------------------------------------ evaluation

    LocalDate today()
    {
        return clock.today();
    }

    /**
     * The raw (unguarded) value; {@code parentTopics} collects the topics read, for the caller's entry.
     * {@code liveKey} names a closed period being recomputed: its snapshot is bypassed all the way down,
     * and those results are cached apart.
     */
    private Future<Object> raw(String name, JsonObject params, Set<String> parentTopics, String liveKey)
    {
        Formula f = registry.formula(name);

        if (f == null)
        {
            return Future.failedFuture(new IllegalArgumentException("Unknown formula '" + name + "'"));
        }

        JsonObject p = new JsonObject();

        for (String k : f.params())
        {
            if (params.getValue(k) != null)
            {
                p.put(k, params.getValue(k));
            }
        }

        return canonicalPeriod(p).compose(cp -> rules().compose(r ->
        {
            String key = cacheKey(name, cp, r.getInteger("version")) + (liveKey == null ? "" : "|live:" + liveKey);

            Entry entry = cache.get(key);

            if (entry == null)
            {
                Set<String> topics = ConcurrentHashMap.newKeySet();

                topics.addAll(f.topics());
                topics.add(RULES);

                Promise<Object> promise = Promise.promise();

                Entry fresh = new Entry(promise.future(), topics);

                entry = cache.putIfAbsent(key, fresh);

                if (entry == null)
                {
                    entry = fresh;

                    evaluate(f, cp, r, topics, liveKey).onComplete(ar ->
                    {
                        if (ar.failed())
                        {
                            cache.remove(key, fresh);
                        }

                        promise.handle(ar);
                    });
                }
            }

            Entry used = entry;

            return used.value().onSuccess(v ->
            {
                if (parentTopics != null)
                {
                    parentTopics.addAll(used.topics());
                }
            });
        }));
    }

    /** A closed period of a periodic formula from its snapshot, otherwise the live computation. */
    private Future<Object> evaluate(Formula f, JsonObject params, JsonObject rules, Set<String> topics, String liveKey)
    {
        if (f.periodKinds().isEmpty() || params.getString("period") == null || params.getString("period").equals(liveKey))
        {
            return run(f, params, rules, topics, liveKey);
        }

        topics.add(SPRINTS);

        return calendar().compose(cal ->
        {
            JsonObject period = cal.period(params.getString("period"));

            if (period.getBoolean("live") || !f.periodKinds().contains(period.getString("kind")))
            {
                return run(f, params, rules, topics, liveKey);
            }

            topics.add(SNAPSHOTS);

            String subject = params.getString("person", NO_SUBJECT);

            return snapshot(snapshotKey(period)).compose(row ->
            {
                JsonObject values = row == null ? null : row.getJsonObject("result").getJsonObject(f.name());

                if (values != null && values.containsKey(subject))
                {
                    return Future.succeededFuture(ResultGuard.unguard(values.getValue(subject)));
                }

                return run(f, params, rules, topics, liveKey);
            });
        });
    }

    /** Computes live, bypassing the period's snapshot down the whole chain (to freeze or recalculate it). */
    private Future<Object> live(Formula f, JsonObject params)
    {
        return rules().compose(r ->
        {
            Set<String> topics = ConcurrentHashMap.newKeySet();

            return canonicalPeriod(params).compose(cp -> run(f, cp, r, topics, cp.getString("period")));
        });
    }

    private Future<Object> run(Formula f, JsonObject params, JsonObject rules, Set<String> topics, String liveKey)
    {
        try
        {
            return f.compute().apply(new Context(rules, topics, liveKey), params);
        }
        catch (RuntimeException e)
        {
            return Future.failedFuture(e);
        }
    }

    /** Rewrites a {@code period} parameter to one key per period ({@code q:Q3} and {@code q:2026-Q3} alike). */
    private Future<JsonObject> canonicalPeriod(JsonObject params)
    {
        String period = params.getString("period");

        if (period == null)
        {
            return Future.succeededFuture(params);
        }

        return calendar().compose(cal ->
        {
            try
            {
                return Future.succeededFuture(params.copy().put("period", snapshotKey(cal.period(period))));
            }
            catch (IllegalArgumentException e)
            {
                return Future.failedFuture(e);
            }
        });
    }

    private String cacheKey(String name, JsonObject params, int ruleVersion)
    {
        return name + "|" + new JsonObject(new TreeMap<>(params.getMap())).encode() + "|v" + ruleVersion + "|" + today();
    }

    // ------------------------------------------------------------------ shared inputs

    /** {@code {version, rules}} in force. */
    private Future<JsonObject> rules()
    {
        Future<JsonObject> r = rules;

        if (r == null)
        {
            r = SnapshotStore.currentRules(sql).compose(row -> row == null
                    ? Future.failedFuture(new IllegalStateException("No rule version: the base seed has not run"))
                    : Future.succeededFuture(row));

            rules = r;

            r.onFailure(e -> rules = null);
        }

        return r;
    }

    private Future<PeriodCalendar> calendar()
    {
        LocalDate today = today();

        Future<PeriodCalendar> c = calendar;

        if (c == null || !today.equals(calendarDay))
        {
            // name comes from work migration V296; read through to_jsonb so a schema without the work
            // module (a test that loads only other modules) still builds the calendar, with no names
            c = sql.queryList("SELECT s.key, s.number, to_jsonb(s) ->> 'name' AS name, s.starts_on AS \"startsOn\", "
                            + "s.work_ends_on AS \"workEndsOn\", s.ends_on AS \"endsOn\" FROM sprints s ORDER BY s.starts_on")
                    .compose(sprints -> sql.queryList("SELECT key, name, starts_on AS \"startsOn\", ends_on AS \"endsOn\", "
                                    + "release_date AS \"releaseDate\", status FROM releases ORDER BY starts_on")
                            .map(releases -> new PeriodCalendar(sprints, releases, today)));

            calendar = c;
            calendarDay = today;

            c.onFailure(e -> calendar = null);
        }

        return c;
    }

    private Future<JsonObject> snapshot(String key)
    {
        return snapshots.computeIfAbsent(key, k ->
        {
            Future<JsonObject> f = SnapshotStore.find(sql, SnapshotStore.PERIOD, k);

            f.onFailure(e -> snapshots.remove(k));

            return f;
        });
    }

    /** The context handed to one formula body. */
    private final class Context implements CalcContext
    {
        private final JsonObject rules;

        private final Set<String> topics;

        private final String liveKey;

        Context(JsonObject rules, Set<String> topics, String liveKey)
        {
            this.rules = rules;
            this.topics = topics;
            this.liveKey = liveKey;
        }

        @Override
        public SqlExecutor sql()
        {
            return sql;
        }

        @Override
        public LocalDate today()
        {
            return CalcService.this.today();
        }

        @Override
        public Instant now()
        {
            return clock.now().toInstant();
        }

        @Override
        public JsonObject rules()
        {
            return rules.getJsonObject("rules").copy();
        }

        @Override
        public int ruleVersion()
        {
            return rules.getInteger("version");
        }

        @Override
        public Future<PeriodCalendar> calendar()
        {
            topics.add(SPRINTS);

            return CalcService.this.calendar();
        }

        @Override
        public Future<Object> get(String formula, JsonObject params)
        {
            return raw(formula, params == null ? new JsonObject() : params, topics, liveKey);
        }

        @Override
        public Future<Object> optional(String formula, JsonObject params)
        {
            return has(formula) ? get(formula, params) : Future.succeededFuture(null);
        }
    }

    /** Formula names, for diagnostics. */
    public Collection<String> formulas()
    {
        return registry.formulas().stream().map(Formula::name).toList();
    }
}
