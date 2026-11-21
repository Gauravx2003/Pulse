package com.pulse.modules.okrs;

import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The OKR side of CSV import and export (task 14.3), implementing {@link OkrImportPort} with the same
 * permission and validation rules as {@link OkrService}.
 */
public class OkrImportService implements OkrImportPort
{

    public static final String MODE_UPSERT = "upsert";

    public static final String MODE_NEW = "new";

    private static final Pattern QUARTER_KEY = Pattern.compile("^([0-9]{4})-Q([1-4])$", Pattern.CASE_INSENSITIVE);

    private static final Pattern QUARTER_LABEL = Pattern.compile("^Q([1-4])\\s+([0-9]{4})$", Pattern.CASE_INSENSITIVE);

    private static final int MAX_KEY_RESULTS = 5;

    private static final String DEFAULT_UNIT = "%";

    private final SqlExecutor sql;

    private final OkrService okrs;

    public OkrImportService(SqlExecutor sql, BiFunction<JsonObject, String, Future<Boolean>> canSeePerson)
    {
        this.sql = Objects.requireNonNull(sql, "sql");

        this.okrs = new OkrService(sql, canSeePerson);
    }

    /**
     * The quarter key ({@code 2026-Q3}) of a CSV or key form ({@code Q3 2026}, {@code q3 2026},
     * {@code 2026-Q3}), or {@code null} when it is neither.
     */
    public static String quarterKey(String value)
    {
        if (value == null)
        {
            return null;
        }

        String text = value.strip();

        Matcher key = QUARTER_KEY.matcher(text);

        if (key.matches())
        {
            return key.group(1) + "-Q" + key.group(2);
        }

        Matcher label = QUARTER_LABEL.matcher(text);

        return label.matches() ? label.group(2) + "-Q" + label.group(1) : null;
    }

    /**
     * The CSV form ({@code Q3 2026}) of a quarter key.
     */
    public static String quarterLabel(String key)
    {
        String normalized = quarterKey(key);

        if (normalized == null)
        {
            return key;
        }

        return "Q" + normalized.substring(6) + " " + normalized.substring(0, 4);
    }

    @Override
    public Future<JsonObject> findOwner(JsonObject actor, String email)
    {
        String address = OkrService.trimmed(email);

        if (address == null)
        {
            return Future.succeededFuture(new JsonObject().put("error", "Missing"));
        }

        return new OkrRepository(sql).findUserByEmail(address)
                .compose(user ->
                {
                    if (user == null)
                    {
                        return Future.succeededFuture(new JsonObject().put("error", "No user with " + address));
                    }

                    String personId = user.getString("person_id");

                    if (personId == null)
                    {
                        return Future.succeededFuture(new JsonObject().put("error",
                                user.getString("name") + " has no performance profile, so can't own individual OKRs"));
                    }

                    JsonObject probe = new JsonObject().put("level", "Individual").put("owner_id", personId);

                    return okrs.canEdit(actor, probe).map(allowed -> allowed
                            ? user
                            : new JsonObject().put("error", "You can't manage OKRs for " + user.getString("name")));
                });
    }

    @Override
    public Future<JsonObject> findMatching(JsonObject actor, JsonObject key)
    {
        JsonObject target;

        try
        {
            target = matchKey(key);
        }
        catch (OkrException e)
        {
            return Future.failedFuture(e);
        }

        return new OkrRepository(sql)
                .findMatching(target.getString("quarter"), OkrService.parseId(target.getString("owner_id")),
                        target.getString("team"), target.getString("title"))
                .compose(objective -> objective == null
                        ? Future.succeededFuture(new JsonObject().putNull("objective").put("editable", true))
                        : okrs.canEdit(actor, objective).map(editable -> new JsonObject()
                                .put("objective", objective)
                                .put("editable", editable)));
    }

