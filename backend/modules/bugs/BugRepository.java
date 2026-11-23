package com.pulse.modules.bugs;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Bugs and support tickets (tasks 7.3 and 12.1), read through {@code bugs_current} and
 * {@code support_tickets_current}: the synced rows with Pulse's own decisions applied (V240). Bugs are
 * scoped by the code owner they are attributed to, and also visible to whoever is fixing them (the
 * assignee owner column), so "Take it" and "Pick up" can show a bug in someone else's code. Support
 * tickets are scoped by the person who took them; the unassigned queue is not personal data. Every
 * personal read takes a {@link Scope} ({@code person} domain). The synced tables are written only by
 * {@code IngestService} (task 5.4); Pulse's decisions go to {@code bug_work}, {@code bug_disputes} and
 * {@code ticket_work}.
 */
public class BugRepository extends ScopedRepository
{

    private static final String BUG = """
            SELECT b.id, b.key, b.title, b.severity, b.status, b.environment,
                   b.origin_person_id AS "originPersonId", b.attributed_person_id AS "attributedPersonId",
                   b.origin_ref AS "originRef", b.attribution_method AS "attributionMethod", b.found_by AS "foundBy",
                   b.assignee_id AS "assigneeId", b.fixed_by_id AS "fixedById", b.proactive,
                   b.reported_on AS "reportedOn", b.resolved_on AS "resolvedOn", b.open_dispute_id AS "openDisputeId",
                   (SELECT a.name FROM people a WHERE a.id = b.assignee_id) AS "assigneeName", b.source
            FROM bugs_current b
            """;

    private static final String TICKET = """
            SELECT t.id, t.key, t.title, t.customer, t.priority, t.person_id AS "personId",
                   t.how_taken AS "howTaken", t.hours, t.status, t.opened_on AS "openedOn",
                   t.resolved_on AS "resolvedOn", t.csat, t.resolution_note AS "resolutionNote", t.source
            FROM support_tickets_current t
            """;

    private static final String DISPUTE = """
            SELECT d.id, d.bug_id AS "bugId", b.key AS "bugKey", b.title AS "bugTitle", b.origin_ref AS "originRef",
                   d.person_id AS "personId", p.name AS "personName", d.reason, d.raised_at AS "raisedAt",
                   d.decision, d.decided_at AS "decidedAt", d.version
            FROM bug_disputes d JOIN bugs b ON b.id = d.bug_id JOIN people p ON p.id = d.person_id
            """;

    public BugRepository(SqlExecutor sql)
    {
        super(sql);
    }

    // ------------------------------------------------------------------ bugs

    /**
     * Bugs attributed to a person's code, newest first.
     */
    public Future<JsonArray> inCodeOf(Scope scope, UUID personId)
    {
        return scopedList(scope, "b.origin_person_id",
                BUG + " WHERE b.origin_person_id = $1 AND {scope} ORDER BY b.reported_on DESC, b.key",
                Tuple.of(personId));
    }

    /**
     * Bugs a person fixed or is fixing in someone else's code ("Bugs fixed for others"), newest first.
     */
    public Future<JsonArray> fixedForOthersBy(Scope scope, UUID personId)
    {
        return scopedList(scope, "b.fixed_by_id", "b.assignee_id", BUG + """
                 WHERE (b.fixed_by_id = $1 OR (b.assignee_id = $1 AND b.status <> 'Resolved'))
                   AND b.origin_person_id IS DISTINCT FROM $1 AND {scope}
                ORDER BY b.resolved_on DESC NULLS FIRST, b.key""", Tuple.of(personId));
    }

