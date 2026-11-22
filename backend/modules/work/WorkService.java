package com.pulse.modules.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.ResultGuard;
import com.pulse.calc.work.WorkViewFormulas;
import com.pulse.calc.work.WorkloadFormulas;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import com.pulse.platform.policy.Scope;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Overview, Work items and the issue drawer (task 8.2, spec work/work-items). Every number comes from
 * {@code calc/work} through CalcService; this service picks the formulas for the period, checks
 * visibility through the policy service and applies the list's filter, search and sort.
 *
 * <p>A period is a period key ({@code s:S43}, {@code m:2026-09}, {@code r:8.3}); without one the active
 * sprint is used, or the current month when no sprint is active.
 */
public class WorkService
{

    /**
     * Filters of the active sprint's list: All, Open, Stale / not started, Overdue, In review, Done.
     */
    public static final List<String> LIVE_FILTERS = List.of("all", "open", "stale", "overdue", "review", "done");

    /**
     * Filters of a closed period's list: All, Done, Carried over, Bugs, Open now.
     */
    public static final List<String> HISTORY_FILTERS = List.of("all", "done", "carried", "bugs", "open");

    public static final List<String> SORTS = List.of("key", "summary", "status", "sp", "remaining", "due", "last", "signals",
            "resolved");

    private static final List<String> STATUS_ORDER = List.of("To Do", "In Progress", "In Review", "Done");

    private final ModuleContext ctx;

    private final PolicyService policy;

    private final CalcService calc;

    public WorkService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();

