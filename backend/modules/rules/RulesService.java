package com.pulse.modules.rules;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.RuleCatalogue;
import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Rules &amp; thresholds (task 5.2, spec platform/rules-thresholds). Every save validates each value
 * against its type and range ({@link RuleCatalogue}), checks weight totals and the PR title pattern,
 * then writes a new rule version with the diff, updates the current values and audits actor and diff in
 * the same transaction. After the commit the calculation cache is dropped, so every screen uses the new
 * rules from the next request. Stored snapshots keep the rule version they were frozen with.
 *
 * <p>Holders of rules.manage edit every group except appraisal standards, which belong to holders of
 * appraisal.approve. Saves carry the rule version the client loaded; a stale one answers 409.
 */
final class RulesService
{
    static final int HISTORY_LIMIT = 50;

    private static final String UNIQUE_VIOLATION = "23505";

    private final ModuleContext ctx;

    RulesService(ModuleContext ctx)
    {
        this.ctx = ctx;
    }

    // ------------------------------------------------------------------ reads

    /** {@code {version, updatedAt, groups: [...]}} with the groups the caller may edit. */
    Future<JsonObject> overview(JsonObject user)
    {
        List<String> groups = editableGroups(user);
        RulesRepository repo = new RulesRepository(ctx.sql());

        return current(repo).compose(current ->
        {
            List<Future<JsonObject>> last = new ArrayList<>();

            groups.forEach(g -> last.add(repo.lastChange(RuleCatalogue.paths(g))));

            return Future.all(last).map(all ->
            {
                JsonArray out = new JsonArray();

                for (int i = 0; i < groups.size(); i++)
                {
                    out.add(groupView(groups.get(i), current.getJsonObject("rules"), all.resultAt(i)));
                }

                return new JsonObject()
                        .put("version", current.getInteger("version"))
                        .put("updatedAt", current.getString("at"))
                        .put("groups", out);
            });
        });
    }

    /** One group as on the Rules &amp; thresholds screen. */
    Future<JsonObject> group(JsonObject user, String group)
    {
        return requireGroup(user, group).compose(v ->
        {
            RulesRepository repo = new RulesRepository(ctx.sql());

            return current(repo).compose(current -> repo.lastChange(RuleCatalogue.paths(group))
                    .map(last -> groupView(group, current.getJsonObject("rules"), last)
                            .put("version", current.getInteger("version"))));
        });
    }

    /** {@code {items: [{version, at, actor, reason, changes: [{path, label, before, after}]}]}} for one group. */
    Future<JsonObject> history(JsonObject user, String group)
    {
        List<String> paths = RuleCatalogue.paths(group);

        return requireGroup(user, group)
                .compose(v -> new RulesRepository(ctx.sql()).history(paths, HISTORY_LIMIT))
                .map(rows ->
                {
                    JsonArray items = new JsonArray();

                    rows.forEach(r ->
                    {
                        JsonObject row = (JsonObject) r;
                        JsonObject diff = row.getJsonObject("diff", new JsonObject());
                        JsonArray changes = new JsonArray();

                        for (String path : paths)
                        {
                            JsonObject change = diff.getJsonObject(path);

                            if (change != null)
                            {
                                changes.add(new JsonObject()
                                        .put("path", path)
                                        .put("label", RuleCatalogue.field(path).getString("label"))
                                        .put("before", change.getValue("before"))
                                        .put("after", change.getValue("after")));
                            }
                        }

                        items.add(new JsonObject()
                                .put("version", row.getInteger("version"))
                                .put("at", row.getString("at"))
                                .put("actor", actor(row))
                                .put("reason", row.getString("reason"))
                                .put("changes", changes));
                    });

                    return new JsonObject().put("group", group).put("items", items);
                });
    }

