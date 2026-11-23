package com.pulse.platform.notify;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * In-app notifications (task 5.3, spec platform/notifications): create, list, read, read all.
 *
 * <p>Modules never write the table themselves. They build a notice with {@link NotificationTriggers} and
 * pass it to {@link #notify(SqlExecutor, JsonObject)} with the executor of their own transaction, so the
 * notification commits or rolls back with the action. Two rules are enforced here:
 *
 * <ul>
 *   <li><b>Privacy</b> ("Notification privacy"): the text never carries a salary value, 360° comment text or
 *       a reviewer's identity. Trigger helpers build the text from names, titles and dates only, and
 *       {@link #checkText(String)} refuses text with a currency amount or salary words. The link is an
 *       in-app path, never an external URL.</li>
 *   <li><b>Visibility</b>: a notice about a person ({@code aboutPersonId}) is delivered only when the
 *       recipient may see that person in the notice's policy domain; otherwise it is skipped. A
 *       deactivated or deleted recipient gets nothing.</li>
 * </ul>
 *
 * <p>Notice shape: {@code {userId, kind, text, link, aboutPersonId?, domain?}}.
 */
public final class NotificationService
{
    /** Longest notification text; the bell shows it on two lines at most. */
    public static final int MAX_TEXT = 300;

    /** Default and largest page of {@link #list(JsonObject, Integer)}. */
    public static final int DEFAULT_LIMIT = 20;

    public static final int MAX_LIMIT = 50;

    /** Currency amounts and salary words (CTC, salary, increment amount); notifications never carry pay. */
    private static final Pattern SALARY = Pattern.compile(
            "[₹$€£]|\\b(?:inr|rs\\.?|ctc|lpa|salary|salaries|lakh|lakhs|crore)\\b", Pattern.CASE_INSENSITIVE);

    private final SqlExecutor sql;

    private final PolicyService policy;

    public NotificationService(SqlExecutor sql, PolicyService policy)
    {
        this.sql = sql;
        this.policy = policy;
    }

    /**
     * Delivers {@code notice} in the caller's transaction {@code tx}. Resolves to the stored notification,
     * or to null when it was skipped (recipient inactive or not allowed to see {@code aboutPersonId}).
     * Fails with {@link IllegalArgumentException} on a malformed notice or text that breaks the privacy rule,
     * which is a programming error in the caller.
     */
    public Future<JsonObject> notify(SqlExecutor tx, JsonObject notice)
    {
        UUID userId;

        try
        {
            userId = UUID.fromString(require(notice, "userId"));
            require(notice, "kind");
            checkText(notice.getString("text"));
            checkLink(notice.getString("link"));
        }
        catch (IllegalArgumentException e)
        {
            return Future.failedFuture(e);
        }

        NotificationRepository repo = new NotificationRepository(tx);

        return repo.recipient(userId).compose(recipient ->
        {
            if (recipient == null || "Deactivated".equals(recipient.getString("status")))
            {
                return Future.succeededFuture(false);
            }

            return maySee(recipient, notice);
        }).compose(allowed ->
        {
            if (!allowed)
            {
                return Future.succeededFuture(null);
            }

            return repo.insert(userId, notice.getString("kind"), notice.getString("text").strip(), notice.getString("link"))
                    .map(NotificationService::item);
        });
    }

    /** {@code {items: [{id, kind, text, link, createdAt, read}], unread}} for the signed-in user, newest first. */
    public Future<JsonObject> list(JsonObject user, Integer limit)
    {
        UUID userId = UUID.fromString(user.getString("id"));
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        NotificationRepository repo = new NotificationRepository(sql);

        Future<JsonArray> items = repo.list(userId, size);
        Future<Integer> unread = repo.unread(userId);

        return Future.all(items, unread).map(all ->
        {
            JsonArray out = new JsonArray();

            items.result().forEach(row -> out.add(item((JsonObject) row)));

            return new JsonObject().put("items", out).put("unread", unread.result());
        });
    }

    /** Marks one of the user's notifications read: {@code {unread}}; false when it is not theirs (the caller answers 404). */
    public Future<JsonObject> markRead(JsonObject user, UUID id)
    {
        UUID userId = UUID.fromString(user.getString("id"));
        NotificationRepository repo = new NotificationRepository(sql);

        return repo.markRead(userId, id).compose(n -> n == 0
                ? Future.succeededFuture(null)
                : repo.unread(userId).map(unread -> new JsonObject().put("unread", unread)));
    }

    /** Mark all read: {@code {unread: 0, marked}}. */
    public Future<JsonObject> markAllRead(JsonObject user)
    {
        return new NotificationRepository(sql).markAllRead(UUID.fromString(user.getString("id")))
                .map(n -> new JsonObject().put("unread", 0).put("marked", n));
    }

    /** Unread count of the user (the bell badge). */
    public Future<Integer> unread(JsonObject user)
    {
        return new NotificationRepository(sql).unread(UUID.fromString(user.getString("id")));
    }

    // ------------------------------------------------------------------ rules

    /**
     * The privacy rule for notification text: non-blank, at most {@link #MAX_TEXT} characters, no
     * currency amount or salary word.
     */
    public static void checkText(String text)
    {
        if (text == null || text.isBlank())
        {
            throw new IllegalArgumentException("notification text is required");
        }

        if (text.length() > MAX_TEXT)
        {
            throw new IllegalArgumentException("notification text is longer than " + MAX_TEXT + " characters");
        }

        if (SALARY.matcher(text.toLowerCase(Locale.ROOT)).find())
        {
            throw new IllegalArgumentException("notification text must not carry salary values");
        }
    }

    /** Links point into Pulse ({@code /screen?...}); external URLs and protocol-relative paths are refused. */
    static void checkLink(String link)
    {
        if (link == null)
        {
            return;
        }

        if (!link.startsWith("/") || link.startsWith("//") || link.contains("\\") || link.length() > 300)
        {
            throw new IllegalArgumentException("notification link must be an in-app path");
        }
    }

    private Future<Boolean> maySee(JsonObject recipient, JsonObject notice)
    {
        String about = notice.getString("aboutPersonId");

        if (about == null)
        {
            return Future.succeededFuture(true);
        }

        if (about.equals(recipient.getString("person_id")))
        {
            return Future.succeededFuture(true);
        }

        JsonArray permissions = recipient.getJsonArray("permissions", new JsonArray()).copy();

        // the built-in Admin role holds the whole catalogue (AuthService.sessionUser)
        if ("Admin".equals(recipient.getString("role")) && !permissions.contains("all.view"))
        {
            permissions.add("all.view");
        }

        JsonObject user = new JsonObject()
                .put("id", recipient.getString("id"))
                .put("personId", recipient.getString("person_id"))
                .put("permissions", permissions);

        String domain = notice.getString("domain", PolicyService.DOMAIN_PERSON);

        return policy.scope(user, domain).map(scope -> scope.all() || scope.allows(about));
    }

    private static String require(JsonObject notice, String field)
    {
        String value = notice == null ? null : notice.getString(field);

        if (value == null || value.isBlank())
        {
            throw new IllegalArgumentException("notice." + field + " is required");
        }

        return value;
    }

    private static JsonObject item(JsonObject row)
    {
        return new JsonObject()
                .put("id", row.getString("id"))
                .put("kind", row.getString("kind"))
                .put("text", row.getString("text"))
                .put("link", row.getString("link"))
                .put("createdAt", row.getString("created_at"))
                .put("read", row.getValue("read_at") != null);
    }
}
