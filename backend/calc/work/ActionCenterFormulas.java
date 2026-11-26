package com.pulse.calc.work;

import com.pulse.calc.core.Appraisal;
import com.pulse.calc.core.CalcContext;
import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.Formula;
import com.pulse.calc.core.FormulaModule;
import com.pulse.calc.core.WorkingDays;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.pulse.calc.work.WorkloadFormulas.list;
import static com.pulse.calc.work.WorkloadFormulas.safe;
import static com.pulse.calc.work.WorkloadFormulas.uuid;

/**
 * The Action center's formulas (task 13.2, spec insight/action-center), exposed through CalcService:
 * <ul>
 *   <li>{@code work.velocity {person}}: done story points per working day (leave and visits off) over the
 *   last 3 closed sprints.</li>
 *   <li>{@code work.forecast {person}}: the live sprint's forecast ({@link ActionCenter#forecast}); null
 *   without an active sprint.</li>
 *   <li>{@code work.nextSprint {}}: {@code {key, name, from, end}} of the sprint after the active one: a
 *   future sprint row when Jira has one, else the two working weeks after the active sprint ends.</li>
 *   <li>{@code work.capacity {person}}: the next sprint's capacity ({@link ActionCenter#capacity}) with
 *   leave and visits from Track 3's ports, and training and mentoring hours from
 *   {@code people.trainingLoad}.</li>
 *   <li>{@code work.signals {person}}: {@code {signals, risk, tone, next}} ({@link ActionCenter#signals}).</li>
 *   <li>{@code work.coverage {people}}: skills coverage of the given people.</li>
 *   <li>{@code work.teamPulse {people}}: the 6-week mood and workload trend and this week's answers.</li>
 *   <li>{@code work.wins {people}}: recent team wins: Track 3's {@code people.wins} (kudos, 5-star visits,
 *   completed training) and proactive bug fixes, newest first.</li>
 * </ul>
 *
 * <p>Track 3 ports read as null until registered (docs/calc.md §5): {@code people.lastOneOnOne},
 * {@code people.kudosReceived}, {@code people.trainingBehind}, {@code people.trainingLoad} and
 * {@code people.wins}; a signal whose input is absent is not raised.
 */
public final class ActionCenterFormulas implements FormulaModule
{

    public static final String VELOCITY = "work.velocity";

    public static final String FORECAST = "work.forecast";

    public static final String NEXT_SPRINT = "work.nextSprint";

    public static final String CAPACITY = "work.capacity";

    public static final String SIGNALS = "work.signals";

    public static final String COVERAGE = "work.coverage";

    public static final String TEAM_PULSE = "work.teamPulse";

    public static final String WINS = "work.wins";

    /**
     * Invalidation topic: a weekly pulse answer was saved (task 13.3).
     */
    public static final String PULSE = "work.pulse";

    /**
     * Invalidation topic: the skills coverage counts changed.
     */
    public static final String COVERAGE_DATA = "work.coverageData";

    /**
     * Closed sprints velocity averages over.
     */
    public static final int VELOCITY_SPRINTS = 3;

    /**
     * Weeks of pulse answers in the team trend.
     */
    public static final int PULSE_WEEKS = 6;

    @Override
    public String id()
    {
        return "work";
    }

    @Override
    public List<Formula> formulas()
    {
        Set<String> calendar = Set.of(CalcService.PEOPLE, CalcService.SPRINTS, WorkFormulas.HOLIDAYS,
                CalcService.importTopic("holidays"), CalcService.importTopic("attendance"), CalcService.importTopic("visits"));

        Set<String> work = new HashSet<>(calendar);

        work.addAll(Set.of(WorkloadFormulas.ISSUES, WorkloadFormulas.WORKLOGS, EffortFormulas.EXTRA_ITEMS, PULSE, CalcService.PERFORMANCE));

        return List.of(
                Formula.of(VELOCITY, Set.of("person"), work, safe(ActionCenterFormulas::velocity)),
                Formula.of(FORECAST, Set.of("person"), work, safe(ActionCenterFormulas::forecast)),
                Formula.of(NEXT_SPRINT, Set.of(), calendar, safe(ActionCenterFormulas::nextSprint)),
                Formula.of(CAPACITY, Set.of("person"), work, safe(ActionCenterFormulas::capacity)),
                Formula.of(SIGNALS, Set.of("person"), work, safe(ActionCenterFormulas::signals)),
                Formula.of(COVERAGE, Set.of("people"), Set.of(COVERAGE_DATA, CalcService.PEOPLE), safe(ActionCenterFormulas::coverage)),
                Formula.of(TEAM_PULSE, Set.of("people"), Set.of(PULSE, CalcService.PEOPLE), safe(ActionCenterFormulas::teamPulse)),
                Formula.of(WINS, Set.of("people"), Set.of(WorkloadFormulas.BUGS, CalcService.PEOPLE), safe(ActionCenterFormulas::wins)));
    }

