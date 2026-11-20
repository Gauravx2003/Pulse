package com.pulse.modules.okrs;

import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Objectives, key results and check-ins (spec growth/okrs, task 14.2): validation, okr.own / okr.team
 * permissions and transactional writes. Progress, status and the OKR pillar are formulas, so they are
 * computed by {@code calc/work} through CalcService (task 4.10) and are not part of this service.
 *
 * <p>The caller ({@code actor}) is a JSON object {@code {userId, personId, permissions: [...]}}.
 * Visibility of another person comes from {@code canSeePerson}, the seam for the policy service
 * (task 3.5): it resolves true when the actor may see that person's data.
 *
 * <p>Responses: reads of a missing or invisible objective fail with 404; writes on an objective the
 * actor may not change, or that does not exist, fail with 403, so neither reveals existence.
 */
public class OkrService
{

    public static final String OKR_OWN = "okr.own";

    public static final String OKR_TEAM = "okr.team";

    public static final String ALL_VIEW = "all.view";

    private static final Pattern QUARTER = Pattern.compile("^[0-9]{4}-Q[1-4]$");

    private static final int MAX_KEY_RESULTS = 5;

    private static final int MAX_NOTE = 500;

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String FOREIGN_KEY_VIOLATION = "23503";

    private final SqlExecutor sql;

    private final BiFunction<JsonObject, String, Future<Boolean>> canSeePerson;

    public OkrService(SqlExecutor sql, BiFunction<JsonObject, String, Future<Boolean>> canSeePerson)
    {
        this.sql = Objects.requireNonNull(sql, "sql");

        this.canSeePerson = Objects.requireNonNull(canSeePerson, "canSeePerson");
    }

    /**
     * One objective with its key results, if the actor may see it.
     */
    public Future<JsonObject> get(JsonObject actor, String objectiveId)
    {
        UUID id = parseId(objectiveId);

        if (id == null)
        {
            return Future.failedFuture(OkrException.notFound());
        }

        return new OkrRepository(sql).findObjective(id)
                .compose(objective -> objective == null
                        ? Future.failedFuture(OkrException.notFound())
                        : canView(actor, objective).compose(visible -> visible
                                ? Future.succeededFuture(objective)
                                : Future.failedFuture(OkrException.notFound())));
    }

    /**
     * A person's Individual objectives for a quarter ({@code 2026-Q3}).
     */
    public Future<JsonArray> listForPerson(JsonObject actor, String personId, String quarter)
    {
        UUID owner = parseId(personId);

        if (owner == null)
        {
            return Future.failedFuture(OkrException.notFound());
        }

        if (!validQuarter(quarter))
        {
            return Future.failedFuture(OkrException.invalid("quarter", "Select a quarter."));
        }

        return visible(actor, personId)
                .compose(visible -> visible
                        ? new OkrRepository(sql).listForOwner(owner, quarter)
                        : Future.failedFuture(OkrException.notFound()));
    }

    /**
     * A team's Team objectives for a quarter; Team OKRs are visible to everyone.
     */
    public Future<JsonArray> listForTeam(JsonObject actor, String team, String quarter)
    {
        if (team == null || team.isBlank())
        {
            return Future.failedFuture(OkrException.invalid("team", "Select a team."));
        }

        if (!validQuarter(quarter))
        {
            return Future.failedFuture(OkrException.invalid("quarter", "Select a quarter."));
        }

        return new OkrRepository(sql).listForTeam(team, quarter);
    }

    /**
     * Creates an objective with 1 to 5 key results. An Individual objective belongs to the actor unless
     * {@code owner_id} names a visible person and the actor holds okr.team.
     */
    public Future<JsonObject> create(JsonObject actor, JsonObject body)
    {
        JsonObject objective;

        List<JsonObject> keyResults;

        try
        {
            objective = validateObjective(body, true);

            keyResults = validateKeyResults(body.getValue("key_results"));
        }
        catch (OkrException e)
        {
            return Future.failedFuture(e);
        }

        if ("Individual".equals(objective.getString("level")) && objective.getString("owner_id") == null)
        {
            objective.put("owner_id", actor.getString("personId"));
        }

        if ("Individual".equals(objective.getString("level")) && objective.getString("owner_id") == null)
        {
            return Future.failedFuture(OkrException.invalid("owner_id", "Select an owner."));
        }

        return canEdit(actor, objective)
                .compose(allowed ->
                {
                    if (!allowed)
                    {
                        return Future.failedFuture(OkrException.forbidden());
                    }

                    return sql.inTransaction(tx ->
                    {
                        OkrRepository repo = new OkrRepository(tx);

                        return repo.insertObjective(objective, parseId(actor.getString("userId")))
                                .compose(id -> insertKeyResults(repo, id, keyResults, 1)
                                        .compose(done -> repo.findObjective(id)));
                    });
                })
                .recover(OkrService::translate);
    }