    /**
     * Open bugs a person could pick up: in the code of someone else on their team, not assigned to them.
     * The owner's name is left out unless the scope sees the owner ({@code ownerVisible}).
     */
    public Future<JsonArray> toPickUp(Scope scope, UUID personId)
    {
        Future<JsonArray> bugs = queryList("""
                SELECT b.key, b.title, b.severity, b.status, b.assignee_id AS "assigneeId", a.name AS "assigneeName",
                       b.origin_person_id AS "originPersonId", o.name AS "ownerName", b.reported_on AS "reportedOn"
                FROM bugs_current b
                JOIN people o ON o.id = b.origin_person_id
                JOIN people me ON me.id = $1 AND me.department IS NOT DISTINCT FROM o.department
                LEFT JOIN people a ON a.id = b.assignee_id
                WHERE b.status <> 'Resolved' AND b.origin_person_id <> $1 AND b.assignee_id IS DISTINCT FROM $1
                ORDER BY b.reported_on, b.key""", Tuple.of(personId));

        Future<JsonArray> visible = scopedList(scope, "p.id", "SELECT p.id FROM people p WHERE {scope}", Tuple.tuple());

        return Future.all(bugs, visible).map(all ->
        {
            List<String> ids = visible.result().stream().map(v -> ((JsonObject) v).getString("id")).toList();

            JsonArray out = new JsonArray();

            for (Object item : bugs.result())
            {
                JsonObject bug = ((JsonObject) item).copy();

                boolean seen = ids.contains(bug.getString("originPersonId"));

                bug.put("ownerVisible", seen);

                if (!seen)
                {
                    bug.putNull("originPersonId").putNull("ownerName");
                }

                if (bug.getString("assigneeId") != null && !ids.contains(bug.getString("assigneeId")))
                {
                    bug.putNull("assigneeId").putNull("assigneeName");
                }

                out.add(bug.put("assigned", ((JsonObject) item).getString("assigneeId") != null));
            }

            return out;
        });
    }

    /**
     * One bug by key, visible to whoever may see its code owner and to the person fixing it.
     */
    public Future<JsonObject> byKey(Scope scope, String key)
    {
        return scopedOne(scope, "b.origin_person_id", "b.assignee_id",
                BUG + " WHERE b.key = $1 AND {scope} ORDER BY b.reported_on DESC, b.id LIMIT 1", Tuple.of(key));
    }

    /**
     * One bug by key with no visibility filter: for the actions that decide access themselves (pick up a
     * bug on the team), locking the bug until the transaction ends.
     */
    public Future<JsonObject> lockByKey(String key)
    {
        return queryOne("SELECT id FROM bugs WHERE key = $1 ORDER BY reported_on DESC, id LIMIT 1 FOR UPDATE", Tuple.of(key))
                .compose(row -> row == null ? Future.succeededFuture(null)
                        : queryOne("SELECT c.*, o.department AS \"ownerTeam\" FROM (" + BUG + " WHERE b.id = $1) c"
                                + " LEFT JOIN people o ON o.id = c.\"originPersonId\"", Tuple.of(UUID.fromString(row.getString("id")))));
    }

    /**
     * Records who is fixing a bug ({@code proactive} for a pick-up) or that it was resolved today.
     */
    public Future<Void> saveWork(UUID bugId, UUID assigneeId, boolean proactive, String status, LocalDate resolvedOn, UUID by)
    {
        return execute("""
                INSERT INTO bug_work (bug_id, assignee_id, proactive, status, resolved_on, updated_by)
                VALUES ($1, $2, $3, $4, $5, $6)
                ON CONFLICT (bug_id) DO UPDATE SET assignee_id = $2, proactive = $3, status = $4, resolved_on = $5,
                    updated_by = $6, updated_at = now(), version = bug_work.version + 1""",
                Tuple.of(bugId, assigneeId, proactive, status, resolvedOn, by)).mapEmpty();
    }

    /**
     * The people on the Team tab: those in scope, without the viewer, by name.
     */
    public Future<JsonArray> teamMembers(Scope scope, UUID viewer)
    {
        return scopedList(scope, "p.id", """
                SELECT p.id, p.name FROM people p
                WHERE p.user_id IS DISTINCT FROM $1 AND {scope}
                ORDER BY p.name, p.id""", Tuple.of(viewer));
    }

    // ------------------------------------------------------------------ disputes

    /**
     * Opens a dispute; resolves to the dispute.
     */
    public Future<JsonObject> openDispute(UUID bugId, UUID personId, String reason, UUID raisedBy)
    {
        return queryOne("""
                INSERT INTO bug_disputes (bug_id, person_id, reason, raised_by) VALUES ($1, $2, $3, $4)
                RETURNING id""", Tuple.of(bugId, personId, reason, raisedBy))
                .compose(row -> dispute(UUID.fromString(row.getString("id"))));
    }

    /**
     * One dispute, visible when its code owner is, locked until the transaction ends; or {@code null}.
     */
    public Future<JsonObject> lockDispute(Scope scope, UUID id)
    {
        return scopedOne(scope, "d.person_id", DISPUTE + " WHERE d.id = $1 AND {scope} FOR UPDATE OF d", Tuple.of(id));
    }