    // ------------------------------------------------------------------ velocity and forecast

    private static Future<Object> velocity(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        return ctx.calendar().compose(calendar ->
        {
            List<JsonObject> closed = new ArrayList<>();

            for (Object o : calendar.sprints())
            {
                JsonObject sprint = (JsonObject) o;

                if (!sprint.getBoolean("live", false) && closed.size() < VELOCITY_SPRINTS)
                {
                    closed.add(sprint);
                }
            }

            Future<WorkloadFormulas.Days> days = WorkloadFormulas.days(ctx, person);

            List<Future<Object>> metrics = closed.stream()
                    .map(s -> ctx.get(WorkloadFormulas.SPRINT_METRICS, new JsonObject().put("person", person.toString()).put("period", s.getString("key"))))
                    .toList();

            return Future.all(new ArrayList<>(metrics)).compose(all -> days).map(d ->
            {
                // the prototype's workBetween(from, end, p): weekends, holidays, leave and visits off, but not
                // the days before joining, so a joiner's first sprint counts in full
                JsonObject profile = d.profile().copy().putNull("joinedOn");

                java.util.function.Predicate<LocalDate> present = day -> WorkingDays.isWorking(day, d.holidays(), profile);

                List<double[]> sprints = new ArrayList<>();

                for (int i = 0; i < closed.size(); i++)
                {
                    JsonObject sprint = closed.get(i);

                    Object m = metrics.get(i).result();

                    double done = m instanceof JsonObject o && o.getValue("dsp") instanceof Number n ? n.doubleValue() : 0;

                    int working = Workload.count(LocalDate.parse(sprint.getString("from")), LocalDate.parse(sprint.getString("end")), present);

                    sprints.add(new double[] {done, working});
                }

                return (Object) ActionCenter.velocity(sprints);
            });
        });
    }

    private static Future<Object> forecast(CalcContext ctx, JsonObject params)
    {
        return ctx.get(WorkloadFormulas.LIVE_SPRINT, new JsonObject().put("person", params.getString("person"))).map(live ->
                live instanceof JsonObject l
                        ? ActionCenter.forecast(l, ctx.rules().getDouble("focus", 6.0)).put("sprint", l.getString("sprint"))
                                .put("sprintName", l.getString("sprintName")) : null);
    }

    // ------------------------------------------------------------------ next-sprint capacity

    private static Future<Object> nextSprint(CalcContext ctx, JsonObject params)
    {
        return ctx.calendar().map(calendar ->
        {
            JsonObject current = calendar.activeSprint();

            if (current == null)
            {
                JsonArray all = calendar.sprints();

                current = all.isEmpty() ? null : all.getJsonObject(0);
            }

            if (current == null)
            {
                return null;
            }

            LocalDate after = LocalDate.parse(current.getString("to")).plusDays(1);

            for (int i = 0; i < 7; i++)
            {
                JsonObject future = calendar.sprintOn(after.plusDays(i));

                if (future != null && !future.getString("key").equals(current.getString("key")))
                {
                    return new JsonObject()
                            .put("key", future.getString("key"))
                            .put("name", future.getString("short"))
                            .put("from", future.getString("from"))
                            .put("end", future.getString("end"));
                }
            }

            // no future sprint yet: the two working weeks after the active sprint, Monday to Friday
            LocalDate from = after;

            while (from.getDayOfWeek() != DayOfWeek.MONDAY)
            {
                from = from.plusDays(1);
            }

            String number = current.getString("key").replaceAll("\\D", "");

            int next = number.isEmpty() ? 0 : Integer.parseInt(number) + 1;

            return (Object) new JsonObject()
                    .put("key", next == 0 ? null : "s:S" + next)
                    .put("name", next == 0 ? "Next sprint" : "Sprint " + next)
                    .put("from", from.toString())
                    .put("end", from.plusDays(11).toString());
        });
    }

