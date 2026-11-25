package com.pulse.modules.connectors.jira;

import com.pulse.platform.ingest.IngestService;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps Jira Server REST v2 answers onto Pulse's ingest records (task 26.2, change
 * pulse-v2-live-connectors design.md decision 6). Pure functions over {@link JsonObject}: no I/O, no
 * model classes. {@link JiraConnector} calls them for every issue of a search page.
 *
 * <ul>
 *   <li>Issue key is the {@code external_id}; the assignee's username goes to
 *       {@code assignee_account_id} and the person is resolved at read time (V290).</li>
 *   <li>{@code statusCategory.key} maps onto the seed statuses; an in-progress status whose name says
 *       "review" maps to In Review.</li>
 *   <li>Estimates are seconds in Jira and hours in Pulse.</li>
 *   <li>The sprint field is parsed from Jira Server's string form or the object form.</li>
 *   <li>Changelog items, comments and creation become {@code issue_events}; authors go to
 *       {@code actor_account_id} (V292).</li>
 * </ul>
 */
final class JiraMapper
{
    /** Jira's date-time, e.g. {@code 2026-11-25T16:48:21.000+0530}. */
    static final DateTimeFormatter JIRA_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .appendOffset("+HHMM", "Z")
            .toFormatter(Locale.ROOT);

    /** JQL's date-time, minute precision, in the offset of the cursor. */
    static final DateTimeFormatter JQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    private static final Pattern SPRINT_STRING = Pattern.compile("^[\\w.$]+@\\w+\\[(.*)]$", Pattern.DOTALL);

    private static final Pattern SPRINT_PAIR = Pattern.compile("(\\w+)=(.*?)(?=,\\w+=|$)", Pattern.DOTALL);

    private static final Map<String, String> CATEGORY = Map.of(
            "new", "To Do",
            "indeterminate", "In Progress",
            "done", "Done");

    private static final double SECONDS_PER_HOUR = 3600.0;

    private static final int SUMMARY_MAX = 200;

    private JiraMapper()
    {
    }

    // ------------------------------------------------------------------ time

    /** A Jira date-time as an ISO-8601 offset date-time, or null. */
    static OffsetDateTime time(String jira)
    {
        if (jira == null || jira.isBlank())
        {
            return null;
        }

        try
        {
            return OffsetDateTime.parse(jira, JIRA_TIME);
        }
        catch (RuntimeException e)
        {
            return OffsetDateTime.parse(jira);
        }
    }

    /** The ISO-8601 form stored as cursor and in timestamp columns. */
    static String iso(String jira)
    {
        OffsetDateTime t = time(jira);

        return t == null ? null : t.toString();
    }

    /** The JQL bound of an incremental run: the cursor minus one minute, minute precision. */
    static String jqlSince(String cursor)
    {
        return OffsetDateTime.parse(cursor).minusMinutes(1).format(JQL_TIME);
    }

    // ------------------------------------------------------------------ issues

    /** The seed status of a Jira status: category first, "review" names as In Review. */
    static String status(JsonObject status)
    {
        if (status == null)
        {
            return "To Do";
        }

        String category = status.getJsonObject("statusCategory", new JsonObject()).getString("key", "new");

        String mapped = CATEGORY.getOrDefault(category, "In Progress");

        String name = status.getString("name", "");

        if ("In Progress".equals(mapped) && name.toLowerCase(Locale.ROOT).contains("review"))
        {
            return "In Review";
        }

        return mapped;
    }

    /** Seconds to hours, two decimals; null stays null. */
    static Double hours(Object seconds)
    {
        if (!(seconds instanceof Number n))
        {
            return null;
        }

        return Math.round(n.doubleValue() / SECONDS_PER_HOUR * 100.0) / 100.0;
    }

    /** The configured story points field as a number, or null when absent, empty or not numeric. */
    static Double storyPoints(JsonObject fields, String fieldId)
    {
        if (fieldId == null || fieldId.isBlank())
        {
            return null;
        }

        Object value = fields.getValue(fieldId);

        return value instanceof Number n ? n.doubleValue() : null;
    }