    /** What Reset defaults would change for the caller: {@code {version, changes, items}}. */
    Future<JsonObject> resetPreview(JsonObject user)
    {
        return current(new RulesRepository(ctx.sql())).map(current ->
        {
            JsonObject diff = resetDiff(user, current.getJsonObject("rules"));

            return new JsonObject()
                    .put("version", current.getInteger("version"))
                    .put("changes", diff.size())
                    .put("items", changeList(diff));
        });
    }

    // ------------------------------------------------------------------ writes

    /**
     * Saves values of one group: {@code values} maps field paths of the group to new values. Resolves to
     * the group view with {@code version} and {@code changed} (number of values that changed).
     */
    Future<JsonObject> save(JsonObject user, String group, JsonObject values, int expectedVersion)
    {
        return requireGroup(user, group)
                .compose(v -> validate(group, values))
                .compose(v -> ctx.sql().inTransaction(tx ->
                {
                    RulesRepository repo = new RulesRepository(tx);

                    return current(repo).compose(current ->
                    {
                        checkVersion(current, expectedVersion);

                        JsonObject before = current.getJsonObject("rules");
                        JsonObject after = before.copy();

                        values.forEach(e -> RuleCatalogue.write(after, e.getKey(),
                                RuleCatalogue.normalize(e.getKey(), e.getValue())));

                        JsonObject problem = RuleCatalogue.groupProblem(group, after);

                        if (problem != null)
                        {
                            return Future.failedFuture(groupFailure(problem));
                        }

                        JsonObject diff = diff(before, after, RuleCatalogue.paths(group));

                        if (diff.isEmpty())
                        {
                            return Future.succeededFuture(new JsonObject()
                                    .put("version", current.getInteger("version"))
                                    .put("changed", 0));
                        }

                        String reason = "Saved " + RuleCatalogue.group(group).getString("label");

                        return writeVersion(tx, user, current, after, diff, reason)
                                .compose(version -> AuditService.record(tx, AuditService.entry(user,
                                                AuditEvents.RULES_CHANGED, "rules", group,
                                                new JsonObject()
                                                        .put("group", group)
                                                        .put("ruleVersion", version)
                                                        .put("changes", diff)))
                                        .map(x -> new JsonObject().put("version", version).put("changed", diff.size())));
                    });
                }))
                .compose(saved ->
                {
                    if (saved.getInteger("changed") > 0)
                    {
                        rulesChanged();
                    }

                    return group(user, group).map(view -> view.put("changed", saved.getInteger("changed")));
                });
    }