    @Override
    public Future<JsonObject> upsertObjective(JsonObject actor, JsonObject group, String mode)
    {
        if (!MODE_UPSERT.equals(mode) && !MODE_NEW.equals(mode))
        {
            return Future.failedFuture(OkrException.invalid("mode", "Choose a mode: add new and update matching, or add new only."));
        }

        JsonObject target;

        List<JsonObject> keyResults;

        try
        {
            target = matchKey(group);

            keyResults = importedKeyResults(group.getValue("key_results"));
        }
        catch (OkrException e)
        {
            return Future.failedFuture(e);
        }

        target.put("epic_key", OkrService.trimmed(OkrService.str(group, "epic_key")))
                .put("culture_value", OkrService.trimmed(OkrService.str(group, "culture_value")));

        UUID by = OkrService.parseId(actor.getString("userId"));

        return sql.inTransaction(tx ->
        {
            OkrRepository repo = new OkrRepository(tx);

            return repo.findMatching(target.getString("quarter"), OkrService.parseId(target.getString("owner_id")),
                            target.getString("team"), target.getString("title"))
                    .compose(existing ->
                    {
                        if (existing == null)
                        {
                            return createImported(repo, actor, target, keyResults, by);
                        }

                        if (MODE_NEW.equals(mode))
                        {
                            return Future.succeededFuture(new JsonObject()
                                    .put("action", "skip")
                                    .put("objective_id", existing.getString("id")));
                        }

                        return okrs.canEdit(actor, existing).compose(allowed -> allowed
                                ? updateImported(repo, existing, target, keyResults, by)
                                : Future.failedFuture(OkrException.conflict(
                                        "You can't edit the existing objective \"" + existing.getString("title") + "\"")));
                    });
        }).recover(OkrService::translate);
    }

    @Override
    public Future<Void> undoUpsert(JsonObject actor, JsonObject result)
    {
        String action = result == null ? null : result.getString("action");

        if (action == null || "skip".equals(action))
        {
            return Future.succeededFuture();
        }

        UUID id = OkrService.parseId(result.getString("objective_id"));

        Integer afterVersion = result.getInteger("after_version");

        if (id == null || afterVersion == null)
        {
            return Future.failedFuture(OkrException.invalid("result", "This import result cannot be undone."));
        }

        return sql.inTransaction(tx ->
        {
            OkrRepository repo = new OkrRepository(tx);

            return repo.lockVersion(id)
                    .compose(version ->
                    {
                        if (version == null || version.intValue() != afterVersion.intValue())
                        {
                            return Future.failedFuture(OkrException.conflict(
                                    "The objective was changed after the import, so the import can't be undone."));
                        }

                        return repo.findObjective(id);
                    })
                    .compose(current -> okrs.canEdit(actor, current).compose(allowed ->
                    {
                        if (!allowed)
                        {
                            return Future.failedFuture(OkrException.forbidden());
                        }

                        if ("new".equals(action))
                        {
                            return repo.deleteObjective(id).<Void>mapEmpty();
                        }

                        return restore(repo, result);
                    }));
        });
    }

    @Override
    public Future<JsonArray> listForExport(JsonObject actor, String quarter)
    {
        String key = quarterKey(quarter);

        if (key == null)
        {
            return Future.failedFuture(OkrException.invalid("quarter", "Select a quarter."));
        }

        return new OkrRepository(sql).exportRows(key)
                .compose(rows ->
                {
                    Set<String> owners = new HashSet<>();

                    for (Object item : rows)
                    {
                        String owner = ((JsonObject) item).getString("owner_id");

                        if (owner != null)
                        {
                            owners.add(owner);
                        }
                    }

                    Map<String, Boolean> visible = new HashMap<>();

                    Future<Void> checks = Future.succeededFuture();

                    for (String owner : owners)
                    {
                        checks = checks.compose(v -> okrs.visible(actor, owner).map(seen ->
                        {
                            visible.put(owner, seen);

                            return null;
                        }));
                    }

                    return checks.map(v -> exportable(rows, visible));
                });
    }

    // ------------------------------------------------------------------ commit helpers

