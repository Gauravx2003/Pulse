package com.pulse.modules.bugs;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.work.EffortFormulas;
import com.pulse.calc.work.WorkloadFormulas;
import com.pulse.modules.work.PersonRepository;
import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bugs & support (task 12.1, spec work/bugs-support): the Person and Team views and the actions on them.
 * Attribution comes from the synced data (a caused-by link wins over blame) and is a learning signal,
 * never blame. A code owner disputes with a reason; their manager keeps it or removes it from the
 * owner's count (audited, separation of duties). The owner takes the fix of a bug in their code, anyone
 * picks up an open bug in a teammate's code (proactive), the person fixing it marks it resolved. Anyone
 * takes a queue ticket and resolves it with hours and a note. A resolved pick-up (1.5 points) or ticket
 * (1 point, rule {@code pts}) becomes an extra-mile item awaiting a manager's confirmation (task 11.2).
 */
public final class BugService
{

    public static final String DISPUTE_RAISED = "bugs.dispute.raised";

    public static final String FIX_TAKEN = "bugs.fix.taken";

    public static final String FIX_PICKED_UP = "bugs.fix.picked_up";

    public static final String FIX_RESOLVED = "bugs.fix.resolved";

    public static final String TICKET_TAKEN = "support.ticket.taken";

    public static final String TICKET_RESOLVED = "support.ticket.resolved";

    public static final List<String> AUDIT_EVENTS = List.of(DISPUTE_RAISED, FIX_TAKEN, FIX_PICKED_UP, FIX_RESOLVED,
            TICKET_TAKEN, TICKET_RESOLVED);

    /**
     * The subtitle of the open-bugs list (spec "Copy check").
     */
    public static final String LEARNING_SIGNAL = "Attributed when the fixing PR changes lines last written by this person,"
            + " or via a Jira “caused by” link · a learning signal, not blame";

    static final int MIN_REASON = 10;

    private static final String RESOLVED = "Resolved";

    private static final String IN_PROGRESS = "In Progress";

    private static final String SELF_PICKED = "Self-picked from queue";

    private static final Pattern SELF_PICKED_HOW = Pattern.compile("Self-picked");

    private final ModuleContext ctx;

    private final PolicyService policy;

    private final CalcService calc;

    BugService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();