    /**
     * Updates an objective and its key results. {@code version} must be the version the caller read
     * (409 otherwise). Key results with an {@code id} are kept and updated, those left out are removed
     * and new ones (no {@code id}) are added at the end; kept key results may not be reordered.
     */
    public Future<JsonObject> update(JsonObject actor, String objectiveId, JsonObject body)
    {
        UUID id = parseId(objectiveId);

        JsonObject changes;

        List<JsonObject> keyResults;

        try
        {
            changes = validateObjective(body, false);

            keyResults = validateKeyResults(body.getValue("key_results"));
        }
        catch (OkrException e)
        {
            return Future.failedFuture(e);
        }

        Integer version = body.getValue("version") instanceof Number n ? n.intValue() : null;

        if (version == null)
        {
            return Future.failedFuture(OkrException.invalid("version", "Reload the objective and try again."));
        }

        if (id == null)
        {
            return Future.failedFuture(OkrException.forbidden());
        }

        return sql.inTransaction(tx ->
        {
            OkrRepository repo = new OkrRepository(tx);

            return repo.findObjective(id)
                    .compose(current -> current == null
                            ? Future.failedFuture(OkrException.forbidden())
                            : canEdit(actor, current).compose(allowed ->
                            {
                                if (!allowed)
                                {
                                    return Future.failedFuture(OkrException.forbidden());
                                }

                                return repo.updateObjective(id, version, changes)
                                        .compose(updated -> updated == 0
                                                ? Future.failedFuture(OkrException.conflict(
                                                        "This objective was changed by someone else. Reload and try again."))
                                                : replaceKeyResults(repo, id, current.getJsonArray("key_results"), keyResults))
                                        .compose(done -> repo.findObjective(id));
                            }));
        }).recover(OkrService::translate);
    }

    /**
     * Deletes an objective with its key results and check-ins; resolves to {@code {deleted, key_results}}.
     */
    public Future<JsonObject> delete(JsonObject actor, String objectiveId)
    {
        UUID id = parseId(objectiveId);

        if (id == null)
        {
            return Future.failedFuture(OkrException.forbidden());
        }

        return sql.inTransaction(tx ->
        {
            OkrRepository repo = new OkrRepository(tx);

            return repo.findObjective(id)
                    .compose(current -> current == null
                            ? Future.failedFuture(OkrException.forbidden())
                            : canEdit(actor, current).compose(allowed -> allowed
                                    ? repo.deleteObjective(id).map(n -> new JsonObject()
                                            .put("deleted", true)
                                            .put("key_results", current.getJsonArray("key_results").size()))
                                    : Future.failedFuture(OkrException.forbidden())));
        });
    }

    /**
     * Records a new current value for a key result with an optional note; resolves to the key result.
     */
    public Future<JsonObject> checkIn(JsonObject actor, String keyResultId, JsonObject body)
    {
        UUID id = parseId(keyResultId);

        Object value = body.getValue("value");

        if (!(value instanceof Number))
        {
            return Future.failedFuture(OkrException.invalid("value", "Enter the current value."));
        }

        String note = Objects.requireNonNullElse(str(body, "note"), "").strip();

        if (note.length() > MAX_NOTE)
        {
            return Future.failedFuture(OkrException.invalid("note", "Keep the note under 500 characters."));
        }

        if (id == null)
        {
            return Future.failedFuture(OkrException.forbidden());
        }

        return sql.inTransaction(tx ->
        {
            OkrRepository repo = new OkrRepository(tx);

            return repo.lockObjectiveOfKeyResult(id)
                    .compose(objective -> objective == null
                            ? Future.failedFuture(OkrException.forbidden())
                            : canEdit(actor, objective).compose(allowed -> allowed
                                    ? repo.checkIn(id, new BigDecimal(value.toString()), note, "manual",
                                            parseId(actor.getString("userId")))
                                    : Future.failedFuture(OkrException.forbidden())));
        });
    }