    /**
     * The {@code issues} record of one search hit. {@code sprint} is the sprint row it belongs to (see
     * {@link #sprints}), or null.
     */
    static JsonObject issue(JsonObject hit, String baseUrl, String storyPointsField, JsonObject sprint, int sprintCount)
    {
        JsonObject fields = hit.getJsonObject("fields", new JsonObject());

        String key = hit.getString("key");

        String status = status(fields.getJsonObject("status"));

        JsonObject assignee = fields.getJsonObject("assignee");

        String resolved = date(fields.getString("resolutiondate"));

        String started = "To Do".equals(status) ? null : firstStatusChange(hit);

        if (started != null && resolved != null && started.compareTo(resolved) > 0)
        {
            started = resolved;
        }

        Double spent = hours(fields.getValue("timespent"));

        return new JsonObject()
                .put("external_id", key)
                .put("key", key)
                .put("summary", fields.getString("summary", key))
                .put("issue_type", fields.getJsonObject("issuetype", new JsonObject()).getString("name", "Story"))
                .put("status", status)
                .put("assignee_id", null)
                .put("assignee_account_id", assignee == null ? null : assignee.getString("name"))
                .put("sprint_id", sprint == null ? null : ref("sprints", sprint.getString("external_id")))
                .put("story_points", storyPoints(fields, storyPointsField))
                .put("estimate_hours", hours(fields.getValue("timeoriginalestimate")))
                .put("spent_hours", spent == null ? 0.0 : spent)
                .put("remaining_hours", hours(fields.getValue("timeestimate")))
                .put("due_on", fields.getString("duedate"))
                .put("started_on", started)
                .put("resolved_on", resolved)
                .put("carried_over", sprintCount > 1)
                .put("reopened", false)
                .put("was_stale", false)
                .put("html_url", baseUrl + "/browse/" + key);
    }

    /** The date of the first status change in the changelog, or null. */
    private static String firstStatusChange(JsonObject hit)
    {
        String first = null;

        for (Object h : histories(hit))
        {
            JsonObject history = (JsonObject) h;

            for (Object i : history.getJsonArray("items", new JsonArray()))
            {
                if ("status".equalsIgnoreCase(((JsonObject) i).getString("field")))
                {
                    String day = date(history.getString("created"));

                    if (day != null && (first == null || day.compareTo(first) < 0))
                    {
                        first = day;
                    }
                }
            }
        }

        return first;
    }

    private static JsonArray histories(JsonObject hit)
    {
        return hit.getJsonObject("changelog", new JsonObject()).getJsonArray("histories", new JsonArray());
    }

    /** The calendar date of a Jira date or date-time in its own offset, or null. */
    static String date(String jira)
    {
        if (jira == null || jira.isBlank())
        {
            return null;
        }

        if (jira.length() == 10)
        {
            return jira;
        }

        return time(jira).toLocalDate().toString();
    }

    // ------------------------------------------------------------------ sprints

    /**
     * The sprints of an issue's sprint field, in Jira's order (oldest first), each as
     * {@code {id, name, state, startDate, endDate}}; both the string and the object forms are read.
     */
    static JsonArray sprints(JsonObject fields, String sprintField)
    {
        JsonArray out = new JsonArray();

        if (sprintField == null || !(fields.getValue(sprintField) instanceof JsonArray values))
        {
            return out;
        }

        for (Object value : values)
        {
            JsonObject sprint = value instanceof JsonObject o ? o : value instanceof String s ? parseSprint(s) : null;

            if (sprint != null && sprint.getValue("id") != null)
            {
                out.add(new JsonObject()
                        .put("id", String.valueOf(sprint.getValue("id")))
                        .put("name", sprint.getString("name"))
                        .put("state", sprint.getString("state"))
                        .put("startDate", blank(sprint.getString("startDate")))
                        .put("endDate", blank(sprint.getString("endDate"))));
            }
        }

        return out;
    }

    /** Jira Server's {@code ...Sprint@1a2b[id=147,name=...,state=ACTIVE,startDate=...,...]}. */
    static JsonObject parseSprint(String text)
    {
        Matcher outer = SPRINT_STRING.matcher(text.strip());

        if (!outer.matches())
        {
            return null;
        }

        JsonObject sprint = new JsonObject();

        Matcher pair = SPRINT_PAIR.matcher(outer.group(1));

        while (pair.find())
        {
            String value = pair.group(2);

            sprint.put(pair.group(1), "<null>".equals(value) ? null : value);
        }

        return sprint;
    }

    private static String blank(String value)
    {
        return value == null || value.isBlank() || "<null>".equals(value) ? null : value;
    }

