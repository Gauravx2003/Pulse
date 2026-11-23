package com.pulse.modules.training;

import com.pulse.calc.core.WorkingDays;
import com.pulse.platform.module.ActionProvider;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Training items for the Action Center (task 21.2), ported from the prototype's action items
 * (app.js ~2102–2103), for holders of {@code training.manage} about the trainees they see (never
 * their own training):
 *
 * <ul>
 *   <li>{@code training.manager-actions}: the manager's next step per assignment — write the manager
 *       review and decision (High), record the assessment or mark a disagreement discussed (Medium).</li>
 *   <li>{@code training.blockers}: a mentor session in the last 5 days that raised a blocker (High).</li>
 *   <li>{@code training.sessions-behind}: a mentor who held fewer than 60% of the sessions their active
 *       mentees' meeting frequency asks for in the last 7 days (Low).</li>
 * </ul>
 *
 * <p>Item shape: {@code {key, priority, source, personId, personName, text, button, target: {module, id}}}.
 */
public final class TrainingActionProvider implements ActionProvider
{
    static final String MANAGER_ACTIONS = "training.manager-actions";

    static final String BLOCKERS = "training.blockers";

    static final String SESSIONS_BEHIND = "training.sessions-behind";

    /** Days a blocker stays on the list (prototype {@code daysSince(s.date) <= 5}). */
    static final int BLOCKER_DAYS = 5;

    private static final Set<String> ACTIVE = Set.of(TrainingLifecycle.IN_TRAINING, TrainingLifecycle.ASSESSMENT,
            TrainingLifecycle.FINAL_REVIEW, TrainingLifecycle.SIGN_OFF);

    private final String key;

    TrainingActionProvider(String key)
    {
        this.key = key;
    }

    static List<ActionProvider> all()
    {
        return List.of(new TrainingActionProvider(MANAGER_ACTIONS), new TrainingActionProvider(BLOCKERS),
                new TrainingActionProvider(SESSIONS_BEHIND));
    }

    @Override
    public String key()
    {
        return key;
    }

    @Override
    public Future<List<JsonObject>> actionsFor(JsonObject user, ModuleContext ctx)
    {
        if (!PolicyService.permissions(user).contains("training.manage"))
        {
            return Future.succeededFuture(List.of());
        }

        // No items while the training tables are not installed (a test schema with other modules only).
        return ctx.sql().queryOne("SELECT to_regclass('training_assignments') IS NOT NULL AS installed", Tuple.tuple())
                .compose(t -> t.getBoolean("installed") ? items(user, ctx) : Future.succeededFuture(List.of()));
    }

    private Future<List<JsonObject>> items(JsonObject user, ModuleContext ctx)
    {
        AssignmentService assignments = new AssignmentService(ctx);

        return assignments.list(user, null).compose(rows ->
        {
            List<JsonObject> others = new ArrayList<>();

            for (Object o : rows)
            {
                JsonObject a = (JsonObject) o;

                if (!a.getString("personId").equals(user.getString("personId")))
                {
                    others.add(a);
                }
            }

            return switch (key)
            {
                case MANAGER_ACTIONS -> Future.succeededFuture(managerActions(others));
                case BLOCKERS -> blockers(ctx, others, assignments.today());
                default -> behind(ctx, others, assignments.today());
            };
        });
    }

    private List<JsonObject> managerActions(List<JsonObject> assignments)
    {
        List<JsonObject> items = new ArrayList<>();

        for (JsonObject a : assignments)
        {
            JsonArray actions = a.getJsonArray("actions", new JsonArray());

            if (!a.getJsonArray("roles", new JsonArray()).contains("manager"))
            {
                continue;
            }

            if (actions.contains("managerReview"))
            {
                items.add(item("High", a, "Write manager review & decision · " + a.getString("planName")));
            }

            if (actions.contains("assess"))
            {
                items.add(item("Medium", a, "Record assessment · " + a.getString("planName")));
            }

            if (actions.contains("markDiscussed"))
            {
                items.add(item("Medium", a, "Mark disagreement discussed · " + a.getString("planName")));
            }
        }

        return items;
    }

    private Future<List<JsonObject>> blockers(ModuleContext ctx, List<JsonObject> assignments, LocalDate today)
    {
        Map<String, JsonObject> byId = byId(assignments);

        if (byId.isEmpty())
        {
            return Future.succeededFuture(List.of());
        }

        return new MentoringRepository(ctx.sql()).recentSessions(ids(byId), today.minusDays(BLOCKER_DAYS)).map(sessions ->
        {
            List<JsonObject> items = new ArrayList<>();

            for (Object o : sessions)
            {
                JsonObject s = (JsonObject) o;

                if (Boolean.TRUE.equals(s.getBoolean("blocker")))
                {
                    JsonObject a = byId.get(s.getString("assignmentId"));

                    items.add(item("High", a, "Blocker raised by mentor " + s.getString("loggedBy", "—") + ": " + s.getString("topics")));
                }
            }

            return items;
        });
    }