    // ------------------------------------------------------------------ permissions

    Future<Boolean> canView(JsonObject actor, JsonObject objective)
    {
        if ("Team".equals(objective.getString("level")))
        {
            return Future.succeededFuture(true);
        }

        return visible(actor, objective.getString("owner_id"));
    }

    /**
     * The prototype's {@code canEditOkr}: okr.team with all.view edits everything; Team objectives need
     * okr.team; own objectives need okr.own; another visible person's objectives need okr.team.
     */
    Future<Boolean> canEdit(JsonObject actor, JsonObject objective)
    {
        if (has(actor, ALL_VIEW) && has(actor, OKR_TEAM))
        {
            return Future.succeededFuture(true);
        }

        if ("Team".equals(objective.getString("level")))
        {
            return Future.succeededFuture(has(actor, OKR_TEAM));
        }

        String owner = objective.getString("owner_id");

        if (owner != null && owner.equals(actor.getString("personId")))
        {
            return Future.succeededFuture(has(actor, OKR_OWN));
        }

        if (owner == null || !has(actor, OKR_TEAM))
        {
            return Future.succeededFuture(false);
        }

        return visible(actor, owner);
    }

    Future<Boolean> visible(JsonObject actor, String personId)
    {
        if (personId != null && personId.equals(actor.getString("personId")))
        {
            return Future.succeededFuture(true);
        }

        return canSeePerson.apply(actor, personId);
    }

    static boolean has(JsonObject actor, String permission)
    {
        JsonArray granted = actor.getJsonArray("permissions");

        return granted != null && granted.contains(permission);
    }

    // ------------------------------------------------------------------ key result writes

    private static Future<Void> insertKeyResults(OkrRepository repo, UUID objectiveId, List<JsonObject> keyResults,
                                                 int firstPosition)
    {
        Future<Void> chain = Future.succeededFuture();

        for (int i = 0; i < keyResults.size(); i++)
        {
            int position = firstPosition + i;

            JsonObject kr = keyResults.get(i);

            chain = chain.compose(v -> repo.insertKeyResult(objectiveId, position, kr).mapEmpty());
        }

        return chain;
    }

    /**
     * Removes left-out key results, then renumbers kept ones in ascending order (each only moves to a
     * lower or equal position, so the unique position never collides), then appends new ones.
     */
    private static Future<Void> replaceKeyResults(OkrRepository repo, UUID objectiveId, JsonArray existing,
                                                  List<JsonObject> requested)
    {
        List<String> existingIds = new ArrayList<>();

        for (Object item : existing)
        {
            existingIds.add(((JsonObject) item).getString("id"));
        }

        List<JsonObject> kept = new ArrayList<>();

        List<JsonObject> added = new ArrayList<>();

        int lastIndex = -1;

        for (JsonObject kr : requested)
        {
            String krId = kr.getString("id");

            if (krId == null)
            {
                added.add(kr);

                continue;
            }

            int index = existingIds.indexOf(krId);

            if (index < 0)
            {
                return Future.failedFuture(OkrException.invalid("key_results", "A key result does not belong to this objective."));
            }

            if (index < lastIndex || !added.isEmpty())
            {
                return Future.failedFuture(OkrException.invalid("key_results", "Key results cannot be reordered."));
            }

            lastIndex = index;

            kept.add(kr);
        }

        Future<Void> chain = Future.succeededFuture();

        for (String existingId : existingIds)
        {
            boolean stays = kept.stream().anyMatch(kr -> existingId.equals(kr.getString("id")));

            if (!stays)
            {
                chain = chain.compose(v -> repo.deleteKeyResult(UUID.fromString(existingId)).mapEmpty());
            }
        }

        for (int i = 0; i < kept.size(); i++)
        {
            int position = i + 1;

            JsonObject kr = kept.get(i);

            chain = chain.compose(v -> repo.updateKeyResult(UUID.fromString(kr.getString("id")), position, kr).mapEmpty());
        }

        return chain.compose(v -> insertKeyResults(repo, objectiveId, added, kept.size() + 1));
    }

    // ------------------------------------------------------------------ validation (spec growth/okrs)