    private Future<JsonObject> createImported(OkrRepository repo, JsonObject actor, JsonObject target,
                                              List<JsonObject> keyResults, UUID by)
    {
        return okrs.canEdit(actor, target).compose(allowed ->
        {
            if (!allowed)
            {
                return Future.failedFuture(OkrException.forbidden());
            }

            return repo.insertObjective(target, by).compose(id ->
            {
                List<String> added = new ArrayList<>();

                Future<Void> chain = Future.succeededFuture();

                for (int i = 0; i < keyResults.size(); i++)
                {
                    int position = i + 1;

                    JsonObject kr = keyResults.get(i);

                    chain = chain.compose(v -> repo.insertKeyResult(id, position, kr).map(krId ->
                    {
                        added.add(krId);

                        return null;
                    }));
                }

                return chain.compose(v -> repo.lockVersion(id)).map(version -> new JsonObject()
                        .put("action", "new")
                        .put("objective_id", id.toString())
                        .putNull("before")
                        .put("after_version", version)
                        .put("added_key_results", new JsonArray(added))
                        .put("checkin_ids", new JsonArray()));
            });
        });
    }

    private Future<JsonObject> updateImported(OkrRepository repo, JsonObject existing, JsonObject target,
                                              List<JsonObject> keyResults, UUID by)
    {
        UUID id = UUID.fromString(existing.getString("id"));

        JsonArray current = existing.getJsonArray("key_results");

        Map<String, JsonObject> byText = new HashMap<>();

        for (Object item : current)
        {
            JsonObject kr = (JsonObject) item;

            byText.put(kr.getString("description").toLowerCase(Locale.ROOT), kr);
        }

        long newOnes = keyResults.stream()
                .filter(kr -> !byText.containsKey(kr.getString("description").toLowerCase(Locale.ROOT)))
                .count();

        if (current.size() + newOnes > MAX_KEY_RESULTS)
        {
            return Future.failedFuture(OkrException.invalid("key_result", "An objective holds at most 5 key results."));
        }

        List<String> added = new ArrayList<>();

        List<String> checkIns = new ArrayList<>();

        Future<Void> chain = Future.succeededFuture();

        int nextPosition = current.size() + 1;

        for (JsonObject kr : keyResults)
        {
            JsonObject match = byText.get(kr.getString("description").toLowerCase(Locale.ROOT));

            if (match == null)
            {
                int position = nextPosition++;

                chain = chain.compose(v -> repo.insertKeyResult(id, position, kr).map(krId ->
                {
                    added.add(krId);

                    return null;
                }));

                continue;
            }

            UUID krId = UUID.fromString(match.getString("id"));

            BigDecimal start = decimal(kr.getValue("start_value"));

            BigDecimal targetValue = decimal(kr.getValue("target_value"));

            BigDecimal value = decimal(kr.getValue("current_value"));

            chain = chain.compose(v -> repo.updateKeyResultRange(krId, start, targetValue, kr.getString("unit")))
                    .compose(v -> value.compareTo(decimal(match.getValue("current_value"))) == 0
                            ? Future.<Void>succeededFuture()
                            : repo.checkIn(krId, value, "", "import", by).map(row ->
                            {
                                checkIns.add(row.getString("checkin_id"));

                                return null;
                            }));
        }

        return chain
                .compose(v -> repo.touchObjective(id, target.getString("culture_value"), target.getString("epic_key")))
                .map(version -> new JsonObject()
                        .put("action", "update")
                        .put("objective_id", id.toString())
                        .put("before", existing)
                        .put("after_version", version)
                        .put("added_key_results", new JsonArray(added))
                        .put("checkin_ids", new JsonArray(checkIns)));
    }

    /**
     * Removes the key results and check-ins the import added, then puts every earlier key result and
     * the objective's fields back to the before-image.
     */
    private static Future<Void> restore(OkrRepository repo, JsonObject result)
    {
        JsonObject before = result.getJsonObject("before");

        if (before == null)
        {
            return Future.failedFuture(OkrException.invalid("result", "This import result cannot be undone."));
        }

        Future<Void> chain = Future.succeededFuture();

        for (Object krId : result.getJsonArray("added_key_results", new JsonArray()))
        {
            chain = chain.compose(v -> repo.deleteKeyResult(UUID.fromString((String) krId)).mapEmpty());
        }

        UUID[] checkIns = result.getJsonArray("checkin_ids", new JsonArray()).stream()
                .map(x -> UUID.fromString((String) x))
                .toArray(UUID[]::new);

        if (checkIns.length > 0)
        {
            chain = chain.compose(v -> repo.deleteCheckIns(checkIns).mapEmpty());
        }

        for (Object item : before.getJsonArray("key_results"))
        {
            JsonObject kr = (JsonObject) item;

            chain = chain.compose(v -> repo.updateKeyResult(UUID.fromString(kr.getString("id")),
                    kr.getInteger("position"), kr).mapEmpty());
        }

        return chain.compose(v -> repo.restoreObjective(before)).mapEmpty();
    }

