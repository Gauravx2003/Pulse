package com.pulse.modules.appraisal;

import com.pulse.calc.core.Appraisal;
import com.pulse.calc.core.ResultGuard;
import com.pulse.calc.core.SnapshotStore;
import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Appraisal & increments (task 22.1, spec appraisal/appraisal), on top of {@code core.appraisal} (task 4.8).
 *
 * <ul>
 *   <li><b>Access:</b> holders of {@code appraisal.manage} see and propose for the people in their salary
 *       scope; holders of {@code appraisal.approve} approve, send back and release. Every read is audited
 *       ({@code salary.read}); nobody proposes or approves their own increment.</li>
 *   <li><b>Draft:</b> the proposed percent is capped server-side at rule {@code appraisal.max}; a percent
 *       outside the person's band needs a reason of at least {@value #MIN_REASON} characters; a promotion
 *       may be flagged. "Apply recommendations" resets Draft and Sent back rows to the recommendation.</li>
 *   <li><b>Submit:</b> blocked while an exception has no reason (naming the people) or when the proposed
 *       spend is over the budget (rule {@code appraisal.budget} % of the team's payroll, amounts given).
 *       People with no CTC are not submitted. The score, breakdown, recommendation and rule version are
 *       frozen in a {@code calc_snapshots} row; a send-back makes the next submission recompute.</li>
 * </ul>
 */
final class AppraisalService
{
    static final int MIN_REASON = 10;

    static final String DRAFT = "Draft";

    static final String SUBMITTED = "Submitted";

    static final String SENT_BACK = "Sent back";

    static final String APPROVED = "Approved";

    static final String RELEASED = "Released";

    static final String SUBMITTED_EVENT = "appraisal.submitted";

    static final String CYCLE_UPDATED = "appraisal.cycle.updated";

    private final ModuleContext ctx;

    private final PolicyService policy;

    AppraisalService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();
    }

    LocalDate today()
    {
        return ctx.calc() == null ? LocalDate.now(PolicyService.ZONE) : ctx.calc().clock().today();
    }

    // ------------------------------------------------------------------ reads

    /** The current cycle: {@code {id, year, effectiveOn, version}}. */
    Future<JsonObject> cycle()
    {
        return new AppraisalRepository(ctx.sql()).cycle(today().getYear());
    }

    /**
     * The appraisal table for the viewer: {@code {cycle, rules, kpis, people: [...]}}; each person has the
     * score breakdown, band, recommendation, CTC, the proposal and whether it is an exception. Audited.
     */
    Future<JsonObject> table(JsonObject user, JsonObject request)
    {
        return load(user).compose(t -> AuditService.record(ctx.sql(), AuditService.entry(user, AuditEvents.SALARY_READ, "appraisal",
                        t.getJsonObject("cycle").getString("id"), new JsonObject().put("people", t.getJsonArray("people").size())))
                .map(t));
    }

    /** Everything the table, submit and the decisions read; not audited. */
    private Future<JsonObject> load(JsonObject user)
    {
        AppraisalRepository repo = new AppraisalRepository(ctx.sql());

        return Future.all(cycle(), policy.scope(user, PolicyService.DOMAIN_SALARY), rules()).compose(all ->
        {
            JsonObject cycle = all.resultAt(0);

            JsonObject rules = all.resultAt(2);

            return Future.all(repo.people(all.resultAt(1)), repo.proposals(UUID.fromString(cycle.getString("id")))).compose(data ->
            {
                Map<String, JsonObject> byPerson = new HashMap<>();

                data.<JsonArray>resultAt(1).forEach(o -> byPerson.put(((JsonObject) o).getString("personId"), (JsonObject) o));

                List<JsonObject> people = new ArrayList<>();

                for (Object o : data.<JsonArray>resultAt(0))
                {
                    JsonObject p = (JsonObject) o;

                    if (!p.getString("id").equals(user.getString("personId")))
                    {
                        people.add(p);
                    }
                }

                List<Future<JsonObject>> rows = new ArrayList<>();

                for (JsonObject p : people)
                {
                    rows.add(row(p, byPerson.get(p.getString("id")), rules));
                }

                return Future.all(rows).map(done ->
                {
                    JsonArray out = new JsonArray();

                    rows.forEach(f -> out.add(f.result()));

                    return new JsonObject().put("cycle", cycle).put("rules", rules).put("kpis", kpis(out, rules)).put("people", out);
                });
            });
        });
    }

    /** One person's row: the frozen assessment once submitted, else the live one. */
    private Future<JsonObject> row(JsonObject p, JsonObject proposal, JsonObject rules)
    {
        boolean frozen = proposal != null && proposal.getString("snapshotId") != null
                && List.of(SUBMITTED, APPROVED, RELEASED).contains(proposal.getString("status"));

        Future<JsonObject> assessment = frozen
                ? SnapshotStore.find(ctx.sql(), SnapshotStore.APPRAISAL, proposal.getString("id")).map(s -> s.getJsonObject("result"))
                : ctx.calc().get("core.appraisal", new JsonObject().put("person", p.getString("id")))
                        .map(r -> ResultGuard.unguard(r) instanceof JsonObject o ? o : new JsonObject());

        return assessment.map(a ->
        {
            JsonObject rec = a.getJsonObject("recommendation", new JsonObject().put("pct", 0).put("eligible", false)
                    .put("why", "No joining date"));

            Number ctc = p.getNumber("ctc");

            double pct = proposal != null ? proposal.getNumber("pct").doubleValue() : rec.getNumber("pct", 0).doubleValue();

            JsonObject band = rec.getJsonObject("band", new JsonObject());

            boolean exception = rec.getBoolean("eligible", false) && band.getNumber("lo") != null
                    && (pct < band.getNumber("lo").doubleValue() || pct > band.getNumber("hi").doubleValue());

            return new JsonObject()
                    .put("personId", p.getString("id"))
                    .put("name", p.getString("name"))
                    .put("title", p.getString("title"))
                    .put("level", p.getString("level"))
                    .put("userId", p.getString("userId"))
                    .put("parts", a.getJsonArray("parts"))
                    .put("score", a.getValue("score"))
                    .put("missing", a.getJsonArray("missing"))
                    .put("recommendation", rec)
                    .put("ctc", ctc)
                    .put("proposal", new JsonObject()
                            .put("id", proposal == null ? null : proposal.getString("id"))
                            .put("pct", pct)
                            .put("reason", proposal == null ? "" : proposal.getString("reason"))
                            .put("promotion", proposal != null && proposal.getBoolean("promotion", false))
                            .put("status", proposal == null ? DRAFT : proposal.getString("status"))
                            .put("sendBackReason", proposal == null ? null : proposal.getString("sendBackReason"))
                            .put("version", proposal == null ? null : proposal.getInteger("version")))
                    .put("exception", exception)
                    .put("newCtc", ctc == null ? null : money(ctc.doubleValue() * (1 + pct / 100)))
                    .put("frozen", frozen);
        });
    }

    private static JsonObject kpis(JsonArray rows, JsonObject rules)
    {
        double payroll = 0;

        double spend = 0;

        Map<String, Integer> counts = new HashMap<>();

        for (Object o : rows)
        {
            JsonObject r = (JsonObject) o;

            counts.merge(r.getJsonObject("proposal").getString("status"), 1, Integer::sum);

            if (r.getNumber("ctc") != null)
            {
                double ctc = r.getNumber("ctc").doubleValue();

                payroll += ctc;

                spend += ctc * r.getJsonObject("proposal").getNumber("pct").doubleValue() / 100;
            }
        }

        double budget = payroll * rules.getNumber("budget", 12).doubleValue() / 100;

        return new JsonObject()
                .put("people", rows.size())
                .put("payroll", money(payroll))
                .put("budget", money(budget))
                .put("spend", money(spend))
                .put("overBudget", spend > budget + 1e-9)
                .put("averagePct", payroll == 0 ? 0 : Math.round(spend / payroll * 1000) / 10.0)
                .put("statuses", new JsonObject(new HashMap<>(counts)));
    }

    // ------------------------------------------------------------------ draft

    /** Saves a Draft proposal for one person (appraisal.manage). */
    Future<JsonObject> save(JsonObject user, String personId, JsonObject body, JsonObject request)
    {
        return policy.refuseSelf(user, AuditEvents.INCREMENT_PROPOSE, personId, null, request)
                .compose(v -> load(user))
                .compose(t ->
                {
                    JsonObject row = find(t, personId);

                    if (row == null)
                    {
                        return Future.failedFuture(PolicyService.notFound());
                    }

                    // Spec "Cap": the value itself is checked first, whatever the proposal's state.
                    double pct = body.getNumber("pct").doubleValue();

                    double max = t.getJsonObject("rules").getNumber("max", 20).doubleValue();

                    if (pct < 0 || pct > max || pct * 2 != Math.rint(pct * 2))
                    {
                        return Future.failedFuture(ApiException.unprocessable("over_cap", "The increment must be between 0 and " + num(max)
                                + "% in steps of 0.5.").field("pct"));
                    }

                    String status = row.getJsonObject("proposal").getString("status");

                    if (!DRAFT.equals(status) && !SENT_BACK.equals(status))
                    {
                        return Future.failedFuture(ApiException.conflict("not_draft", "This proposal is " + status.toLowerCase()
                                + " and can't be changed."));
                    }

                    JsonObject rec = row.getJsonObject("recommendation");

                    JsonObject band = rec.getJsonObject("band", new JsonObject());

                    String reason = body.getString("reason", "").strip();

                    boolean outside = rec.getBoolean("eligible", false) && band.getNumber("lo") != null
                            && (pct < band.getNumber("lo").doubleValue() || pct > band.getNumber("hi").doubleValue());

                    if (outside && reason.length() < MIN_REASON)
                    {
                        return Future.failedFuture(ApiException.unprocessable("reason_required", "Outside the " + band.getString("name")
                                + " band (" + band.getValue("lo") + "–" + band.getValue("hi") + "%) — give a reason of at least "
                                + MIN_REASON + " characters.").field("reason"));
                    }

                    UUID cycle = UUID.fromString(t.getJsonObject("cycle").getString("id"));

                    return ctx.sql().inTransaction(tx -> new AppraisalRepository(tx)
                            .saveDraft(cycle, UUID.fromString(personId), BigDecimal.valueOf(pct), reason, body.getBoolean("promotion", false),
                                    UUID.fromString(user.getString("id")))
                            .compose(saved -> saved == null
                                    ? Future.failedFuture(ApiException.conflict("not_draft", "This proposal is no longer a draft."))
                                    : AuditService.record(tx, AuditService.entry(user, outside ? AuditEvents.APPRAISAL_EXCEPTION
                                                    : AuditEvents.INCREMENT_PROPOSE, "person", personId,
                                            new JsonObject().put("pct", pct).put("promotion", body.getBoolean("promotion", false))))
                                            .map(saved)));
                })
                .compose(saved -> load(user)).map(t -> find(t, personId));
    }

    /** Sets every Draft and Sent back proposal of the viewer's people to the recommendation. */
    Future<JsonObject> apply(JsonObject user)
    {
        return load(user).compose(t ->
        {
            UUID cycle = UUID.fromString(t.getJsonObject("cycle").getString("id"));

            UUID by = UUID.fromString(user.getString("id"));

            return ctx.sql().inTransaction(tx ->
            {
                AppraisalRepository repo = new AppraisalRepository(tx);

                Future<Integer> chain = Future.succeededFuture(0);

                for (Object o : t.getJsonArray("people"))
                {
                    JsonObject r = (JsonObject) o;

                    String status = r.getJsonObject("proposal").getString("status");

                    if (DRAFT.equals(status) || SENT_BACK.equals(status))
                    {
                        BigDecimal pct = BigDecimal.valueOf(r.getJsonObject("recommendation").getNumber("pct", 0).doubleValue());

                        chain = chain.compose(n -> repo.saveDraft(cycle, UUID.fromString(r.getString("personId")), pct, "", false, by)
                                .map(saved -> n + (saved == null ? 0 : 1)));
                    }
                }

                return chain.compose(n -> AuditService.record(tx, AuditService.entry(user, AuditEvents.INCREMENT_PROPOSE, "appraisal",
                        cycle.toString(), new JsonObject().put("applied", n))).map(n));
            }).map(n -> new JsonObject().put("applied", n));
        });
    }

    // ------------------------------------------------------------------ submit

    /**
     * Submits every Draft proposal with a CTC to HR: 422 naming the people whose exception has no reason,
     * 409 with the amounts when over budget; freezes each assessment.
     */
    Future<JsonObject> submit(JsonObject user)
    {
        return Future.all(load(user), SnapshotStore.currentRules(ctx.sql())).compose(all ->
        {
            JsonObject t = all.resultAt(0);

            int ruleVersion = all.<JsonObject>resultAt(1) == null ? 1 : all.<JsonObject>resultAt(1).getInteger("version");

            List<JsonObject> drafts = new ArrayList<>();

            List<String> noReason = new ArrayList<>();

            List<String> noCtc = new ArrayList<>();

            for (Object o : t.getJsonArray("people"))
            {
                JsonObject r = (JsonObject) o;

                if (!DRAFT.equals(r.getJsonObject("proposal").getString("status")))
                {
                    continue;
                }

                if (r.getNumber("ctc") == null)
                {
                    noCtc.add(r.getString("name"));

                    continue;
                }

                if (r.getBoolean("exception") && r.getJsonObject("proposal").getString("reason", "").length() < MIN_REASON)
                {
                    noReason.add(r.getString("name"));
                }

                drafts.add(r);
            }

            if (!noReason.isEmpty())
            {
                return Future.failedFuture(ApiException.unprocessable("exception_reason", "Add a reason for exceptions first: "
                        + String.join(", ", noReason) + "."));
            }

            JsonObject kpis = t.getJsonObject("kpis");

            if (kpis.getBoolean("overBudget"))
            {
                return Future.failedFuture(ApiException.conflict("over_budget", "Proposed increments total ₹" + num(kpis.getDouble("spend"))
                                + " L against a budget of ₹" + num(kpis.getDouble("budget")) + " L. Lower some proposals before submitting.")
                        .detail("spend", kpis.getValue("spend")).detail("budget", kpis.getValue("budget")));
            }

            if (drafts.isEmpty())
            {
                return Future.succeededFuture(new JsonObject().put("submitted", 0).put("noCtc", new JsonArray(noCtc)));
            }

            UUID cycle = UUID.fromString(t.getJsonObject("cycle").getString("id"));

            UUID by = UUID.fromString(user.getString("id"));

            return ctx.sql().inTransaction(tx ->
            {
                AppraisalRepository repo = new AppraisalRepository(tx);

                Future<Integer> chain = Future.succeededFuture(0);

                for (JsonObject r : drafts)
                {
                    UUID person = UUID.fromString(r.getString("personId"));

                    JsonObject p = r.getJsonObject("proposal");

                    chain = chain.compose(n -> (p.getString("id") != null ? repo.proposal(cycle, person)
                                    : repo.saveDraft(cycle, person, BigDecimal.valueOf(p.getNumber("pct").doubleValue()), "", false, by)
                                            .compose(x -> repo.proposal(cycle, person)))
                            .compose(row -> freeze(tx, row.getString("id"), ruleVersion, r, user)
                                    .compose(snapshot -> repo.submit(UUID.fromString(row.getString("id")), by, UUID.fromString(snapshot.getString("id"))))
                                    .compose(done -> AuditService.record(tx, AuditService.entry(user, SUBMITTED_EVENT, "person", person.toString(),
                                            new JsonObject().put("pct", p.getValue("pct")).put("ruleVersion", ruleVersion))))
                                    .map(n + 1)));
                }

                return chain;
            }).map(n -> new JsonObject().put("submitted", n).put("noCtc", new JsonArray(noCtc)));
        });
    }

    /** Freezes the assessment of a proposal; a resubmission after a send-back replaces the old snapshot. */
    private Future<JsonObject> freeze(com.pulse.platform.db.SqlExecutor tx, String proposalId, int ruleVersion, JsonObject row, JsonObject user)
    {
        JsonObject result = new JsonObject()
                .put("parts", row.getJsonArray("parts"))
                .put("score", row.getValue("score"))
                .put("missing", row.getJsonArray("missing"))
                .put("recommendation", row.getJsonObject("recommendation"));

        return SnapshotStore.find(tx, SnapshotStore.APPRAISAL, proposalId).compose(existing -> existing == null
                ? SnapshotStore.freeze(tx, SnapshotStore.APPRAISAL, proposalId, ruleVersion, new JsonObject(), result, user.getString("id"))
                : SnapshotStore.replace(tx, SnapshotStore.APPRAISAL, proposalId, ruleVersion, new JsonObject(), result, user,
                        "Resubmitted after a send-back"));
    }

    // ------------------------------------------------------------------ decisions (appraisal.approve)

    Future<JsonObject> approve(JsonObject user, String personId, JsonObject request)
    {
        return decideOne(user, personId, SUBMITTED, APPROVED, null, AuditEvents.INCREMENT_APPROVE, request);
    }

    Future<JsonObject> sendBack(JsonObject user, String personId, String reason, JsonObject request)
    {
        if (reason == null || reason.strip().length() < MIN_REASON)
        {
            return Future.failedFuture(ApiException.unprocessable("reason_required", "Tell the manager why, in at least " + MIN_REASON
                    + " characters.").field("reason"));
        }

        return decideOne(user, personId, SUBMITTED, SENT_BACK, reason.strip(), AuditEvents.INCREMENT_SEND_BACK, request);
    }

    private Future<JsonObject> decideOne(JsonObject user, String personId, String from, String to, String reason, String event,
                                         JsonObject request)
    {
        return policy.refuseSelf(user, AuditEvents.INCREMENT_APPROVE, personId, null, request)
                .compose(v -> load(user))
                .compose(t ->
                {
                    JsonObject row = find(t, personId);

                    if (row == null)
                    {
                        return Future.failedFuture(PolicyService.notFound());
                    }

                    String id = row.getJsonObject("proposal").getString("id");

                    if (id == null || !from.equals(row.getJsonObject("proposal").getString("status")))
                    {
                        return Future.failedFuture(ApiException.conflict("out_of_order", "Only a " + from.toLowerCase()
                                + " proposal can be " + (SENT_BACK.equals(to) ? "sent back" : to.toLowerCase()) + "."));
                    }

                    return ctx.sql().inTransaction(tx -> new AppraisalRepository(tx)
                            .decide(UUID.fromString(id), from, to, UUID.fromString(user.getString("id")), reason)
                            .compose(done -> AuditService.record(tx, AuditService.entry(user, event, "person", personId,
                                    new JsonObject().put("status", to).put("reason", reason)))));
                })
                .compose(v -> load(user)).map(t -> find(t, personId));
    }

    /** Approves every Submitted proposal the approver sees (never their own). */
    Future<JsonObject> approveAll(JsonObject user)
    {
        return bulk(user, SUBMITTED, APPROVED, AuditEvents.INCREMENT_APPROVE).map(n -> new JsonObject().put("approved", n));
    }

    /** Releases the letters of every Approved proposal, effective on the cycle date. */
    Future<JsonObject> release(JsonObject user)
    {
        return Future.all(bulk(user, APPROVED, RELEASED, AuditEvents.LETTER_RELEASED), cycle())
                .map(all -> new JsonObject().put("released", all.<Integer>resultAt(0))
                        .put("effectiveOn", all.<JsonObject>resultAt(1).getString("effectiveOn")));
    }

    private Future<Integer> bulk(JsonObject user, String from, String to, String event)
    {
        return load(user).compose(t -> ctx.sql().inTransaction(tx ->
        {
            AppraisalRepository repo = new AppraisalRepository(tx);

            Future<Integer> chain = Future.succeededFuture(0);

            for (Object o : t.getJsonArray("people"))
            {
                JsonObject r = (JsonObject) o;

                JsonObject p = r.getJsonObject("proposal");

                if (from.equals(p.getString("status")) && p.getString("id") != null)
                {
                    chain = chain.compose(n -> repo.decide(UUID.fromString(p.getString("id")), from, to, UUID.fromString(user.getString("id")), null)
                            .compose(done -> AuditService.record(tx, AuditService.entry(user, event, "person", r.getString("personId"),
                                    new JsonObject().put("status", to))))
                            .map(n + 1));
                }
            }

            return chain;
        }));
    }

    // ------------------------------------------------------------------ cycle

    Future<JsonObject> setEffectiveOn(JsonObject user, LocalDate effectiveOn, int expectedVersion)
    {
        return cycle().compose(c -> ctx.sql().inTransaction(tx -> new AppraisalRepository(tx)
                .setEffectiveOn(UUID.fromString(c.getString("id")), effectiveOn, expectedVersion)
                .compose(v -> AuditService.record(tx, AuditService.entry(user, CYCLE_UPDATED, "appraisal_cycle", c.getString("id"),
                        new JsonObject().put("effectiveOn", effectiveOn.toString()))))))
                .compose(v -> cycle());
    }

    // ------------------------------------------------------------------ helpers

    /** Rule {@code appraisal} in force, or the defaults. */
    private Future<JsonObject> rules()
    {
        return SnapshotStore.currentRules(ctx.sql()).map(r ->
        {
            JsonObject set = r == null ? null : r.getJsonObject("rules");

            return set != null && set.getJsonObject("appraisal") != null ? set.getJsonObject("appraisal") : Appraisal.defaultRules();
        });
    }

    private static JsonObject find(JsonObject table, String personId)
    {
        for (Object o : table.getJsonArray("people"))
        {
            if (personId.equals(((JsonObject) o).getString("personId")))
            {
                return (JsonObject) o;
            }
        }

        return null;
    }

    private static double money(double lakh)
    {
        return BigDecimal.valueOf(lakh).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String num(double v)
    {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