    /**
     * Restores every rule the caller may edit to its default (Admin: all rules), as a new rule version,
     * and audits the old and new values. Resolves to {@code {version, changes}}.
     */
    Future<JsonObject> reset(JsonObject user, int expectedVersion)
    {
        return ctx.sql().inTransaction(tx ->
        {
            RulesRepository repo = new RulesRepository(tx);

            return current(repo).compose(current ->
            {
                checkVersion(current, expectedVersion);

                JsonObject before = current.getJsonObject("rules");
                JsonObject diff = resetDiff(user, before);

                if (diff.isEmpty())
                {
                    return Future.succeededFuture(new JsonObject().put("version", current.getInteger("version"))
                            .put("changes", 0));
                }

                JsonObject after = before.copy();
                JsonObject defaults = RuleCatalogue.defaults();

                diff.fieldNames().forEach(path -> RuleCatalogue.write(after, path, RuleCatalogue.read(defaults, path)));

                return writeVersion(tx, user, current, after, diff, "Reset to defaults")
                        .compose(version -> AuditService.record(tx, AuditService.entry(user, AuditEvents.RULES_RESET,
                                        "rules", "all", new JsonObject()
                                                .put("ruleVersion", version)
                                                .put("changes", diff)))
                                .map(x -> new JsonObject().put("version", version).put("changes", diff.size())));
            });
        }).onSuccess(result ->
        {
            if (result.getInteger("changes") > 0)
            {
                rulesChanged();
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Groups the caller may edit, in card order. */
    static List<String> editableGroups(JsonObject user)
    {
        Set<String> held = PolicyService.permissions(user);
        List<String> out = new ArrayList<>();

        for (String g : RuleCatalogue.groupKeys())
        {
            if (held.contains(RuleCatalogue.group(g).getString("permission")))
            {
                out.add(g);
            }
        }

        return out;
    }

    /** 404 for an unknown group, 403 for a group the caller may not edit. */
    private Future<Void> requireGroup(JsonObject user, String group)
    {
        JsonObject g = RuleCatalogue.group(group);

        if (g == null)
        {
            return Future.failedFuture(PolicyService.notFound());
        }

        if (!PolicyService.permissions(user).contains(g.getString("permission")))
        {
            JsonObject entry = AuditService.entry(user, AuditEvents.PERMISSION_REFUSED, "rules", group,
                            new JsonObject().put("required", new JsonArray().add(g.getString("permission"))))
                    .put("outcome", "refused");

            return AuditService.record(ctx.sql(), entry).transform(x -> Future.failedFuture(PolicyService.noAccess()));
        }

        return Future.succeededFuture();
    }

    /** Type and range of every value (400), then the safety of a title pattern (422, off the event loop). */
    private Future<Void> validate(String group, JsonObject values)
    {
        if (values == null || values.isEmpty())
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "Send at least one rule value.")
                    .field("values"));
        }

        Set<String> allowed = new LinkedHashSet<>(RuleCatalogue.paths(group));

        for (String path : values.fieldNames())
        {
            if (!allowed.contains(path))
            {
                return Future.failedFuture(ApiException.badRequest("invalid_field",
                        "'" + path + "' is not a rule of this card.").field("values." + path));
            }

            String problem = RuleCatalogue.problem(path, values.getValue(path));

            if (problem != null)
            {
                return Future.failedFuture(ApiException.badRequest("invalid_field",
                        RuleCatalogue.field(path).getString("label") + " " + problem + ".").field("values." + path));
            }
        }

        List<String> patterns = values.fieldNames().stream()
                .filter(p -> RuleCatalogue.PATTERN.equals(RuleCatalogue.field(p).getString("type")))
                .toList();

        if (patterns.isEmpty())
        {
            return Future.succeededFuture();
        }

        return ctx.vertx().executeBlocking(() ->
        {
            for (String path : patterns)
            {
                String problem = SafeRegex.problem(values.getString(path));

                if (problem != null)
                {
                    throw ApiException.unprocessable("unsafe_pattern", problem).field("values." + path);
                }
            }

            return null;
        });
    }

    private static Future<JsonObject> current(RulesRepository repo)
    {
        // a rule the stored version lacks (added after it was written) reads, shows and saves from its default
        return repo.current().compose(row -> row == null
                ? Future.failedFuture(new IllegalStateException("No rule version: the base seed has not run"))
                : Future.succeededFuture(row.put("rules", RuleCatalogue.withDefaults(row.getJsonObject("rules")))));
    }

    private static void checkVersion(JsonObject current, int expectedVersion)
    {
        int version = current.getInteger("version");

        if (version != expectedVersion)
        {
            throw conflict(version);
        }
    }

    private static ApiException conflict(int currentVersion)
    {
        return ApiException.conflict("version_conflict",
                        "Someone else changed the rules since you opened them. Reload to see their changes.")
                .currentVersion(currentVersion);
    }

    private static ApiException groupFailure(JsonObject problem)
    {
        ApiException e = ApiException.unprocessable(problem.getString("code"), problem.getString("message"));

        if (problem.containsKey("total"))
        {
            e.detail("total", problem.getValue("total"));
        }

        if (problem.containsKey("field"))
        {
            e.field("values." + problem.getString("field"));
        }

        return e;
    }

    /** Inserts the next version and stores the changed top-level keys; resolves to the new version number. */
    private static Future<Integer> writeVersion(SqlExecutor tx, JsonObject user, JsonObject current, JsonObject after,
                                                JsonObject diff, String reason)
    {
        int next = current.getInteger("version") + 1;
        RulesRepository repo = new RulesRepository(tx);
        String actorId = user.getString("id");

        Map<String, String> keys = new LinkedHashMap<>();

        diff.fieldNames().forEach(path -> keys.putIfAbsent(path.split("\\.")[0],
                RuleCatalogue.field(path).getString("group")));

        return repo.insertVersion(next, after, diff, actorId, reason)
                .recover(e -> Future.failedFuture(e instanceof PgException pg && UNIQUE_VIOLATION.equals(pg.getSqlState())
                        ? conflict(next)
                        : e))
                .compose(row ->
                {
                    Future<Integer> chain = Future.succeededFuture(0);

                    for (Map.Entry<String, String> key : keys.entrySet())
                    {
                        chain = chain.compose(n -> repo.upsertRule(key.getKey(), key.getValue(),
                                after.getValue(key.getKey()), actorId));
                    }

                    return chain.map(n -> next);
                });
    }

    /** {@code path -> {before, after}} for the paths whose value differs. */
    private static JsonObject diff(JsonObject before, JsonObject after, List<String> paths)
    {
        JsonObject diff = new JsonObject();

        for (String path : paths)
        {
            Object b = RuleCatalogue.read(before, path);
            Object a = RuleCatalogue.read(after, path);

            if (!same(a, b))
            {
                diff.put(path, new JsonObject().put("before", b).put("after", a));
            }
        }

        return diff;
    }

    private static JsonObject resetDiff(JsonObject user, JsonObject rules)
    {
        JsonObject defaults = RuleCatalogue.defaults();
        List<String> paths = new ArrayList<>();

        editableGroups(user).forEach(g -> paths.addAll(RuleCatalogue.paths(g)));

        return diff(rules, defaults, paths);
    }

    private static JsonArray changeList(JsonObject diff)
    {
        JsonArray out = new JsonArray();

        diff.forEach(e ->
        {
            JsonObject field = RuleCatalogue.field(e.getKey());
            JsonObject change = (JsonObject) e.getValue();

            out.add(new JsonObject()
                    .put("path", e.getKey())
                    .put("group", field.getString("group"))
                    .put("label", field.getString("label"))
                    .put("value", change.getValue("before"))
                    .put("default", change.getValue("after")));
        });

        return out;
    }

    static boolean same(Object a, Object b)
    {
        if (a instanceof Number x && b instanceof Number y)
        {
            return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        }

        return Objects.equals(a, b);
    }

    private static JsonObject groupView(String group, JsonObject rules, JsonObject lastChange)
    {
        JsonObject g = RuleCatalogue.group(group);
        JsonArray fields = new JsonArray();

        g.getJsonArray("fields").forEach(f ->
        {
            JsonObject field = ((JsonObject) f).copy();

            field.put("value", RuleCatalogue.read(rules, field.getString("path")));
            fields.add(field);
        });

        g.put("fields", fields);

        if (g.getString("totalOf") != null)
        {
            g.put("total", RuleCatalogue.total(rules, g.getString("totalOf")));
        }

        g.put("lastChanged", lastChange == null ? null : new JsonObject()
                .put("version", lastChange.getInteger("version"))
                .put("at", lastChange.getString("at"))
                .put("actor", actor(lastChange)));

        return g;
    }

    private static JsonObject actor(JsonObject row)
    {
        return row.getString("actorId") == null ? null
                : new JsonObject().put("id", row.getString("actorId")).put("name", row.getString("actorName"));
    }

    /** Drops cached results here and on every other HTTP instance (spec: "from the next request"). */
    private void rulesChanged()
    {
        if (ctx.calc() != null)
        {
            ctx.calc().invalidate(CalcService.RULES);
        }

        CalcService.publish(ctx.vertx(), CalcService.RULES);
    }
}