    /**
     * Normalizes the objective's own fields; {@code creating} also reads level, owner and team, which
     * cannot change afterwards.
     */
    private static JsonObject validateObjective(JsonObject body, boolean creating)
    {
        if (body == null)
        {
            throw OkrException.invalid("title", "Write the objective.");
        }

        String title = trimmed(str(body, "title"));

        if (title == null)
        {
            throw OkrException.invalid("title", "Write the objective.");
        }

        String quarter = trimmed(str(body, "quarter"));

        if (!validQuarter(quarter))
        {
            throw OkrException.invalid("quarter", "Select a quarter.");
        }

        JsonObject out = new JsonObject()
                .put("title", title)
                .put("quarter", quarter)
                .put("epic_key", trimmed(str(body, "epic_key")))
                .put("culture_value", trimmed(str(body, "culture_value")));

        if (!creating)
        {
            return out;
        }

        String level = Objects.requireNonNullElse(str(body, "level"), "Individual");

        if (!"Individual".equals(level) && !"Team".equals(level))
        {
            throw OkrException.invalid("level", "Choose Individual or Team.");
        }

        out.put("level", level);

        if ("Team".equals(level))
        {
            String team = trimmed(str(body, "team"));

            if (team == null)
            {
                throw OkrException.invalid("team", "Select a team.");
            }

            out.put("team", team);
        }
        else
        {
            String owner = trimmed(str(body, "owner_id"));

            if (owner != null && parseId(owner) == null)
            {
                throw OkrException.invalid("owner_id", "Select an owner.");
            }

            out.put("owner_id", owner);
        }

        return out;
    }

    static List<JsonObject> validateKeyResults(Object value)
    {
        if (!(value instanceof JsonArray array) || array.isEmpty() || array.size() > MAX_KEY_RESULTS)
        {
            throw OkrException.invalid("key_results", "Add 1 to 5 key results.");
        }

        List<JsonObject> out = new ArrayList<>();

        for (Object item : array)
        {
            if (!(item instanceof JsonObject kr))
            {
                throw OkrException.invalid("key_results", "Every key result needs a description.");
            }

            String description = trimmed(str(kr, "description"));

            if (description == null)
            {
                throw OkrException.invalid("key_results", "Every key result needs a description.");
            }

            if (!(kr.getValue("start_value") instanceof Number start) || !(kr.getValue("target_value") instanceof Number target))
            {
                throw OkrException.invalid("key_results", "Enter a start and a target for every key result.");
            }

            if (new BigDecimal(start.toString()).compareTo(new BigDecimal(target.toString())) == 0)
            {
                throw OkrException.invalid("key_results", "Target must differ from start.");
            }

            Object current = kr.getValue("current_value");

            if (current != null && !(current instanceof Number))
            {
                throw OkrException.invalid("key_results", "Enter the current value as a number.");
            }

            JsonObject clean = new JsonObject()
                    .put("description", description)
                    .put("start_value", start)
                    .put("target_value", target)
                    .put("current_value", current == null ? start : current)
                    .put("unit", Objects.requireNonNullElse(str(kr, "unit"), "").strip());

            if (kr.getValue("id") != null)
            {
                if (parseId(str(kr, "id")) == null)
                {
                    throw OkrException.invalid("key_results", "A key result does not belong to this objective.");
                }

                clean.put("id", kr.getString("id"));
            }

            out.add(clean);
        }

        return out;
    }

    /**
     * The string at {@code key}, or {@code null} when it is missing or not a string.
     */
    static String str(JsonObject json, String key)
    {
        return json.getValue(key) instanceof String s ? s : null;
    }

    private static boolean validQuarter(String quarter)
    {
        return quarter != null && QUARTER.matcher(quarter).matches();
    }

    static String trimmed(String value)
    {
        if (value == null)
        {
            return null;
        }

        String stripped = value.strip();

        return stripped.isEmpty() ? null : stripped;
    }

    static UUID parseId(String value)
    {
        if (value == null)
        {
            return null;
        }

        try
        {
            return UUID.fromString(value);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    /**
     * Maps database constraint failures to the OKR error contract; everything else passes through.
     */
    static <T> Future<T> translate(Throwable error)
    {
        if (error instanceof PgException pg && UNIQUE_VIOLATION.equals(pg.getSqlState()))
        {
            return Future.failedFuture(OkrException.conflict(
                    "An objective with this text already exists for this owner and quarter."));
        }

        if (error instanceof PgException pg && FOREIGN_KEY_VIOLATION.equals(pg.getSqlState()))
        {
            return Future.failedFuture(OkrException.invalid("owner_id", "Select an owner."));
        }

        return Future.failedFuture(error);
    }
}