    // ------------------------------------------------------------------ validation

    /**
     * Quarter, level, owner or team and text of an import group, validated like the objective form.
     */
    private static JsonObject matchKey(JsonObject group)
    {
        if (group == null)
        {
            throw OkrException.invalid("objective", "Missing");
        }

        String quarter = quarterKey(OkrService.str(group, "quarter"));

        if (quarter == null)
        {
            throw OkrException.invalid("quarter", "Select a quarter.");
        }

        String title = OkrService.trimmed(OkrService.str(group, "title"));

        if (title == null)
        {
            throw OkrException.invalid("objective", "Write the objective.");
        }

        String level = Objects.requireNonNullElse(OkrService.str(group, "level"), "Individual");

        JsonObject key = new JsonObject().put("quarter", quarter).put("title", title);

        if ("Team".equalsIgnoreCase(level))
        {
            String team = OkrService.trimmed(OkrService.str(group, "team"));

            if (team == null)
            {
                throw OkrException.invalid("level", "Select a team.");
            }

            return key.put("level", "Team").put("team", team);
        }

        if (!"Individual".equalsIgnoreCase(level))
        {
            throw OkrException.invalid("level", "Use Individual or Team");
        }

        String owner = OkrService.trimmed(OkrService.str(group, "owner_person_id"));

        if (OkrService.parseId(owner) == null)
        {
            throw OkrException.invalid("owner_email", "Missing");
        }

        return key.put("level", "Individual").put("owner_id", owner);
    }

    /**
     * Key results of an import group: the objective form's rules, the import default unit and no
     * key result twice in one objective.
     */
    private static List<JsonObject> importedKeyResults(Object value)
    {
        if (value instanceof JsonArray array)
        {
            for (Object item : array)
            {
                if (item instanceof JsonObject kr && OkrService.trimmed(OkrService.str(kr, "unit")) == null)
                {
                    kr.put("unit", DEFAULT_UNIT);
                }
            }
        }

        List<JsonObject> keyResults = OkrService.validateKeyResults(value);

        Set<String> seen = new HashSet<>();

        for (JsonObject kr : keyResults)
        {
            if (!seen.add(kr.getString("description").toLowerCase(Locale.ROOT)))
            {
                throw OkrException.invalid("key_result", "Same key result twice in one objective");
            }
        }

        return keyResults;
    }

    private static JsonArray exportable(JsonArray rows, Map<String, Boolean> visible)
    {
        JsonArray out = new JsonArray();

        for (Object item : rows)
        {
            JsonObject row = (JsonObject) item;

            String owner = row.getString("owner_id");

            if (owner != null && !Boolean.TRUE.equals(visible.get(owner)))
            {
                continue;
            }

            out.add(new JsonObject()
                    .put("quarter", quarterLabel(row.getString("quarter")))
                    .put("level", row.getString("level"))
                    .put("owner_email", Objects.requireNonNullElse(row.getString("owner_email"), ""))
                    .put("objective", row.getString("objective"))
                    .put("key_result", row.getString("key_result"))
                    .put("start", row.getValue("start"))
                    .put("target", row.getValue("target"))
                    .put("current", row.getValue("current"))
                    .put("unit", row.getString("unit"))
                    .put("value", Objects.requireNonNullElse(row.getString("value"), ""))
                    .put("epic", Objects.requireNonNullElse(row.getString("epic"), "")));
        }

        return out;
    }

    private static BigDecimal decimal(Object value)
    {
        return new BigDecimal(value.toString());
    }
}