        this.calc = ctx.calc();
    }

    /**
     * Overview of a person for a period: identity, the period, utilization by sprint, bugs and support,
     * the performance index, and either the active sprint (KPIs, alert, issues needing attention) or the
     * closed period (sprint metrics, utilization, the sprints of the period, the release card).
     */
    public Future<JsonObject> overview(JsonObject user, String personId, String periodKey)
    {
        return visible(user, personId).compose(scope -> resolve(personId, periodKey).compose(period ->
        {
            JsonObject person = new JsonObject().put("person", personId);

            JsonObject withPeriod = person.copy().put("period", period.getString("key"));

            boolean live = isLiveSprint(period);

            Future<JsonObject> identity = identity(scope, personId);

            Future<Object> trend = calc.get(WorkViewFormulas.UTILIZATION_TREND, person);

            Future<Object> bugs = calc.get(WorkloadFormulas.BUG_STATS, withPeriod);

            Future<JsonObject> index = index(personId);

            Future<JsonObject> body = live ? liveOverview(person) : closedOverview(withPeriod);

            return Future.all(identity, trend, bugs, index, body).map(all -> body.result()
                    .put("person", identity.result())
                    .put("period", period)
                    .put("live", live)
                    .put("utilizationTrend", trend.result())
                    .put("bugs", bugs.result())
                    .put("index", index.result()));
        }));
    }

    /**
     * Work items of a person for a period: KPIs, the list with its filter chips (filtered, searched and
     * sorted), and the daily load of the active sprint or the sprints and release card of a closed period.
     */
    public Future<JsonObject> workItems(JsonObject user, String personId, String periodKey, String filter, String query, String sort)
    {
        return visible(user, personId).compose(scope -> resolve(personId, periodKey).compose(period ->
        {
            boolean live = isLiveSprint(period);

            String chosen = filter == null || filter.isBlank() ? "all" : filter;

            if (!(live ? LIVE_FILTERS : HISTORY_FILTERS).contains(chosen))
            {
                return Future.failedFuture(ApiException.badRequest("invalid_parameter", "Unknown filter '" + chosen + "'.")
                        .field("filter"));
            }

            String order = sort == null || sort.isBlank() ? (live ? "-signals" : "-resolved") : sort;

            if (!SORTS.contains(order.startsWith("-") ? order.substring(1) : order))
            {
                return Future.failedFuture(ApiException.badRequest("invalid_parameter", "Unknown sort '" + order + "'.")
                        .field("sort"));
            }

            JsonObject person = new JsonObject().put("person", personId);

            JsonObject withPeriod = person.copy().put("period", period.getString("key"));

            Future<JsonObject> identity = identity(scope, personId);

            Future<JsonObject> body = live
                    ? liveWorkItems(person, withPeriod, chosen, query, order)
                    : closedWorkItems(withPeriod, chosen, query, order);

            return Future.all(identity, body).map(all -> body.result()
                    .put("person", identity.result())
                    .put("period", period)
                    .put("live", live)
                    .put("filter", chosen)
                    .put("sort", order));
        }));
    }

    /**
     * The issue drawer: the issue, its assignee, signals, the date and time checks and the activity
     * timeline, where bot and planning events are marked as not counted. A peer's issue answers 404 like
     * a missing one.
     */
    public Future<JsonObject> issue(JsonObject user, String key)
    {
        return policy.scope(user, PolicyService.DOMAIN_PERSON).compose(scope ->
        {
            IssueRepository issues = new IssueRepository(ctx.sql());

            return issues.byKey(scope, key).compose(issue ->
            {
                if (issue == null)
                {
                    return Future.failedFuture(ApiException.notFound("not_found", "Issue not found."));
                }

                UUID id = UUID.fromString(issue.getString("id"));

                String assignee = issue.getString("assigneeId");

                Future<JsonArray> events = issues.events(scope, id);

                Future<Object> signals = calc.get(WorkloadFormulas.ISSUE, new JsonObject().put("issue", id.toString()));

                Future<JsonObject> person = assignee == null ? Future.succeededFuture(null)
                        : new PersonRepository(ctx.sql()).identity(scope, UUID.fromString(assignee));

                return Future.all(events, signals, person).map(all ->
                {
                    JsonArray timeline = new JsonArray();

                    for (Object item : events.result())
                    {
                        JsonObject event = (JsonObject) item;

                        boolean counted = !event.getBoolean("isBot", false)
                                && WorkloadFormulas.COUNTED_ACTIVITY.contains(event.getString("kind"))
                                && assignee != null && assignee.equals(event.getString("actorId"));

                        timeline.add(event.copy().put("counted", counted));
                    }

                    JsonObject calcIssue = (JsonObject) signals.result();

                    return new JsonObject()
                            .put("issue", issue)
                            .put("assignee", person.result())
                            .put("flags", calcIssue.getValue("flags"))
                            .put("noEstimate", calcIssue.getValue("noEstimate"))
                            .put("checks", calcIssue.getValue("checks"))
                            .put("remainingHours", calcIssue.getValue("remainingHours"))
                            .put("lastActivity", calcIssue.getValue("lastActivity"))
                            .put("timeline", timeline);
                });
            });
        });
    }

    // ------------------------------------------------------------------ overview parts

    private Future<JsonObject> liveOverview(JsonObject person)
    {
        Future<Object> live = calc.get(WorkloadFormulas.LIVE_SPRINT, person);

        Future<Object> issues = calc.get(WorkViewFormulas.LIVE_ISSUES, person);

        Future<Object> alert = calc.get(WorkViewFormulas.LIVE_ALERT, person);

        Future<Object> onboarding = calc.get(WorkViewFormulas.ONBOARDING, person);

        return Future.all(live, issues, alert, onboarding).map(all ->
        {
            JsonObject metrics = (JsonObject) live.result();

            JsonArray items = ((JsonObject) issues.result()).getJsonArray("items");

            boolean onPlan = !ResultGuard.isNotApplicable(onboarding.result()) && onboarding.result() != null;

            JsonObject kpis = new JsonObject()
                    .put("tasks", items.size())
                    .put("storyPoints", metrics.getValue("sp"))
                    .put("done", metrics.getJsonArray("done").size())
                    .put("open", metrics.getJsonArray("open").size())
                    .put("stale", metrics.getJsonArray("stale").size())
                    .put("oldest", metrics.getValue("oldest"))
                    .put("remainingHours", metrics.getValue("rem"))
                    .put("workDays", metrics.getValue("work"))
                    .put("focus", metrics.getValue("focus"))
                    .put("utilization", metrics.getValue("util"))
                    .put("band", metrics.getValue("band"))
                    .put("capacity", metrics.getValue("cap"))
                    .put("onboarding", onPlan);

            List<JsonObject> attention = list(items).stream()
                    .filter(issue -> !issue.getJsonArray("flags", new JsonArray()).isEmpty())
                    .toList();

            return new JsonObject()
                    .put("kpis", kpis)
                    .put("alert", ResultGuard.isNotApplicable(alert.result()) ? null : alert.result())
                    .put("needsAttention", new JsonArray(new ArrayList<Object>(sorted(attention, "-signals"))));
        });
    }

    private Future<JsonObject> closedOverview(JsonObject withPeriod)
    {
        Future<Object> metrics = calc.get(WorkloadFormulas.SPRINT_METRICS, withPeriod);

        Future<Object> utilization = calc.get(WorkloadFormulas.UTILIZATION, withPeriod);

        Future<Object> sprints = calc.get(WorkViewFormulas.SPRINT_BREAKDOWN, withPeriod);

        Future<Object> release = calc.get(WorkViewFormulas.RELEASE_CARD, withPeriod);

        return Future.all(metrics, utilization, sprints, release).map(all -> new JsonObject()
                .put("metrics", metrics.result())
                .put("utilization", utilization.result())
                .put("sprints", ((JsonObject) sprints.result()).getJsonArray("sprints"))
                .put("release", ResultGuard.isNotApplicable(release.result()) ? null : release.result()));
    }

    // ------------------------------------------------------------------ work items parts

    private Future<JsonObject> liveWorkItems(JsonObject person, JsonObject withPeriod, String filter, String query, String sort)
    {
        Future<Object> live = calc.get(WorkloadFormulas.LIVE_SPRINT, person);

        Future<Object> issues = calc.get(WorkViewFormulas.LIVE_ISSUES, person);

        Future<Object> metrics = calc.get(WorkloadFormulas.SPRINT_METRICS, withPeriod);

        Future<Object> daily = calc.get(WorkViewFormulas.DAILY_LOAD, person);

        return Future.all(live, issues, metrics, daily).map(all ->
        {
            JsonObject sprint = (JsonObject) live.result();

            JsonObject list = (JsonObject) issues.result();

            JsonObject delivery = (JsonObject) metrics.result();

            List<JsonObject> items = list(list.getJsonArray("items"));

            Map<String, Predicate<JsonObject>> filters = Map.of(
                    "all", issue -> true,
                    "open", issue -> !"Done".equals(issue.getString("status")),
                    "stale", issue -> hasFlag(issue, "Stale") || hasFlag(issue, "Not started"),
                    "overdue", issue -> hasFlag(issue, "Overdue"),
                    "review", issue -> "In Review".equals(issue.getString("status")),
                    "done", issue -> "Done".equals(issue.getString("status")));

            JsonObject chips = new JsonObject();

            LIVE_FILTERS.forEach(f -> chips.put(f, (int) items.stream().filter(filters.get(f)).count()));

            JsonObject kpis = new JsonObject()
                    .put("committed", delivery.getValue("com"))
                    .put("issues", items.size())
                    .put("done", delivery.getValue("dsp"))
                    .put("commitment", delivery.getValue("commit"))
                    .put("remainingHours", sprint.getValue("rem"))
                    .put("workDays", sprint.getValue("work"))
                    .put("focus", sprint.getValue("focus"))
                    .put("elapsedPct", list.getValue("elapsedPct"))
                    .put("workingDaysLeft", list.getValue("workingDaysLeft"));

            return new JsonObject()
                    .put("kpis", kpis)
                    .put("chips", chips)
                    .put("items", select(items, filters.get(filter), query, sort))
                    .put("dailyLoad", daily.result());
        });
    }

    private Future<JsonObject> closedWorkItems(JsonObject withPeriod, String filter, String query, String sort)
    {
        Future<Object> metrics = calc.get(WorkloadFormulas.SPRINT_METRICS, withPeriod);

        Future<Object> utilization = calc.get(WorkloadFormulas.UTILIZATION, withPeriod);

        Future<Object> sprints = calc.get(WorkViewFormulas.SPRINT_BREAKDOWN, withPeriod);

        Future<Object> release = calc.get(WorkViewFormulas.RELEASE_CARD, withPeriod);

        Future<Object> issues = calc.get(WorkViewFormulas.PERIOD_ISSUES, withPeriod);

        return Future.all(List.of(metrics, utilization, sprints, release, issues)).map(all ->
        {
            List<JsonObject> items = list(((JsonObject) issues.result()).getJsonArray("items"));

            Map<String, Predicate<JsonObject>> filters = Map.of(
                    "all", issue -> true,
                    "done", issue -> "Done".equals(issue.getString("status")),
                    "carried", issue -> issue.getBoolean("carriedOver", false),
                    "bugs", issue -> "Bug".equals(issue.getString("issueType")),
                    "open", issue -> issue.getBoolean("live", false) && !"Done".equals(issue.getString("status")));

            JsonObject chips = new JsonObject();

            HISTORY_FILTERS.forEach(f -> chips.put(f, (int) items.stream().filter(filters.get(f)).count()));

            return new JsonObject()
                    .put("metrics", metrics.result())
                    .put("utilization", utilization.result())
                    .put("sprints", ((JsonObject) sprints.result()).getJsonArray("sprints"))
                    .put("release", ResultGuard.isNotApplicable(release.result()) ? null : release.result())
                    .put("chips", chips)
                    .put("items", select(items, filters.get(filter), query, sort));
        });
    }

    // ------------------------------------------------------------------ helpers

    private Future<Scope> visible(JsonObject user, String personId)
    {
        return policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON);
    }

    private Future<JsonObject> identity(Scope scope, String personId)
    {
        return new PersonRepository(ctx.sql()).identity(scope, UUID.fromString(personId));
    }

    /**
     * The period of a key, or of the active sprint (the current month when none) without one; a bad key
     * answers 400.
     */
    private Future<JsonObject> resolve(String personId, String key)
    {
        Future<String> chosen;

        if (key == null || key.isBlank())
        {
            chosen = calc.get(WorkViewFormulas.LIVE_ISSUES, new JsonObject().put("person", personId)).map(live ->
            {
                Object sprint = ((JsonObject) live).getValue("sprint");

                return sprint instanceof String s ? s : "m:" + YearMonth.from(calc.clock().today());
            });
        }
        else
        {
            chosen = Future.succeededFuture(key);
        }

        return chosen.compose(k -> calc.get("core.period", new JsonObject().put("period", k)))
                .map(period -> (JsonObject) period)
                .recover(error -> error instanceof IllegalArgumentException
                        ? Future.failedFuture(ApiException.badRequest("invalid_parameter", "Unknown period.").field("period"))
                        : Future.failedFuture(error));
    }

    /**
     * The person's index this quarter and at the end of the last closed quarter.
     */
    private Future<JsonObject> index(String personId)
    {
        LocalDate today = calc.clock().today();

        String quarter = "q:Q" + ((today.getMonthValue() - 1) / 3 + 1);

        Future<Object> current = calc.get("core.index", new JsonObject().put("person", personId).put("period", quarter));

        Future<Object> history = calc.get("core.indexHistory", new JsonObject().put("person", personId));

        return Future.all(current, history).map(all ->
        {
            Object previous = history.result() instanceof JsonArray h && !h.isEmpty() ? h.getValue(h.size() - 1) : null;

            return new JsonObject().put("value", current.result()).put("previous", previous);
        });
    }

    private static boolean isLiveSprint(JsonObject period)
    {
        return "sprint".equals(period.getString("kind")) && period.getBoolean("live", false);
    }

    private static boolean hasFlag(JsonObject issue, String label)
    {
        return issue.getJsonArray("flags", new JsonArray()).stream().anyMatch(f -> label.equals(((JsonArray) f).getString(1)));
    }

    private static JsonArray select(List<JsonObject> items, Predicate<JsonObject> filter, String query, String sort)
    {
        String q = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);

        List<JsonObject> chosen = items.stream()
                .filter(filter)
                .filter(issue -> q.isEmpty() || (issue.getString("key", "") + " " + issue.getString("summary", ""))
                        .toLowerCase(Locale.ROOT).contains(q))
                .toList();

        return new JsonArray(new ArrayList<Object>(sorted(chosen, sort)));
    }

    /**
     * The list in the order of {@code sort} ({@code -} first for descending), ties by key.
     */
    private static List<JsonObject> sorted(List<JsonObject> items, String sort)
    {
        boolean descending = sort.startsWith("-");

        String key = descending ? sort.substring(1) : sort;

        Comparator<JsonObject> by = switch (key)
        {
            case "summary" -> Comparator.comparing((JsonObject i) -> i.getString("summary", ""));
            case "status" -> Comparator.comparingInt((JsonObject i) -> STATUS_ORDER.indexOf(i.getString("status")));
            case "sp" -> Comparator.comparingDouble((JsonObject i) -> number(i, "storyPoints"));
            case "remaining" -> Comparator.comparingDouble((JsonObject i) -> number(i, "remainingHours"));
            case "due" -> Comparator.comparing((JsonObject i) -> i.getString("dueOn", "9999-12-31"));
            case "last" -> Comparator.comparing((JsonObject i) -> i.getString("lastActivity", ""));
            case "signals" -> Comparator.comparingInt((JsonObject i) -> i.getJsonArray("flags", new JsonArray()).size());
            case "resolved" -> Comparator.comparing((JsonObject i) -> i.getString("resolvedOn", "9999-12-31"));
            default -> Comparator.comparing((JsonObject i) -> i.getString("key"));
        };

        Comparator<JsonObject> ordered = descending ? by.reversed() : by;

        List<JsonObject> out = new ArrayList<>(items);

        out.sort(ordered.thenComparing(i -> i.getString("key")));

        return out;
    }

    private static double number(JsonObject issue, String key)
    {
        Object value = issue.getValue(key);

        return value instanceof Number n ? n.doubleValue() : 0;
    }

    private static List<JsonObject> list(JsonArray rows)
    {
        List<JsonObject> out = new ArrayList<>();

        rows.forEach(row -> out.add((JsonObject) row));

        return out;
    }
}