    /**
     * Records the manager's decision ({@code keep} or {@code remove}) on the version they saw.
     */
    public Future<JsonObject> decideDispute(UUID id, String decision, UUID by, int expectedVersion)
    {
        return updateVersioned("bug_disputes", "decision = $1, decided_by = $2, decided_at = now()",
                Tuple.of(decision, by), id, expectedVersion)
                .compose(row -> dispute(id));
    }

    /**
     * Open disputes of the people in scope ("Disputes to decide"), oldest first.
     */
    public Future<JsonArray> openDisputes(Scope scope)
    {
        return scopedList(scope, "d.person_id", DISPUTE + " WHERE d.decision IS NULL AND {scope} ORDER BY d.raised_at, d.id",
                Tuple.tuple());
    }

    private Future<JsonObject> dispute(UUID id)
    {
        return queryOne(DISPUTE + " WHERE d.id = $1", Tuple.of(id));
    }

    // ------------------------------------------------------------------ support tickets

    /**
     * Support tickets a person took, newest first.
     */
    public Future<JsonArray> ticketsOf(Scope scope, UUID personId)
    {
        return scopedList(scope, "t.person_id",
                TICKET + " WHERE t.person_id = $1 AND {scope} ORDER BY t.opened_on DESC, t.key", Tuple.of(personId));
    }

    /**
     * Open tickets nobody has taken ("Support queue"): not personal data, so no scope.
     */
    public Future<JsonArray> ticketQueue()
    {
        return queryList(TICKET + " WHERE t.person_id IS NULL AND t.status = 'Open' ORDER BY t.opened_on, t.key",
                Tuple.tuple());
    }

    /**
     * One ticket by key, locked until the transaction ends; {@code null} when there is none.
     */
    public Future<JsonObject> lockTicket(String key)
    {
        return queryOne("SELECT id FROM support_tickets WHERE key = $1 ORDER BY opened_on DESC, id LIMIT 1 FOR UPDATE", Tuple.of(key))
                .compose(row -> row == null ? Future.succeededFuture(null)
                        : queryOne(TICKET + " WHERE t.id = $1", Tuple.of(UUID.fromString(row.getString("id")))));
    }

    /**
     * Takes a ticket ({@code Open}) or resolves it with hours and a note.
     */
    public Future<Void> saveTicket(UUID ticketId, UUID personId, String howTaken, String status, BigDecimal hours, String note,
                                   LocalDate resolvedOn, UUID by)
    {
        return execute("""
                INSERT INTO ticket_work (ticket_id, person_id, how_taken, status, hours, resolution_note, resolved_on, updated_by)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
                ON CONFLICT (ticket_id) DO UPDATE SET person_id = $2, how_taken = $3, status = $4, hours = $5,
                    resolution_note = $6, resolved_on = $7, updated_by = $8, updated_at = now(),
                    version = ticket_work.version + 1""",
                Tuple.of(ticketId, personId, howTaken, status, hours, note).addValue(resolvedOn).addValue(by)).mapEmpty();
    }

    // ------------------------------------------------------------------ extra-mile items

    /**
     * Adds a pending extra-mile item awaiting a manager (task 12.1: a resolved pick-up or ticket); a second
     * add of the same detection key is ignored. Resolves to whether one was added.
     */
    public Future<Boolean> addPendingItem(JsonObject item)
    {
        return queryOne("""
                INSERT INTO extra_items (detection_key, person_id, kind, ref, title, requested_by, detection_note,
                                         evidence_source, occurred_on, hours, is_done, values_credited)
                VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, true, $11)
                ON CONFLICT (detection_key) DO NOTHING
                RETURNING id""",
                Tuple.of(item.getString("detectionKey"), UUID.fromString(item.getString("personId")), item.getString("kind"),
                                item.getString("ref"), item.getString("title"), item.getString("requestedBy"))
                        .addValue(item.getString("detectionNote"))
                        .addValue(item.getString("evidenceSource"))
                        .addValue(LocalDate.parse(item.getString("occurredOn")))
                        .addValue(new BigDecimal(String.valueOf(item.getValue("hours", 0))))
                        .addValue(item.getJsonArray("values").stream().map(String::valueOf).toArray(String[]::new)))
                .map(row -> row != null);
    }
}
