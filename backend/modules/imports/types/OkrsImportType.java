package com.pulse.modules.imports.types;

import com.pulse.modules.imports.csv.CsvColumns;
import com.pulse.modules.imports.type.ImportType;
import com.pulse.modules.okrs.OkrException;
import com.pulse.modules.okrs.OkrImportPort;
import com.pulse.modules.okrs.OkrImportService;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OKR import (spec admin/data-imports-exports "OKR import" and growth/okrs "Import and export";
 * task 16.3). One row per key result; rows with the same quarter, owner (or team) and objective text
 * form one objective. Every read and write of OKR data goes through Track 2's {@link OkrImportPort}
 * (task 14.3), so the OKR rules stay in the OKR module.
 *
 * <ul>
 *   <li>Errors: unknown quarter, owner not visible or editable (an okr.own user may import only their
 *       own rows), target equal to start, non-numeric values, the same key result twice in one
 *       objective, more than 5 key results.</li>
 *   <li>Warning: a culture value that is not one of the 11 values (left empty).</li>
 *   <li>Modes: {@code upsert} (a new current value on a matching key result is a bulk check-in) and
 *       {@code new} (existing objectives are skipped).</li>
 *   <li>Team rows leave {@code owner_email} empty and belong to the importer's team, their reports'
 *       one team, or for HR / Admin the organisation's one team ({@link ActorTeamRepository}).</li>
 * </ul>
 *
 * <p>Undo goes back through the port ({@link #restoresItself()}): one objective change spans the
 * objective, its key results and its check-ins.
 */
public class OkrsImportType implements ImportType
{
    /** The 11 culture values an objective may name (reference spec section 12, prototype {@code VALUES}). */
    static final List<String> CULTURE_VALUES = List.of("Outcome Ownership", "Moral & Ethics", "Decision Making",
            "Problem Solving", "Risk Appetite", "Adaptability", "Agility in Execution", "Collaboration", "Resilience",
            "Resourcefulness", "Discipline");

    private static final int MAX_KEY_RESULTS = 5;

    @Override
    public String id()
    {
        return "okrs";
    }

    @Override
    public String name()
    {
        return "OKRs";
    }

    @Override
    public String feeds()
    {
        return "OKRs page and the Goals / OKRs pillar of the performance index";
    }

    @Override
    public List<String> permissions()
    {
        return List.of("okr.own", "okr.team");
    }

    @Override
    public JsonArray columns()
    {
        return JsonArray.of(
                CsvColumns.required("quarter", CsvColumns.TEXT),
                CsvColumns.oneOf(CsvColumns.optional("level", CsvColumns.ENUM), "Individual", "Team"),
                CsvColumns.requiredHeader("owner_email", CsvColumns.EMAIL, "owner", "email", "owner_mail"),
                CsvColumns.required("objective", CsvColumns.TEXT),
                CsvColumns.required("key_result", CsvColumns.TEXT, "kr", "keyresult"),
                CsvColumns.required("start", CsvColumns.NUMBER, "baseline"),
                CsvColumns.required("target", CsvColumns.NUMBER, "goal"),
                CsvColumns.optional("current", CsvColumns.NUMBER, "current_value"),
                CsvColumns.optional("unit", CsvColumns.TEXT),
                CsvColumns.optional("value", CsvColumns.TEXT, "culture_value"),
                CsvColumns.optional("epic", CsvColumns.TEXT));
    }

    @Override
    public JsonArray modes()
    {
        return JsonArray.of(
                new JsonObject().put("key", OkrImportService.MODE_UPSERT).put("label", "Add new and update matching objectives"),
                new JsonObject().put("key", OkrImportService.MODE_NEW).put("label", "Add new only"));
    }

    @Override
    public JsonObject templateExample()
    {
        return new JsonObject()
                .put("quarter", "Q4 2026")
                .put("level", "Individual")
                .put("owner_email", "priya@pulse.demo")
                .put("objective", "Cut alert noise for NOC teams")
                .put("key_result", "False-positive alerts")
                .put("start", "30")
                .put("target", "10")
                .put("current", "18")
                .put("unit", "%")
                .put("value", "Outcome Ownership")
                .put("epic", "PLAT-2301");
    }

    @Override
    public List<String> targetTables()
    {
        return List.of("objectives");
    }

    // ------------------------------------------------------------------ check

    @Override
    public Future<JsonArray> check(JsonArray rows, String mode, JsonObject actor, SqlExecutor sql, PolicyService policy)
    {
        JsonObject okrActor = okrActor(actor);

        OkrImportPort port = port(sql, policy);

        Map<String, JsonObject> owners = new HashMap<>();

        boolean allView = actor.getJsonArray("permissions", new JsonArray()).contains("all.view");

        return new ActorTeamRepository(sql).teamOf(actor.getString("id"), allView)
                .compose(team -> resolveOwners(rows, okrActor, port, owners).map(team))
                .compose(team ->
                {
                    Map<String, List<JsonObject>> groups = new LinkedHashMap<>();

                    for (Object item : rows)
                    {
                        checkRow((JsonObject) item, okrActor, team, owners, groups);
                    }

                    Future<Void> chain = Future.succeededFuture();

                    for (List<JsonObject> group : groups.values())
                    {
                        chain = chain.compose(v -> checkGroup(group, mode, okrActor, port));
                    }

                    return chain.map(rows);
                });
    }

    /** Looks up each distinct owner email once through the port ({@code {person_id, ...}} or {@code {error}}). */
    private static Future<Void> resolveOwners(JsonArray rows, JsonObject okrActor, OkrImportPort port, Map<String, JsonObject> owners)
    {
        Set<String> emails = new HashSet<>();

        for (Object item : rows)
        {
            JsonObject row = (JsonObject) item;

            String email = row.getJsonObject("values").getString("owner_email");

            if (email != null && row.getJsonArray("errors").isEmpty() && !"Team".equals(row.getJsonObject("values").getString("level")))
            {
                emails.add(email);
            }
        }

        Future<Void> chain = Future.succeededFuture();

        for (String email : emails)
        {
            chain = chain.compose(v -> port.findOwner(okrActor, email).map(owner ->
            {
                owners.put(email, owner);

                return null;
            }));
        }

        return chain;
    }

    /** Per-row rules; a valid row gets {@code okr} (its objective key) and joins its group. */
    private static void checkRow(JsonObject row, JsonObject okrActor, JsonObject team, Map<String, JsonObject> owners,
                                 Map<String, List<JsonObject>> groups)
    {
        if (!row.getJsonArray("errors").isEmpty())
        {
            return;
        }

        int line = row.getInteger("line");

        JsonObject values = row.getJsonObject("values");

        String quarter = OkrImportService.quarterKey(values.getString("quarter"));

        if (quarter == null)
        {
            error(row, line, "quarter", "“" + values.getString("quarter") + "” is not a quarter in Pulse. Use Q3 2026 or 2026-Q3.");

            return;
        }

        String level = values.getString("level") == null ? "Individual" : values.getString("level");

        JsonObject key = new JsonObject().put("quarter", quarter).put("level", level).put("title", values.getString("objective"));

        if ("Team".equals(level))
        {
            if (!okrActor.getJsonArray("permissions").contains("okr.team"))
            {
                error(row, line, "level", "You can’t import team OKRs.");

                return;
            }

            if (team.getString("team") == null)
            {
                error(row, line, "level", noTeamMessage(team));

                return;
            }

            key.put("team", team.getString("team"));
        }
        else
        {
            String email = values.getString("owner_email");

            if (email == null)
            {
                error(row, line, "owner_email", "Missing. Fill in the owner, or set level to Team.");

                return;
            }

            JsonObject owner = owners.get(email);

            if (owner == null || owner.getString("error") != null)
            {
                error(row, line, "owner_email", owner == null ? "No user with " + email : owner.getString("error"));

                return;
            }

            key.put("owner_person_id", owner.getString("person_id"));
        }

        BigDecimal start = (BigDecimal) values.getValue("start");

        BigDecimal target = (BigDecimal) values.getValue("target");

        if (start.compareTo(target) == 0)
        {
            error(row, line, "target", "Target must differ from start.");

            return;
        }

        String value = values.getString("value");

        if (value != null)
        {
            String canonical = canonicalValue(value);

            if (canonical == null)
            {
                row.getJsonArray("warnings").add(message(line, "value", "“" + value + "” is not one of the 11 values — left empty."));
            }

            key.put("value", canonical);
        }

        key.put("epic", values.getString("epic"));

        String groupKey = quarter + "|" + key.getString("owner_person_id", "team:" + key.getString("team"))
                + "|" + values.getString("objective").toLowerCase(Locale.ROOT);

        List<JsonObject> group = groups.computeIfAbsent(groupKey, k -> new ArrayList<>());

        String description = values.getString("key_result").toLowerCase(Locale.ROOT);

        for (JsonObject other : group)
        {
            if (other.getJsonObject("values").getString("key_result").toLowerCase(Locale.ROOT).equals(description))
            {
                error(row, line, "key_result", "Same key result twice in one objective (see line " + other.getInteger("line") + ").");

                return;
            }
        }

        row.put("okr", key);

        group.add(row);
    }

    /** Matches the group's objective through the port and sets each row's action. */
    private static Future<Void> checkGroup(List<JsonObject> group, String mode, JsonObject okrActor, OkrImportPort port)
    {
        JsonObject key = group.get(0).getJsonObject("okr");

        JsonObject match = new JsonObject()
                .put("quarter", key.getString("quarter"))
                .put("level", key.getString("level"))
                .put("title", key.getString("title"));

        if (key.getString("team") != null)
        {
            match.put("team", key.getString("team"));
        }
        else
        {
            match.put("owner_person_id", key.getString("owner_person_id"));
        }

        return port.findMatching(okrActor, match).map(found ->
        {
            JsonObject objective = found.getJsonObject("objective");

            if (objective == null)
            {
                if (group.size() > MAX_KEY_RESULTS)
                {
                    errorAll(group, "key_result", "An objective holds at most 5 key results.");

                    return null;
                }

                setAll(group, "new");

                return null;
            }

            if (OkrImportService.MODE_NEW.equals(mode))
            {
                setAll(group, "unchanged");

                JsonObject first = group.get(0);

                first.getJsonArray("warnings").add(message(first.getInteger("line"), "objective", "Already exists — skipped."));

                return null;
            }

            if (!found.getBoolean("editable", false))
            {
                errorAll(group, "objective", "You can’t edit the existing objective “" + objective.getString("title") + "”.");

                return null;
            }

            Set<String> existing = new HashSet<>();

            for (Object kr : objective.getJsonArray("key_results", new JsonArray()))
            {
                existing.add(((JsonObject) kr).getString("description").toLowerCase(Locale.ROOT));
            }

            int added = 0;

            for (JsonObject row : group)
            {
                if (!existing.contains(row.getJsonObject("values").getString("key_result").toLowerCase(Locale.ROOT)))
                {
                    added++;
                }
            }

            if (existing.size() + added > MAX_KEY_RESULTS)
            {
                errorAll(group, "key_result", "An objective holds at most 5 key results.");

                return null;
            }

            setAll(group, "update");

            return null;
        });
    }

    // ------------------------------------------------------------------ commit, undo, export

    @Override
    public Future<JsonObject> commit(JsonArray rows, String mode, JsonObject actor, SqlExecutor tx, PolicyService policy)
    {
        JsonObject okrActor = okrActor(actor);

        OkrImportPort port = port(tx, policy);

        Map<String, JsonObject> groups = new LinkedHashMap<>();

        for (Object item : rows)
        {
            JsonObject row = (JsonObject) item;

            JsonObject key = row.getJsonObject("okr");

            JsonObject values = row.getJsonObject("values");

            String groupKey = key.getString("quarter") + "|" + key.getString("owner_person_id", "team:" + key.getString("team"))
                    + "|" + key.getString("title").toLowerCase(Locale.ROOT);

            JsonObject group = groups.computeIfAbsent(groupKey, k -> new JsonObject()
                    .put("quarter", key.getString("quarter"))
                    .put("level", key.getString("level"))
                    .put("owner_person_id", key.getString("owner_person_id"))
                    .put("team", key.getString("team"))
                    .put("title", key.getString("title"))
                    .put("key_results", new JsonArray()));

            if (group.getString("culture_value") == null && key.getString("value") != null)
            {
                group.put("culture_value", key.getString("value"));
            }

            if (group.getString("epic_key") == null && key.getString("epic") != null)
            {
                group.put("epic_key", key.getString("epic"));
            }

            Object start = values.getValue("start");

            group.getJsonArray("key_results").add(new JsonObject()
                    .put("description", values.getString("key_result"))
                    .put("start_value", start)
                    .put("target_value", values.getValue("target"))
                    .put("current_value", values.getValue("current") == null ? start : values.getValue("current"))
                    .put("unit", values.getString("unit") == null ? "%" : values.getString("unit")));
        }

        JsonArray changes = new JsonArray();

        Future<Void> chain = Future.succeededFuture();

        for (JsonObject group : groups.values())
        {
            chain = chain.compose(v -> port.upsertObjective(okrActor, group, mode).map(result ->
            {
                if (!"skip".equals(result.getString("action")))
                {
                    changes.add(new JsonObject()
                            .put("table", "objectives")
                            .put("id", result.getString("objective_id"))
                            .put("action", "update")
                            .put("before", result)
                            .put("afterVersion", result.getInteger("after_version")));
                }

                return null;
            }));
        }

        return chain
                .map(v -> new JsonObject()
                        .put("imported", rows.size())
                        .put("changes", changes)
                        .put("notes", JsonArray.of(groups.size() + " objective" + (groups.size() == 1 ? "" : "s")
                                + " saved; new current values are recorded as check-ins.")))
                .recover(OkrsImportType::asApiException);
    }

    @Override
    public boolean restoresItself()
    {
        return true;
    }

    @Override
    public Future<Void> undoChange(JsonObject before, JsonObject actor, SqlExecutor tx, PolicyService policy)
    {
        return port(tx, policy).undoUpsert(okrActor(actor), before).recover(OkrsImportType::asApiException);
    }

    @Override
    public Future<JsonArray> exportRows(JsonObject actor, JsonObject query, SqlExecutor sql, PolicyService policy)
    {
        String quarter = OkrImportService.quarterKey(query.getString("quarter"));

        if (quarter == null)
        {
            return Future.failedFuture(ApiException.badRequest("invalid_parameter",
                    "Choose the quarter to export, for example ?quarter=Q3 2026.").field("quarter"));
        }

        return port(sql, policy).listForExport(okrActor(actor), quarter).recover(OkrsImportType::asApiException);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The OKR port's actor: the platform user plus {@code userId}, the key {@code OkrService} reads
     * the user id from (the platform user names it {@code id}).
     */
    static JsonObject okrActor(JsonObject user)
    {
        return user.copy().put("userId", user.getString("id"));
    }

    /** The OKR port on this executor; "can see person" is the policy service's OKR visibility. */
    private static OkrImportPort port(SqlExecutor sql, PolicyService policy)
    {
        return new OkrImportService(sql, (actor, personId) -> policy == null
                ? Future.succeededFuture(false)
                : policy.requireVisible(actor, personId, PolicyService.DOMAIN_OKRS).map(true).otherwise(false));
    }

    private static <T> Future<T> asApiException(Throwable error)
    {
        if (error instanceof OkrException okr)
        {
            String code = switch (okr.status())
            {
                case 400 -> "invalid_field";
                case 403 -> "no_access";
                case 404 -> "not_found";
                default -> "okr_conflict";
            };

            ApiException api = new ApiException(okr.status(), code, okr.getMessage());

            return Future.failedFuture(okr.field() == null ? api : api.field(okr.field()));
        }

        return Future.failedFuture(error);
    }

    /** Why a Team row has no team: none found, or several so the row is ambiguous (Romil's two cases). */
    static String noTeamMessage(JsonObject team)
    {
        if (ActorTeamRepository.SEVERAL_TEAMS.equals(team.getString("reason")))
        {
            List<String> names = new ArrayList<>();

            team.getJsonArray("teams", new JsonArray()).forEach(t -> names.add(String.valueOf(t)));

            return "Your people are in several teams (" + String.join(", ", names)
                    + "), so the team of a Team OKR row is unclear. Import it as Individual OKRs, or ask someone in that team.";
        }

        return "Neither you nor your direct reports belong to a team, so Team OKRs can’t be imported.";
    }

    private static String canonicalValue(String value)
    {
        for (String candidate : CULTURE_VALUES)
        {
            if (candidate.equalsIgnoreCase(value.strip()))
            {
                return candidate;
            }
        }

        return null;
    }

    private static void setAll(List<JsonObject> group, String action)
    {
        for (JsonObject row : group)
        {
            row.put("action", action);
        }
    }

    private static void errorAll(List<JsonObject> group, String column, String text)
    {
        for (JsonObject row : group)
        {
            error(row, row.getInteger("line"), column, text);

            row.remove("action");
        }
    }

    private static void error(JsonObject row, int line, String column, String text)
    {
        row.getJsonArray("errors").add(message(line, column, text));
    }

    private static JsonObject message(int line, String column, String text)
    {
        return new JsonObject().put("line", line).put("column", column).put("message", text);
    }
}