        this.calc = ctx.calc();
    }

    // ------------------------------------------------------------------ views

    /**
     * The Person tab: {@code {person, period, stats, open, inCode, fixedForOthers, tickets, disputes,
     * subtitle}}, and for the signed-in person also {@code toPickUp} and {@code queue}.
     */
    Future<JsonObject> person(JsonObject user, String personId, String periodKey)
    {
        UUID id = UUID.fromString(personId);

        boolean self = personId.equals(user.getString("personId"));

        return policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON).compose(scope ->
        {
            BugRepository repo = new BugRepository(ctx.sql());

            Future<JsonObject> period = period(periodKey);

            Future<JsonArray> inCode = repo.inCodeOf(scope, id);

            Future<JsonArray> fixed = repo.fixedForOthersBy(scope, id);

            Future<JsonArray> tickets = repo.ticketsOf(scope, id);

            Future<JsonArray> pick = self ? repo.toPickUp(scope, id) : Future.succeededFuture(null);

            Future<JsonArray> queue = self ? repo.ticketQueue() : Future.succeededFuture(null);

            Future<JsonObject> identity = new PersonRepository(ctx.sql()).identity(scope, id);

            return Future.all(List.of(period, inCode, fixed, tickets, pick, queue, identity)).compose(all ->
            {
                String key = period.result().getString("key");

                Future<Object> stats = calc.get(WorkloadFormulas.BUG_STATS, new JsonObject().put("person", personId).put("period", key));

                Future<Object> history = calc.get(WorkloadFormulas.BUG_HISTORY, new JsonObject().put("people", personId).put("period", key));

                return Future.all(stats, history).map(done -> new JsonObject()
                                    .put("person", identity.result())
                                    .put("period", period.result())
                                    .put("history", history.result())
                                    .put("stats", stats.result())
                                    .put("open", openOnly(inCode.result()))
                                    .put("inCode", inCode.result())
                                    .put("fixedForOthers", fixed.result())
                                    .put("tickets", tickets.result())
                                    .put("toPickUp", pick.result())
                                    .put("queue", queue.result())
                                    .put("subtitle", LEARNING_SIGNAL));
            });
        });
    }

    /**
     * The Team tab: {@code {period, kpis, people, disputes}} for the people the manager sees.
     */
    Future<JsonObject> team(JsonObject user, String periodKey)
    {
        return policy.scope(user, PolicyService.DOMAIN_PERSON).compose(scope ->
        {
            BugRepository repo = new BugRepository(ctx.sql());

            Future<JsonArray> visible = repo.teamMembers(scope, userId(user));

            Future<JsonObject> period = period(periodKey);

            Future<JsonArray> disputes = repo.openDisputes(scope);

            Future<JsonArray> queue = repo.ticketQueue();

            return Future.all(List.of(visible, period, disputes, queue)).compose(loaded ->
            {
                List<JsonObject> members = new ArrayList<>();

                visible.result().forEach(row -> members.add((JsonObject) row));

                List<Future<Object>> stats = new ArrayList<>();

                List<Future<JsonArray>> open = new ArrayList<>();

                for (JsonObject member : members)
                {
                    stats.add(calc.get(WorkloadFormulas.BUG_STATS, new JsonObject().put("person", member.getString("id"))
                            .put("period", period.result().getString("key"))));

                    open.add(repo.inCodeOf(scope, UUID.fromString(member.getString("id"))));
                }

                String ids = String.join(",", members.stream().map(m -> m.getString("id")).toList());

                Future<Object> history = calc.get(WorkloadFormulas.BUG_HISTORY, new JsonObject().put("people", ids)
                        .put("period", period.result().getString("key")));

                Future<JsonObject> teamName = ctx.sql().queryOne("SELECT string_agg(DISTINCT department, ', ') AS team FROM people WHERE id = ANY($1)",
                        Tuple.of(members.stream().map(m -> UUID.fromString(m.getString("id"))).toArray(UUID[]::new)));

                List<Future<?>> everything = new ArrayList<>(stats);

                everything.addAll(open);

                everything.add(history);

                everything.add(teamName);

                return Future.all(everything).map(done ->
                {
                    JsonArray rows = new JsonArray();

                    int openBugs = 0;

                    int unassigned = 0;

                    int proactive = 0;

                    int tickets = 0;

                    double hours = 0;

                    for (int i = 0; i < members.size(); i++)
                    {
                        JsonObject s = (JsonObject) stats.get(i).result();

                        JsonArray mine = openOnly(open.get(i).result());

                        openBugs += mine.size();

                        unassigned += (int) mine.stream().filter(b -> ((JsonObject) b).getString("assigneeId") == null).count();

                        proactive += s.getInteger("proactive", 0);

                        tickets += s.getInteger("supM", 0);

                        hours += s.getDouble("supH", 0.0);

                        rows.add(new JsonObject()
                                .put("id", members.get(i).getString("id"))
                                .put("name", members.get(i).getString("name"))
                                .put("stats", s));
                    }

                    List<Object> sorted = new ArrayList<>(rows.getList());

                    sorted.sort((a, b) -> Integer.compare(open(b), open(a)));

                    return new JsonObject()
                            .put("period", period.result())
                            .put("team", teamName.result() == null ? null : teamName.result().getString("team"))
                            .put("history", history.result())
                            .put("kpis", new JsonObject()
                                    .put("openBugs", openBugs)
                                    .put("unassigned", unassigned)
                                    .put("proactiveFixes", proactive)
                                    .put("supportTickets", tickets)
                                    .put("supportHours", hours)
                                    .put("queue", queue.result().size()))
                            .put("people", new JsonArray(sorted))
                            .put("disputes", disputes.result())
                            .put("subtitle", LEARNING_SIGNAL);
                });
            });
        });
    }

    // ------------------------------------------------------------------ disputes

    /**
     * The code owner disputes the attribution of a bug in their code with a reason of at least
     * {@value #MIN_REASON} characters; one open dispute per bug (409 {@code dispute_open}).
     */
    Future<JsonObject> dispute(JsonObject user, String key, String reason)
    {
        String text = reason == null ? "" : reason.strip();

        if (text.length() < MIN_REASON)
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field",
                    "Give a reason of at least " + MIN_REASON + " characters.").field("reason"));
        }

        UUID me = personOf(user);

        return policy.scope(user, PolicyService.DOMAIN_PERSON).compose(scope -> ctx.sql().inTransaction(tx ->
        {
            BugRepository repo = new BugRepository(tx);

            return repo.byKey(scope, key).compose(visible -> visible == null ? Future.succeededFuture(null) : repo.lockByKey(key))
                    .compose(bug ->
                    {
                        if (bug == null)
                        {
                            return Future.failedFuture(notFound());
                        }

                        if (me == null || !me.toString().equals(bug.getString("originPersonId")))
                        {
                            return Future.failedFuture(ApiException.forbidden("no_access",
                                    "Only the person whose code a bug is traced to can dispute it."));
                        }

                        if (bug.getString("openDisputeId") != null)
                        {
                            return Future.failedFuture(ApiException.conflict("dispute_open",
                                    "This attribution is already disputed; your manager is reviewing it."));
                        }

                        UUID bugId = UUID.fromString(bug.getString("id"));

                        return repo.openDispute(bugId, me, text, userId(user))
                                .compose(saved -> audit(tx, user, DISPUTE_RAISED, "bug", bugId, new JsonObject()
                                        .put("key", key).put("personId", me.toString())).map(saved));
                    });
        }));
    }

    /**
     * The code owner's manager keeps the attribution or removes the bug from their count ({@code keep} or
     * {@code remove}), on the version they saw; audited as {@code bugs.dispute.resolve}.
     */
    Future<JsonObject> decide(JsonObject user, UUID disputeId, String decision, int version, JsonObject request)
    {
        return policy.scope(user, PolicyService.DOMAIN_PERSON).compose(scope -> ctx.sql().inTransaction(tx ->
        {
            BugRepository repo = new BugRepository(tx);

            return repo.lockDispute(scope, disputeId).compose(dispute ->
            {
                if (dispute == null)
                {
                    return Future.failedFuture(ApiException.notFound("not_found", "Dispute not found."));
                }

                return policy.refuseSelf(user, AuditEvents.BUG_DISPUTE_RESOLVE, dispute.getString("personId"), null, request)
                        .compose(v ->
                        {
                            if (dispute.getString("decision") != null)
                            {
                                return Future.failedFuture(ApiException.unprocessable("already_decided",
                                        "This dispute was already decided."));
                            }

                            return repo.decideDispute(disputeId, decision, userId(user), version);
                        })
                        .compose(saved -> audit(tx, user, AuditEvents.BUG_DISPUTE_RESOLVE, "bug",
                                UUID.fromString(dispute.getString("bugId")), new JsonObject()
                                        .put("key", dispute.getString("bugKey"))
                                        .put("personId", dispute.getString("personId"))
                                        .put("decision", decision))
                                .map(saved));
            });
        })).onSuccess(saved -> changed(WorkloadFormulas.BUGS));
    }

    // ------------------------------------------------------------------ fixing bugs

    /**
     * "Take it": the code owner takes the fix of an unresolved bug in their code from whoever has it.
     */
    Future<JsonObject> take(JsonObject user, String key)
    {
        UUID me = personOf(user);

        return policy.scope(user, PolicyService.DOMAIN_PERSON).compose(scope -> ctx.sql().inTransaction(tx ->
        {
            BugRepository repo = new BugRepository(tx);

            return repo.byKey(scope, key).compose(visible -> visible == null ? Future.succeededFuture(null) : repo.lockByKey(key))
                    .compose(bug ->
                    {
                        if (bug == null)
                        {
                            return Future.failedFuture(notFound());
                        }

                        if (me == null || !me.toString().equals(bug.getString("originPersonId")))
                        {
                            return Future.failedFuture(ApiException.unprocessable("not_your_code",
                                    "Take it is for bugs in your own code; pick up the others."));
                        }

                        ApiException refused = unresolvedAndNotMine(bug, me);

                        if (refused != null)
                        {
                            return Future.failedFuture(refused);
                        }

                        return work(tx, user, bug, me, false, IN_PROGRESS, FIX_TAKEN);
                    });
        })).onSuccess(bug -> changed(WorkloadFormulas.BUGS));
    }

    /**
     * "Pick up": the signed-in person fixes an unresolved bug in a teammate's code (same team, or a person
     * they may see); it counts as proactive.
     */
    Future<JsonObject> pickUp(JsonObject user, String key)
    {
        UUID me = personOf(user);

        return policy.scope(user, PolicyService.DOMAIN_PERSON).compose(scope -> ctx.sql().inTransaction(tx ->
        {
            BugRepository repo = new BugRepository(tx);

            Future<JsonObject> visible = repo.byKey(scope, key);

            Future<JsonObject> myTeam = me == null ? Future.succeededFuture(null)
                    : tx.queryOne("SELECT department FROM people WHERE id = $1", Tuple.of(me));

            return Future.all(visible, myTeam).compose(all -> repo.lockByKey(key)).compose(bug ->
            {
                boolean sameTeam = bug != null && myTeam.result() != null && bug.getString("ownerTeam") != null
                        && bug.getString("ownerTeam").equals(myTeam.result().getString("department"));

                if (bug == null || me == null || (visible.result() == null && !sameTeam))
                {
                    return Future.failedFuture(notFound());
                }

                if (me.toString().equals(bug.getString("originPersonId")))
                {
                    return Future.failedFuture(ApiException.unprocessable("own_code", "This bug is in your code: use Take it."));
                }

                ApiException refused = unresolvedAndNotMine(bug, me);

                if (refused != null)
                {
                    return Future.failedFuture(refused);
                }

                return work(tx, user, bug, me, true, IN_PROGRESS, FIX_PICKED_UP);
            });
        })).onSuccess(bug -> changed(WorkloadFormulas.BUGS));
    }

    /**
     * "Mark resolved" by the person fixing the bug. A proactive fix of someone else's code adds a
     * proactive-fix item awaiting confirmation; {@code hours} is the time it took, if given.
     */
    Future<JsonObject> resolve(JsonObject user, String key, Double hours)
    {
        UUID me = personOf(user);

        return policy.scope(user, PolicyService.DOMAIN_PERSON).compose(scope -> ctx.sql().inTransaction(tx ->
        {
            BugRepository repo = new BugRepository(tx);

            return repo.byKey(scope, key).compose(visible -> visible == null ? Future.succeededFuture(null) : repo.lockByKey(key))
                    .compose(bug ->
                    {
                        if (bug == null)
                        {
                            return Future.failedFuture(notFound());
                        }

                        if (RESOLVED.equals(bug.getString("status")))
                        {
                            return Future.failedFuture(ApiException.unprocessable("already_resolved", key + " is already resolved."));
                        }

                        if (me == null || !me.toString().equals(bug.getString("assigneeId")))
                        {
                            return Future.failedFuture(ApiException.unprocessable("not_assigned_to_you",
                                    "Only the person fixing " + key + " can mark it resolved."));
                        }

                        boolean proactive = bug.getBoolean("proactive", false) && !me.toString().equals(bug.getString("originPersonId"));

                        return work(tx, user, bug, me, bug.getBoolean("proactive", false), RESOLVED, FIX_RESOLVED)
                                .compose(saved -> !proactive ? Future.succeededFuture(saved) : ownerName(tx, bug)
                                        .compose(owner -> repo.addPendingItem(new JsonObject()
                                                .put("detectionKey", "proactivefix:" + key + ":" + me)
                                                .put("personId", me.toString())
                                                .put("kind", "proactivefix")
                                                .put("ref", key)
                                                .put("title", bug.getString("title") + (owner == null ? "" : " (code by " + owner + ")"))
                                                .put("requestedBy", "Self-assigned from bug backlog")
                                                .put("detectionNote", "Jira: picked up in Pulse, resolved " + today()
                                                        + (bug.getString("originRef") == null ? "" : "; bug linked to " + bug.getString("originRef")))
                                                .put("evidenceSource", "Jira + GitHub")
                                                .put("occurredOn", today().toString())
                                                .put("hours", hours == null ? 0 : hours)
                                                .put("values", new JsonArray().add("Problem Solving").add("Collaboration"))))
                                        .map(added -> saved.put("pendingItem", added)));
                    });
        })).onSuccess(bug -> changed(WorkloadFormulas.BUGS, EffortFormulas.EXTRA_ITEMS));
    }

    // ------------------------------------------------------------------ support tickets

    /**
     * Takes an unassigned ticket from the queue.
     */
    Future<JsonObject> takeTicket(JsonObject user, String key)
    {
        UUID me = personOf(user);

        return ctx.sql().inTransaction(tx ->
        {
            BugRepository repo = new BugRepository(tx);

            return repo.lockTicket(key).compose(ticket ->
            {
                if (ticket == null)
                {
                    return Future.failedFuture(ApiException.notFound("not_found", "Ticket not found."));
                }

                if (me == null)
                {
                    return Future.failedFuture(ApiException.unprocessable("no_person", "Only people with work records take tickets."));
                }

                if (ticket.getString("personId") != null || !"Open".equals(ticket.getString("status")))
                {
                    return Future.failedFuture(ApiException.unprocessable("ticket_taken", key + " is no longer in the queue."));
                }

                UUID id = UUID.fromString(ticket.getString("id"));

                return repo.saveTicket(id, me, SELF_PICKED, "Open", null, null, null, userId(user))
                        .compose(v -> audit(tx, user, TICKET_TAKEN, "support_ticket", id, new JsonObject().put("key", key)))
                        .compose(v -> repo.lockTicket(key));
            });
        }).onSuccess(ticket -> changed(WorkloadFormulas.BUGS));
    }

    /**
     * Resolves the signed-in person's open ticket with the hours spent and a resolution note; adds a
     * support item awaiting confirmation.
     */
    Future<JsonObject> resolveTicket(JsonObject user, String key, double hours, String note)
    {
        UUID me = personOf(user);

        String text = note == null ? "" : note.strip();

        if (text.isEmpty())
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "Add a resolution note.").field("note"));
        }

        if (!(hours > 0))
        {
            return Future.failedFuture(ApiException.badRequest("invalid_field", "Enter the hours spent.").field("hours"));
        }

        return ctx.sql().inTransaction(tx ->
        {
            BugRepository repo = new BugRepository(tx);

            return repo.lockTicket(key).compose(ticket ->
            {
                if (ticket == null || me == null || !me.toString().equals(ticket.getString("personId")))
                {
                    return Future.failedFuture(ApiException.notFound("not_found", "Ticket not found."));
                }

                if (!"Open".equals(ticket.getString("status")))
                {
                    return Future.failedFuture(ApiException.unprocessable("already_resolved", key + " is already resolved."));
                }

                UUID id = UUID.fromString(ticket.getString("id"));

                String how = ticket.getString("howTaken") == null ? SELF_PICKED : ticket.getString("howTaken");

                boolean selfPicked = SELF_PICKED_HOW.matcher(how).find();

                JsonArray values = selfPicked ? new JsonArray().add("Outcome Ownership").add("Collaboration")
                        : new JsonArray().add("Collaboration");

                return repo.saveTicket(id, me, how, RESOLVED, BigDecimal.valueOf(hours), text, today(), userId(user))
                        .compose(v -> repo.addPendingItem(new JsonObject()
                                .put("detectionKey", "support:" + key + ":" + me)
                                .put("personId", me.toString())
                                .put("kind", "support")
                                .put("ref", key)
                                .put("title", ticket.getString("title") + (ticket.getString("customer") == null ? ""
                                        : " (" + ticket.getString("customer") + ")"))
                                .put("requestedBy", selfPicked ? "Self-picked from support queue" : how.replace("Assigned by ", ""))
                                .put("detectionNote", "Support desk: " + how + ", resolved in Pulse " + today())
                                .put("evidenceSource", "Support desk")
                                .put("occurredOn", today().toString())
                                .put("hours", hours)
                                .put("values", values)))
                        .compose(added -> audit(tx, user, TICKET_RESOLVED, "support_ticket", id, new JsonObject()
                                        .put("key", key).put("hours", hours))
                                .compose(v -> repo.lockTicket(key))
                                .map(saved -> saved.put("pendingItem", added)));
            });
        }).onSuccess(ticket -> changed(WorkloadFormulas.BUGS, EffortFormulas.EXTRA_ITEMS));
    }

    // ------------------------------------------------------------------ helpers

    private Future<JsonObject> work(SqlExecutor tx, JsonObject user, JsonObject bug, UUID me, boolean proactive, String status,
                                    String event)
    {
        UUID id = UUID.fromString(bug.getString("id"));

        BugRepository repo = new BugRepository(tx);

        return repo.saveWork(id, me, proactive, status, RESOLVED.equals(status) ? today() : null, userId(user))
                .compose(v -> audit(tx, user, event, "bug", id, new JsonObject().put("key", bug.getString("key"))))
                .compose(v -> repo.lockByKey(bug.getString("key")));
    }

    private static ApiException unresolvedAndNotMine(JsonObject bug, UUID me)
    {
        if (RESOLVED.equals(bug.getString("status")))
        {
            return ApiException.unprocessable("already_resolved", bug.getString("key") + " is already resolved.");
        }

        if (me.toString().equals(bug.getString("assigneeId")))
        {
            return ApiException.unprocessable("already_yours", "You are already fixing " + bug.getString("key") + ".");
        }

        return null;
    }

    private static Future<Void> audit(SqlExecutor tx, JsonObject user, String event, String type, UUID id, JsonObject details)
    {
        return AuditService.record(tx, AuditService.entry(user, event, type, id.toString(), details));
    }

    private static Future<String> ownerName(SqlExecutor tx, JsonObject bug)
    {
        String owner = bug.getString("originPersonId");

        return owner == null ? Future.succeededFuture(null)
                : tx.queryOne("SELECT name FROM people WHERE id = $1", Tuple.of(UUID.fromString(owner)))
                        .map(row -> row == null ? null : row.getString("name"));
    }

    private Future<JsonObject> period(String key)
    {
        String chosen = key == null || key.isBlank() ? "m:" + YearMonth.from(today()) : key;

        return calc.get("core.period", new JsonObject().put("period", chosen))
                .map(period -> (JsonObject) period)
                .recover(error -> error instanceof IllegalArgumentException
                        ? Future.failedFuture(ApiException.badRequest("invalid_parameter", "Unknown period.").field("period"))
                        : Future.failedFuture(error));
    }

    private LocalDate today()
    {
        return calc.clock().today();
    }

    /**
     * Drops the cached bug stats (and extra-mile results) here and on every other instance.
     */
    private void changed(String... topics)
    {
        calc.invalidate(topics);

        CalcService.publish(ctx.vertx(), topics);
    }

    private static JsonArray openOnly(JsonArray bugs)
    {
        JsonArray out = new JsonArray();

        bugs.forEach(b ->
        {
            if (!RESOLVED.equals(((JsonObject) b).getString("status")))
            {
                out.add(b);
            }
        });

        return out;
    }

    private static int open(Object row)
    {
        return ((JsonObject) row).getJsonObject("stats").getInteger("open", 0);
    }

    private static UUID personOf(JsonObject user)
    {
        return user.getString("personId") == null ? null : UUID.fromString(user.getString("personId"));
    }

    private static UUID userId(JsonObject user)
    {
        return UUID.fromString(user.getString("id"));
    }

    private static ApiException notFound()
    {
        return ApiException.notFound("not_found", "Bug not found.");
    }
}