    private static Future<Object> capacity(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        JsonObject ref = new JsonObject().put("person", person.toString());

        Future<Object> sprint = ctx.get(NEXT_SPRINT, new JsonObject());

        Future<WorkloadFormulas.Days> days = WorkloadFormulas.days(ctx, person);

        Future<Object> velocity = ctx.get(VELOCITY, ref);

        Future<Object> load = ctx.optional("people.trainingLoad", ref);

        return Future.all(sprint, days, velocity, load).map(all ->
        {
            if (!(sprint.result() instanceof JsonObject next))
            {
                return null;
            }

            WorkloadFormulas.Days d = days.result();

            JsonObject profile = d.profile();

            Set<LocalDate> leave = new HashSet<>();

            profile.getJsonArray("leave", new JsonArray()).forEach(day -> leave.add(LocalDate.parse((String) day)));

            JsonObject training = load.result() instanceof JsonObject t ? t : new JsonObject();

            return (Object) ActionCenter.capacity(LocalDate.parse(next.getString("from")), LocalDate.parse(next.getString("end")),
                            d.company(), leave, profile.getJsonArray("visits", new JsonArray()), training.getJsonArray("plans", new JsonArray()),
                            training.getInteger("mentees", 0), ((Number) velocity.result()).doubleValue(),
                            ctx.rules().getDouble("focus", 6.0), newJoiner(profile.getString("joinedOn"), ctx.today()))
                    .put("sprint", next.getString("name"));
        });
    }

    // ------------------------------------------------------------------ people signals

