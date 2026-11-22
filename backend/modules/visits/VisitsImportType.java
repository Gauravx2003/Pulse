package com.pulse.modules.visits;

import com.pulse.modules.imports.csv.CsvColumns;
import com.pulse.modules.imports.type.ImportType;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.notify.NotificationService;
import com.pulse.platform.notify.NotificationTriggers;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Customer visit import (spec admin/data-imports-exports "Customer visit import"; task 16.3): planned
 * visits from the CRM, for managers and Admin.
 *
 * <ul>
 *   <li>Errors: person not in your team, purpose not in the list, over 14 days, overlap with existing
 *       visits or with another row of the file (both rows name each other), unknown account owner.</li>
 *   <li>Warning: a start in the past (the visit shows as Report due).</li>
 *   <li>A row identical to an existing visit (same person, dates and customer) is skipped, so an export
 *       checks back unchanged.</li>
 * </ul>
 *
 * <p>Commit notifies each engineer of their planned visit (platform 5.3).
 *
 * <p>Not here yet: overlap with leave (attendance, 17.1).
 * "Today" here is the server clock: import types get no CalcService clock, so PULSE_TODAY does not
 * pin the past-start warning.
 */
public class VisitsImportType implements ImportType
{
    @Override
    public String id()
    {
        return "visits";
    }

    @Override
    public String name()
    {
        return "Customer visits";
    }

    @Override
    public String feeds()
    {
        return "Customer visits (planned), capacity and the extra-mile credit once the report is filed";
    }

    @Override
    public List<String> permissions()
    {
        return List.of("team.view", "all.view");
    }

    @Override
    public JsonArray columns()
    {
        return JsonArray.of(
                CsvColumns.required("employee_email", CsvColumns.EMAIL, "email", "employee"),
                CsvColumns.required("customer", CsvColumns.TEXT),
                CsvColumns.required("city", CsvColumns.TEXT),
                CsvColumns.required("purpose", CsvColumns.TEXT),
                CsvColumns.required("from", CsvColumns.DATE, "start", "from_date"),
                CsvColumns.required("to", CsvColumns.DATE, "end", "to_date"),
                CsvColumns.required("requested_by", CsvColumns.TEXT, "requested"),
                CsvColumns.required("owner_email", CsvColumns.EMAIL, "account_owner_email", "owner"),
                CsvColumns.optional("reference", CsvColumns.TEXT, "ref", "deal"));
    }

    @Override
    public JsonArray modes()
    {
        return JsonArray.of(new JsonObject().put("key", "new").put("label", "Add as planned visits"));
    }

    @Override
    public JsonObject templateExample()
    {
        return new JsonObject()
                .put("employee_email", "arjun@pulse.demo")
                .put("customer", "Initech")
                .put("city", "Pune")
                .put("purpose", "Demo / POC")
                .put("from", "2026-10-13")
                .put("to", "2026-10-14")
                .put("requested_by", "Sana Mirza · account manager")
                .put("owner_email", "sana@pulse.demo")
                .put("reference", "DEAL-3150");
    }

    @Override
    public List<String> targetTables()
    {
        return List.of("visits");
    }

    // ------------------------------------------------------------------ check

    @Override
    public Future<JsonArray> check(JsonArray rows, String mode, JsonObject actor, SqlExecutor sql, PolicyService policy)
    {
        VisitRepository repo = new VisitRepository(sql);

        Set<String> emails = new LinkedHashSet<>();

        for (Object item : rows)
        {
            JsonObject values = ((JsonObject) item).getJsonObject("values");

            addIfPresent(emails, values.getString("employee_email"));

            addIfPresent(emails, values.getString("owner_email"));
        }

        Map<String, JsonObject> users = new HashMap<>();

        Map<String, Boolean> visible = new HashMap<>();

        return repo.usersByEmail(emails.toArray(new String[0]))
                .compose(found ->
                {
                    found.forEach(u -> users.put(((JsonObject) u).getString("email"), (JsonObject) u));

                    return resolveVisibility(rows, users, actor, policy, visible);
                })
                .compose(v ->
                {
                    for (Object item : rows)
                    {
                        checkRow((JsonObject) item, users, visible);
                    }

                    return checkOverlaps(rows, repo);
                })
                .map(rows);
    }

    /** One visibility decision per engineer, from the policy service (domain visits). */
    private static Future<Void> resolveVisibility(JsonArray rows, Map<String, JsonObject> users, JsonObject actor,
                                                 PolicyService policy, Map<String, Boolean> visible)
    {
        Set<String> people = new LinkedHashSet<>();

        for (Object item : rows)
        {
            JsonObject engineer = users.get(((JsonObject) item).getJsonObject("values").getString("employee_email"));

            if (engineer != null && engineer.getString("personId") != null)
            {
                people.add(engineer.getString("personId"));
            }
        }

        Future<Void> chain = Future.succeededFuture();

        for (String personId : people)
        {
            chain = chain.compose(v -> policy.requireVisible(actor, personId, PolicyService.DOMAIN_VISITS)
                    .map(true)
                    .otherwise(false)
                    .map(seen ->
                    {
                        visible.put(personId, seen);

                        return null;
                    }));
        }

        return chain;
    }