    /**
     * The {@code sprints} record of a parsed sprint, or null when it has no dates yet (a future sprint):
     * the table needs a start and an end. {@code ends_on} is the Sunday on or after the last working day,
     * as for seeded sprints.
     */
    static JsonObject sprintRecord(JsonObject sprint)
    {
        String start = date(sprint.getString("startDate"));

        String end = date(sprint.getString("endDate"));

        if (start == null || end == null || end.compareTo(start) < 0)
        {
            return null;
        }

        String id = sprint.getString("id");

        LocalDate workEnd = LocalDate.parse(end);

        return new JsonObject()
                .put("external_id", id)
                .put("key", "S" + id)
                .put("number", Integer.parseInt(id))
                .put("starts_on", start)
                .put("work_ends_on", end)
                .put("ends_on", workEnd.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).toString());
    }

    // ------------------------------------------------------------------ events

    /**
     * The {@code issue_events} records of one search hit: creation, every changelog item, every comment
     * and, with the bot switch off, Jira's {@code updated} as an activity of the assignee.
     */
    static JsonArray events(JsonObject hit, boolean ignoreBotEdits)
    {
        JsonObject fields = hit.getJsonObject("fields", new JsonObject());

        String key = hit.getString("key");

        JsonArray out = new JsonArray();

        String created = iso(fields.getString("created"));

        if (created != null)
        {
            JsonObject creator = fields.getJsonObject("creator", fields.getJsonObject("reporter"));

            out.add(event(key, "created", created, "created", creator, "Created"));
        }

        for (Object h : histories(hit))
        {
            JsonObject history = (JsonObject) h;

            String at = iso(history.getString("created"));

            JsonArray items = history.getJsonArray("items", new JsonArray());

            for (int i = 0; i < items.size(); i++)
            {
                JsonObject item = items.getJsonObject(i);

                String field = item.getString("field", "");

                String kind = kind(field);

                out.add(event(key, "h" + history.getString("id") + "." + i, at, kind, history.getJsonObject("author"),
                        summary(kind, field, item.getString("toString")))
                        .put("field", field)
                        .put("from_value", item.getString("fromString"))
                        .put("to_value", item.getString("toString")));
            }
        }

        JsonArray comments = fields.getJsonObject("comment", new JsonObject()).getJsonArray("comments", new JsonArray());

        for (Object c : comments)
        {
            JsonObject comment = (JsonObject) c;

            out.add(event(key, "c" + comment.getString("id"), iso(comment.getString("created")), "comment",
                    comment.getJsonObject("author"), "Comment")
                    .put("to_value", comment.getString("body")));
        }

        JsonObject assignee = fields.getJsonObject("assignee");

        String updated = iso(fields.getString("updated"));

        if (!ignoreBotEdits && assignee != null && updated != null)
        {
            out.add(event(key, "updated", updated, "transition", assignee, "Updated in Jira").put("field", "updated"));
        }

        return out;
    }

    /** The event kind of a changelog field. */
    static String kind(String field)
    {
        return switch (field.toLowerCase(Locale.ROOT))
        {
            case "status" -> "transition";
            case "assignee" -> "assignee_change";
            case "sprint" -> "sprint_change";
            case "worklogid", "timespent" -> "worklog";
            default -> "field_change";
        };
    }

    private static String summary(String kind, String field, String to)
    {
        String text = switch (kind)
        {
            case "transition" -> "Status → " + (to == null ? "" : to);
            case "assignee_change" -> to == null ? "Unassigned" : "Assigned to " + to;
            case "sprint_change" -> to == null || to.isBlank() ? "Removed from sprint" : "Sprint → " + to;
            case "worklog" -> "Worklog";
            default -> "Field “" + field + "” edited";
        };

        return text.length() > SUMMARY_MAX ? text.substring(0, SUMMARY_MAX) : text;
    }

    private static JsonObject event(String key, String id, String at, String kind, JsonObject author, String summary)
    {
        return new JsonObject()
                .put("external_id", key + "|" + id)
                .put("issue_id", ref("issues", key))
                .put("occurred_at", at)
                .put("kind", kind)
                .put("actor_id", null)
                .put("actor_account_id", author == null ? null : author.getString("name"))
                .put("actor_name", author == null ? "Jira" : author.getString("displayName", author.getString("name", "Jira")))
                .put("is_bot", false)
                .put("summary", summary);
    }

    // ------------------------------------------------------------------ accounts

    /** The {@code resolve} input of a Jira user, or null: the username is the account id. */
    static JsonObject account(JsonObject user)
    {
        if (user == null || user.getString("name") == null)
        {
            return null;
        }

        return new JsonObject()
                .put("accountId", user.getString("name"))
                .put("login", user.getString("name"))
                .put("displayName", user.getString("displayName"))
                .put("email", user.getString("emailAddress"));
    }

    /** Every user an issue mentions that Pulse matches to a person: assignee, changelog and comment authors. */
    static JsonArray users(JsonObject hit)
    {
        JsonObject fields = hit.getJsonObject("fields", new JsonObject());

        JsonArray out = new JsonArray();

        out.add(fields.getJsonObject("assignee"));

        histories(hit).forEach(h -> out.add(((JsonObject) h).getJsonObject("author")));

        fields.getJsonObject("comment", new JsonObject()).getJsonArray("comments", new JsonArray())
                .forEach(c -> out.add(((JsonObject) c).getJsonObject("author")));

        return out;
    }

    static JsonObject ref(String entity, String externalId)
    {
        return new JsonObject().put(IngestService.REF, entity).put("external_id", externalId);
    }
}