    private Future<List<JsonObject>> behind(ModuleContext ctx, List<JsonObject> assignments, LocalDate today)
    {
        Map<String, JsonObject> active = new LinkedHashMap<>();

        for (JsonObject a : assignments)
        {
            if (a.getString("mentorId") != null && ACTIVE.contains(a.getString("stage")))
            {
                active.put(a.getString("id"), a);
            }
        }

        if (active.isEmpty())
        {
            return Future.succeededFuture(List.of());
        }

        LocalDate from = today.minusDays(6);

        AssignmentRepository repo = new AssignmentRepository(ctx.sql());

        return Future.all(new MentoringRepository(ctx.sql()).recentSessions(ids(active), from), repo.holidays()).map(all ->
        {
            JsonArray sessions = all.resultAt(0);

            Set<LocalDate> holidays = WorkingDays.holidays(all.resultAt(1), null);

            Map<String, int[]> perMentor = new LinkedHashMap<>();

            Map<String, String> names = new LinkedHashMap<>();

            for (JsonObject a : active.values())
            {
                int expected = expected(a, from, today, holidays);

                int held = 0;

                for (Object o : sessions)
                {
                    held += a.getString("id").equals(((JsonObject) o).getString("assignmentId")) ? 1 : 0;
                }

                int[] counts = perMentor.computeIfAbsent(a.getString("mentorId"), k -> new int[2]);

                counts[0] += expected;

                counts[1] += Math.min(held, expected == 0 ? held : expected);

                names.put(a.getString("mentorId"), a.getString("mentorName"));
            }

            List<JsonObject> items = new ArrayList<>();

            for (Map.Entry<String, int[]> e : perMentor.entrySet())
            {
                int expected = e.getValue()[0];

                int held = e.getValue()[1];

                if (expected > 0 && held < expected * 0.6)
                {
                    items.add(new JsonObject()
                            .put("key", key)
                            .put("priority", "Low")
                            .put("source", "Mentoring")
                            .put("personId", null)
                            .put("personName", names.get(e.getKey()))
                            .put("text", "Mentor sessions behind: " + held + " of " + expected + " in the last 7 days")
                            .put("button", "Open")
                            .put("target", new JsonObject().put("module", "training").put("id", e.getKey())));
                }
            }

            return items;
        });
    }

    /**
     * Sessions the meeting frequency asks for in the last 7 days (prototype {@code sessExpected}): every
     * working day for Daily, two in five for Twice a week, else one per five working days, at least one.
     */
    static int expected(JsonObject a, LocalDate from, LocalDate today, Set<LocalDate> holidays)
    {
        LocalDate start = LocalDate.parse(a.getString("startOn"));

        LocalDate end = LocalDate.parse(a.getString("end"));

        LocalDate s = start.isAfter(from) ? start : from;

        LocalDate e = end.isBefore(today) ? end : today;

        if (s.isAfter(e))
        {
            return 0;
        }

        int wd = WorkingDays.count(s, e, holidays, null);

        String meet = a.getString("meetingFrequency", "Weekly");

        if ("Daily".equalsIgnoreCase(meet))
        {
            return wd;
        }

        if ("Twice a week".equalsIgnoreCase(meet))
        {
            return Math.max(1, Math.round(wd * 2 / 5f));
        }

        return Math.max(1, (int) Math.ceil(wd / 5d));
    }

    private JsonObject item(String priority, JsonObject a, String text)
    {
        return new JsonObject()
                .put("key", key)
                .put("priority", priority)
                .put("source", "Training")
                .put("personId", a.getString("personId"))
                .put("personName", a.getString("personName"))
                .put("text", text)
                .put("button", "Open")
                .put("target", new JsonObject().put("module", "training").put("id", a.getString("id")));
    }

    private static Map<String, JsonObject> byId(List<JsonObject> assignments)
    {
        Map<String, JsonObject> out = new LinkedHashMap<>();

        assignments.forEach(a -> out.put(a.getString("id"), a));

        return out;
    }

    private static UUID[] ids(Map<String, JsonObject> byId)
    {
        return byId.keySet().stream().map(UUID::fromString).toArray(UUID[]::new);
    }
}