    private static void checkRow(JsonObject row, Map<String, JsonObject> users, Map<String, Boolean> visible)
    {
        if (!row.getJsonArray("errors").isEmpty())
        {
            return;
        }

        int line = row.getInteger("line");

        JsonObject values = row.getJsonObject("values");

        String email = values.getString("employee_email");

        JsonObject engineer = users.get(email);

        if (engineer == null)
        {
            error(row, line, "employee_email", "No user with " + email);

            return;
        }

        if (engineer.getString("personId") == null)
        {
            error(row, line, "employee_email", engineer.getString("name") + " has no performance profile.");

            return;
        }

        if (!Boolean.TRUE.equals(visible.get(engineer.getString("personId"))))
        {
            error(row, line, "employee_email", engineer.getString("name") + " is not in your team.");

            return;
        }

        String purpose = purpose(values.getString("purpose"));

        if (purpose == null)
        {
            error(row, line, "purpose", "“" + values.getString("purpose") + "” — use " + String.join(", ", VisitsRoutes.PURPOSES) + ".");

            return;
        }

        LocalDate from = LocalDate.parse(values.getString("from"));

        LocalDate to = LocalDate.parse(values.getString("to"));

        if (to.isBefore(from))
        {
            error(row, line, "to", "Before “from”.");

            return;
        }

        if (ChronoUnit.DAYS.between(from, to) + 1 > VisitService.MAX_DAYS)
        {
            error(row, line, "to", "A visit can be at most 14 days. Split it into separate visits.");

            return;
        }

        JsonObject owner = users.get(values.getString("owner_email"));

        if (owner == null || "Deactivated".equals(owner.getString("status")))
        {
            error(row, line, "owner_email", owner == null ? "No user with " + values.getString("owner_email")
                    : owner.getString("name") + " is deactivated.");

            return;
        }

        if (from.isBefore(LocalDate.now(PolicyService.ZONE)))
        {
            row.getJsonArray("warnings").add(message(line, "from",
                    "Visit already started — " + firstName(engineer.getString("name")) + " gets a “report due” reminder."));
        }

        row.put("visit", new JsonObject()
                .put("personId", engineer.getString("personId"))
                .put("userId", engineer.getString("id"))
                .put("personName", engineer.getString("name"))
                .put("customer", values.getString("customer"))
                .put("city", values.getString("city"))
                .put("purpose", purpose)
                .put("from", from.toString())
                .put("to", to.toString())
                .put("requestedBy", values.getString("requested_by"))
                .put("ownerId", owner.getString("id"))
                .put("reference", values.getString("reference")));
    }

    /** Overlap with stored visits (identical rows are skipped) and between rows of the file. */
    private static Future<Void> checkOverlaps(JsonArray rows, VisitRepository repo)
    {
        List<JsonObject> candidates = new ArrayList<>();

        Set<UUID> people = new LinkedHashSet<>();

        LocalDate min = null;

        LocalDate max = null;

        for (Object item : rows)
        {
            JsonObject visit = ((JsonObject) item).getJsonObject("visit");

            if (visit == null)
            {
                continue;
            }

            candidates.add((JsonObject) item);

            people.add(UUID.fromString(visit.getString("personId")));

            LocalDate from = LocalDate.parse(visit.getString("from"));

            LocalDate to = LocalDate.parse(visit.getString("to"));

            min = min == null || from.isBefore(min) ? from : min;

            max = max == null || to.isAfter(max) ? to : max;
        }

        if (candidates.isEmpty())
        {
            return Future.succeededFuture();
        }

        return repo.overlappingAny(people.toArray(new UUID[0]), min, max).map(stored ->
        {
            for (JsonObject row : candidates)
            {
                JsonObject visit = row.getJsonObject("visit");

                JsonObject clash = null;

                boolean identical = false;

                for (Object item : stored)
                {
                    JsonObject other = (JsonObject) item;

                    if (other.getString("personId").equals(visit.getString("personId")) && overlaps(visit, other))
                    {
                        identical = visit.getString("from").equals(other.getString("from"))
                                && visit.getString("to").equals(other.getString("to"))
                                && visit.getString("customer").equalsIgnoreCase(other.getString("customer"));

                        clash = other;

                        break;
                    }
                }

                if (identical)
                {
                    row.put("action", "unchanged");
                }
                else if (clash != null)
                {
                    error(row, row.getInteger("line"), "from", visit.getString("personName") + " already has a visit to "
                            + clash.getString("customer") + " on those dates.");
                }
            }

            // Rows of the same file: both rows of an overlapping pair are errors naming the other line.
            for (int i = 0; i < candidates.size(); i++)
            {
                for (int j = i + 1; j < candidates.size(); j++)
                {
                    JsonObject a = candidates.get(i);

                    JsonObject b = candidates.get(j);

                    JsonObject va = a.getJsonObject("visit");

                    JsonObject vb = b.getJsonObject("visit");

                    if (va.getString("personId").equals(vb.getString("personId")) && overlaps(va, vb))
                    {
                        error(a, a.getInteger("line"), "from", "Overlaps line " + b.getInteger("line") + " for " + va.getString("personName") + ".");

                        error(b, b.getInteger("line"), "from", "Overlaps line " + a.getInteger("line") + " for " + vb.getString("personName") + ".");
                    }
                }
            }

            for (JsonObject row : candidates)
            {
                if (row.getJsonArray("errors").isEmpty() && row.getString("action") == null)
                {
                    row.put("action", "new");
                }
                else if (!row.getJsonArray("errors").isEmpty())
                {
                    row.remove("action");
                }
            }

            return null;
        });
    }