    private static Future<Object> signals(CalcContext ctx, JsonObject params)
    {
        UUID person = uuid(params, "person");

        JsonObject ref = new JsonObject().put("person", person.toString());

        LocalDate today = ctx.today();

        String month = "m:" + today.getYear() + "-" + String.format("%02d", today.getMonthValue());

        String quarter = "q:" + today.getYear() + "-Q" + ((today.getMonthValue() - 1) / 3 + 1);

        return ctx.calendar().compose(calendar ->
        {
            List<String> closed = new ArrayList<>();

            for (Object o : calendar.sprints())
            {
                JsonObject s = (JsonObject) o;

                if (!s.getBoolean("live", false) && closed.size() < VELOCITY_SPRINTS)
                {
                    closed.add(s.getString("key"));
                }
            }

            Future<Object> live = ctx.get(WorkloadFormulas.LIVE_SPRINT, ref);

            List<Future<Object>> utilization = closed.stream()
                    .map(k -> ctx.get(WorkloadFormulas.UTILIZATION, ref.copy().put("period", k)))
                    .toList();

            Future<Object> effort = ctx.get(EffortFormulas.EFFORT, ref.copy().put("period", month));

            Future<Object> discipline = ctx.get("core.discipline", ref);

            Future<Object> onboarding = ctx.get(WorkViewFormulas.ONBOARDING, ref);

            Future<Object> next = ctx.get(NEXT_SPRINT, new JsonObject());

            Future<Object> history = ctx.optional("core.indexHistory", ref).recover(e -> Future.succeededFuture(null));

            Future<Object> index = ctx.optional("core.index", ref.copy().put("period", quarter)).recover(e -> Future.succeededFuture(null));

            Future<Object> lastOneOnOne = ctx.optional("people.lastOneOnOne", ref);

            Future<Object> kudos = ctx.optional("people.kudosReceived", ref.copy().put("period", quarter));

            Future<Object> training = ctx.optional("people.trainingBehind", ref);

            Future<JsonArray> pulses = WorkSql.rows(ctx, """
                    SELECT week_start AS "weekStart", mood, workload, note FROM weekly_pulse
                    WHERE person_id = $1 ORDER BY week_start""", Tuple.of(person));

            Future<JsonObject> joined = WorkSql.row(ctx, "SELECT joined_on AS \"joinedOn\" FROM people WHERE id = $1", Tuple.of(person));

            Future<WorkloadFormulas.Days> company = WorkloadFormulas.days(ctx, null);

            List<Future<?>> waits = new ArrayList<>(List.of(live, effort, discipline, onboarding, next, history, index, lastOneOnOne, kudos,
                    training, pulses, joined, company));

            waits.addAll(utilization);

            return Future.all(waits).map(all ->
            {
                JsonObject in = new JsonObject();

                if (live.result() instanceof JsonObject l)
                {
                    in.put("util", l.getInteger("util"));
                }

                JsonArray closedUtil = new JsonArray();

                utilization.forEach(u -> closedUtil.add(u.result()));

                in.put("closed", closedUtil);

                in.put("newJoiner", newJoiner(joined.result() == null ? null : joined.result().getString("joinedOn"), today));

                in.put("onboarding", onboarding.result() != null);

                if (effort.result() instanceof JsonObject e)
                {
                    in.put("weekend", e.getInteger("weekend", 0)).put("share", e.getInteger("share"));
                }

                if (discipline.result() instanceof JsonObject dsc)
                {
                    in.put("discipline", dsc.getValue("score"));
                }

                if (lastOneOnOne.result() instanceof String last)
                {
                    LocalDate day = LocalDate.parse(last);

                    in.put("lastOneOnOne", last)
                            .put("lastOneOnOneLabel", day.getDayOfMonth() + " " + day.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH))
                            .put("oneOnOneDays", Workload.count(day.plusDays(1), today, company.result().company()));
                }

                in.put("pulses", pulses.result() == null ? new JsonArray() : pulses.result());

                if (history.result() instanceof JsonArray h && !h.isEmpty() && h.getValue(h.size() - 1) instanceof Number previous
                        && index.result() instanceof Number now)
                {
                    in.put("indexPrevious", previous).put("indexNow", now);
                }

                if (kudos.result() instanceof Number k)
                {
                    in.put("kudos", k.intValue());
                }

                if (training.result() instanceof JsonArray t)
                {
                    in.put("trainingBehind", t);
                }

                if (next.result() instanceof JsonObject n)
                {
                    in.put("nextSprint", n.getString("name"));
                }

                JsonArray signals = ActionCenter.signals(in, ctx.rules());

                return (Object) ActionCenter.risk(signals)
                        .put("signals", signals)
                        .put("next", signals.isEmpty() ? "Keep going" : signals.getJsonObject(0).getString("next"));
            });
        });
    }

    // ------------------------------------------------------------------ coverage, pulse and wins

    private static Future<Object> coverage(CalcContext ctx, JsonObject params)
    {
        UUID[] ids = TeamFormulas.ids(params).toArray(UUID[]::new);

        Future<JsonArray> areas = WorkSql.rows(ctx, "SELECT area FROM coverage_areas ORDER BY sort_order, area");

        Future<JsonArray> counts = WorkSql.rows(ctx, """
                SELECT area, person_id AS "personId", touches FROM coverage_counts WHERE person_id = ANY($1)""", Tuple.of(ids));

        Future<List<JsonObject>> people = people(ctx, ids);

        return Future.all(areas, counts, people).map(all ->
        {
            List<String> names = list(areas.result()).stream().map(a -> a.getString("area")).toList();

            Map<String, Map<String, Integer>> byArea = new LinkedHashMap<>();

            for (JsonObject c : list(counts.result()))
            {
                byArea.computeIfAbsent(c.getString("area"), k -> new LinkedHashMap<>()).put(c.getString("personId"), c.getInteger("touches"));
            }

            JsonArray columns = new JsonArray();

            people.result().stream().filter(p -> !p.getBoolean("newJoiner")).forEach(p -> columns.add(new JsonObject()
                    .put("id", p.getString("id")).put("name", p.getString("name")).put("initials", initials(p.getString("name")))));

            return (Object) new JsonObject()
                    .put("people", columns)
                    .put("areas", ActionCenter.coverage(names, byArea, people.result()));
        });
    }

    private static Future<Object> teamPulse(CalcContext ctx, JsonObject params)
    {
        UUID[] ids = TeamFormulas.ids(params).toArray(UUID[]::new);

        LocalDate since = ctx.today().minusWeeks(PULSE_WEEKS);

        return WorkSql.rows(ctx, """
                SELECT w.person_id AS "personId", p.name, w.week_start AS "weekStart", w.mood, w.workload
                FROM weekly_pulse w JOIN people p ON p.id = w.person_id
                WHERE w.person_id = ANY($1) AND w.week_start >= $2
                ORDER BY w.week_start, p.name""", Tuple.of(ids, since)).map(rows ->
        {
            List<JsonObject> answers = list(rows);

            Map<String, JsonObject> latest = new LinkedHashMap<>();

            answers.forEach(a -> latest.put(a.getString("personId"), a));

            double mood = latest.values().stream().mapToInt(a -> a.getInteger("mood")).average().orElse(Double.NaN);

            JsonArray low = new JsonArray();

            latest.values().stream().filter(ActionCenter::low).forEach(a -> low.add(a.getString("name")));

            return (Object) new JsonObject()
                    .put("weeks", ActionCenter.pulseTrend(answers))
                    .put("answered", latest.size())
                    .put("mood", Double.isNaN(mood) ? null : (Object) (Math.round(mood * 10) / 10.0))
                    .put("low", low);
        });
    }

    private static Future<Object> wins(CalcContext ctx, JsonObject params)
    {
        List<UUID> ids = TeamFormulas.ids(params);

        UUID[] array = ids.toArray(UUID[]::new);

        Future<List<JsonObject>> people = people(ctx, array);

        Future<JsonArray> fixes = WorkSql.rows(ctx, """
                SELECT b.key, b.title, b.resolved_on AS "at", f.id AS "personId", f.name AS who, o.name AS owner
                FROM bugs_current b JOIN people f ON f.id = b.fixed_by_id JOIN people o ON o.id = b.origin_person_id
                WHERE b.proactive AND b.status = 'Resolved' AND b.fixed_by_id = ANY($1)
                  AND b.fixed_by_id <> b.origin_person_id""", Tuple.of(array));

        List<Future<Object>> ports = ids.stream()
                .map(id -> ctx.optional("people.wins", new JsonObject().put("person", id.toString())))
                .toList();

        List<Future<?>> waits = new ArrayList<>(List.of(people, fixes));

        waits.addAll(ports);

        return Future.all(waits).map(all ->
        {
            Map<String, String> names = new LinkedHashMap<>();

            people.result().forEach(p -> names.put(p.getString("id"), p.getString("name")));

            List<JsonObject> out = new ArrayList<>();

            for (int i = 0; i < ids.size(); i++)
            {
                if (ports.get(i).result() instanceof JsonArray won)
                {
                    String id = ids.get(i).toString();

                    for (Object o : won)
                    {
                        out.add(((JsonObject) o).copy().put("personId", id).put("who", names.get(id)));
                    }
                }
            }

            for (JsonObject fix : list(fixes.result()))
            {
                out.add(new JsonObject()
                        .put("kind", "proactive-fix")
                        .put("personId", fix.getString("personId"))
                        .put("who", fix.getString("who"))
                        .put("what", "Fixed a bug in " + ActionCenter.firstName(fix.getString("owner")) + "’s code without being asked")
                        .put("text", fix.getString("key") + " · " + fix.getString("title"))
                        .put("value", "Problem Solving")
                        .put("at", fix.getString("at")));
            }

            out.sort((a, b) -> String.valueOf(b.getString("at")).compareTo(String.valueOf(a.getString("at"))));

            return (Object) new JsonArray(new ArrayList<>(out));
        });
    }

    // ------------------------------------------------------------------ helpers

    /**
     * {@code [{id, name, newJoiner}]} in the order of {@code ids}.
     */
    private static Future<List<JsonObject>> people(CalcContext ctx, UUID[] ids)
    {
        return WorkSql.rows(ctx, "SELECT id, name, joined_on AS \"joinedOn\" FROM people WHERE id = ANY($1)", Tuple.of((Object) ids)).map(rows ->
        {
            Map<String, JsonObject> byId = new LinkedHashMap<>();

            list(rows).forEach(r -> byId.put(r.getString("id"), r));

            List<JsonObject> out = new ArrayList<>();

            for (UUID id : ids)
            {
                JsonObject row = byId.get(id.toString());

                if (row != null)
                {
                    out.add(new JsonObject()
                            .put("id", row.getString("id"))
                            .put("name", row.getString("name"))
                            .put("newJoiner", newJoiner(row.getString("joinedOn"), ctx.today())));
                }
            }

            return out;
        });
    }

    static boolean newJoiner(String joinedOn, LocalDate today)
    {
        return joinedOn != null && Appraisal.monthsBetween(LocalDate.parse(joinedOn), today) < TeamFormulas.NEW_JOINER_MONTHS;
    }

    private static String initials(String name)
    {
        StringBuilder out = new StringBuilder();

        for (String part : name.trim().split("\\s+"))
        {
            if (!part.isEmpty() && out.length() < 2)
            {
                out.append(Character.toUpperCase(part.charAt(0)));
            }
        }

        return out.toString();
    }
}