    // ------------------------------------------------------------------ commit and export

    @Override
    public Future<JsonObject> commit(JsonArray rows, String mode, JsonObject actor, SqlExecutor tx, PolicyService policy)
    {
        VisitRepository repo = new VisitRepository(tx);

        UUID createdBy = UUID.fromString(actor.getString("id"));

        JsonArray changes = new JsonArray();

        Future<Void> chain = Future.succeededFuture();

        for (Object item : rows)
        {
            JsonObject visit = ((JsonObject) item).getJsonObject("visit");

            // Re-check overlap under the person's lock: a visit may have been planned since the Check.
            chain = chain.compose(v -> repo.lockPerson(UUID.fromString(visit.getString("personId"))))
                    .compose(v -> repo.overlapping(UUID.fromString(visit.getString("personId")), LocalDate.parse(visit.getString("from")),
                            LocalDate.parse(visit.getString("to")), null))
                    .compose(clash -> clash == null
                            ? repo.insertImported(visit, createdBy)
                            : Future.failedFuture(ApiException.conflict("visit_overlap", visit.getString("personName")
                                    + " got a visit to " + clash.getString("customer") + " on those dates after the check. Check the file again.")))
                    .compose(added -> notifyEngineer(tx, policy, actor, visit, added.getString("id")).map(added))
                    .map(added ->
                    {
                        changes.add(new JsonObject()
                                .put("table", "visits")
                                .put("id", added.getString("id"))
                                .put("action", "insert")
                                .putNull("before")
                                .put("afterVersion", added.getInteger("version")));

                        return null;
                    });
        }

        return chain.map(v -> new JsonObject()
                .put("imported", rows.size())
                .put("changes", changes)
                .put("notes", JsonArray.of(rows.size() + " visit" + (rows.size() == 1 ? "" : "s") + " planned.")));
    }

    @Override
    public Future<JsonArray> exportRows(JsonObject actor, JsonObject query, SqlExecutor sql, PolicyService policy)
    {
        int year = LocalDate.now(PolicyService.ZONE).getYear();

        LocalDate from;

        LocalDate to;

        try
        {
            from = query.getString("from") == null ? LocalDate.of(year, 1, 1) : LocalDate.parse(query.getString("from"));

            to = query.getString("to") == null ? LocalDate.of(year, 12, 31) : LocalDate.parse(query.getString("to"));
        }
        catch (RuntimeException e)
        {
            return Future.failedFuture(ApiException.badRequest("invalid_parameter", "Use YYYY-MM-DD for from and to."));
        }

        LocalDate start = from;

        LocalDate end = to;

        return policy.scope(actor, PolicyService.DOMAIN_VISITS).compose(scope -> new VisitRepository(sql).exportRows(scope, start, end));
    }

    // ------------------------------------------------------------------ helpers

    /** The engineer hears about their planned visit in the commit transaction (unless they imported it). */
    private static Future<JsonObject> notifyEngineer(SqlExecutor tx, PolicyService policy, JsonObject actor, JsonObject visit, String visitId)
    {
        if (visit.getString("userId") == null || visit.getString("userId").equals(actor.getString("id")))
        {
            return Future.succeededFuture();
        }

        return new NotificationService(tx, policy).notify(tx, NotificationTriggers.visitPlanned(visit.getString("userId"),
                visit.getString("customer"), visit.getString("city"), LocalDate.parse(visit.getString("from")),
                LocalDate.parse(visit.getString("to")), visit.getString("requestedBy"), visitId));
    }

    /** The listed purpose matching the cell ignoring case, or its first word ("Demo" → "Demo / POC"). */
    static String purpose(String value)
    {
        String text = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);

        for (String candidate : VisitsRoutes.PURPOSES)
        {
            String lower = candidate.toLowerCase(Locale.ROOT);

            if (lower.equals(text) || lower.split(" / ")[0].equals(text))
            {
                return candidate;
            }
        }

        return null;
    }

    private static boolean overlaps(JsonObject a, JsonObject b)
    {
        return !LocalDate.parse(a.getString("to")).isBefore(LocalDate.parse(b.getString("from")))
                && !LocalDate.parse(b.getString("to")).isBefore(LocalDate.parse(a.getString("from")));
    }

    private static void addIfPresent(Set<String> emails, String email)
    {
        if (email != null)
        {
            emails.add(email);
        }
    }

    private static String firstName(String name)
    {
        return name == null ? "The engineer" : name.split(" ")[0];
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
