package com.pulse.modules.reviews;

import com.pulse.calc.core.CoreThresholds;
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
import java.util.List;
import java.util.UUID;

/**
 * Performance review (task 20.1, spec culture/reviews-performance): the index with its five pillars and
 * a 4-quarter trend, the calibration table for {@code reviews.calibrate} holders, the review summary and
 * submission for calibration (never yourself; audited). Every number comes from the calc service
 * ({@code core.pillars}, {@code core.index}, {@code core.indexHistory}, the proposed rating
 * {@code core.proposed} and its thresholds {@code core.thresholds}); the index is an input to
 * calibration, not the rating.
 */
final class PerformanceService
{
    static final String CALIBRATE = "reviews.calibrate";

    /** The pillars in order: key, label, rule weight key and what feeds it. */
    static final String[][] PILLARS = {
            {"delivery", "Delivery", "Commitment met, cycle time, carry-over, predictability"},
            {"quality", "Quality", "Reopen rate, escaped bugs, review findings"},
            {"culture", "Culture values", "Behaviour-anchored 360° on the culture values, with data signals"},
            {"okr", "Goals / OKRs", "Key results achieved this quarter"},
            {"growth", "Growth", "Road map progress, skills, mentoring, training rating"}};

    private final ModuleContext ctx;

    private final PolicyService policy;

    PerformanceService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();
    }

    /** The live quarter key, e.g. {@code q:2026-Q3}. */
    String liveQuarter()
    {
        LocalDate d = ctx.calc().clock().today();

        return "q:" + d.getYear() + "-Q" + ((d.getMonthValue() - 1) / 3 + 1);
    }

    private static String label(String key)
    {
        return key.substring(7) + " " + key.substring(2, 6);
    }

    // ------------------------------------------------------------------ one person

    /**
     * A person's review: identity, index, pillar breakdown (weight, score, inputs), the trend (three closed
     * quarters and this one), the submission and whether the viewer may submit it.
     */
    Future<JsonObject> review(JsonObject user, String personId)
    {
        String live = liveQuarter();

        UUID id = UUID.fromString(personId);

        return policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON).compose(scope ->
        {
            Future<JsonObject> person = new PerformanceRepository(ctx.sql()).person(id);

            Future<JsonObject> breakdown = breakdown(personId, live);

            Future<Object> history = get("core.indexHistory", new JsonObject().put("person", personId));

            Future<JsonObject> submission = new PerformanceRepository(ctx.sql()).submission(id, live.substring(2));

            return Future.all(person, breakdown, history, submission).map(all ->
            {
                JsonObject b = breakdown.result();

                JsonArray trend = new JsonArray();

                JsonArray h = history.result() instanceof JsonArray a ? a : new JsonArray();

                LocalDate today = ctx.calc().clock().today();

                for (int back = 3; back >= 1; back--)
                {
                    LocalDate d = today.minusMonths(3L * back);

                    String key = "q:" + d.getYear() + "-Q" + ((d.getMonthValue() - 1) / 3 + 1);

                    Object v = h.size() > 3 - back ? h.getValue(3 - back) : null;

                    trend.add(new JsonObject().put("period", key).put("label", label(key)).put("index", round(v)));
                }

                trend.add(new JsonObject().put("period", live).put("label", label(live)).put("index", b.getValue("index")).put("live", true));

                return b
                        .put("person", person.result())
                        .put("trend", trend)
                        .put("submission", submission.result())
                        .put("canSubmit", submission.result() == null && b.getValue("index") != null && canCalibrate(user, personId));
            });
        });
    }

    /**
     * {@code {period, label, index, proposed, pillars:[{key, label, weight, score, tone, inputs}]}} of a person;
     * {@code tone} from {@code core.thresholds} {@code tones.index}.
     */
    private Future<JsonObject> breakdown(String personId, String period)
    {
        JsonObject pp = new JsonObject().put("person", personId).put("period", period);

        return Future.all(List.of(get("core.pillars", pp), get("core.index", pp), weights(), get("core.proposed", pp),
                get("core.thresholds", new JsonObject()))).map(all ->
        {
            JsonArray tones = all.<JsonObject>resultAt(4).getJsonObject("tones").getJsonArray("index");

            JsonObject p = all.resultAt(0) instanceof JsonObject o ? o : null;

            Double index = all.resultAt(1) instanceof Number n ? round(n) : null;

            JsonObject w = all.resultAt(2);

            JsonArray pillars = new JsonArray();

            for (String[] def : PILLARS)
            {
                pillars.add(new JsonObject()
                        .put("key", def[0])
                        .put("label", def[1])
                        .put("weight", w.getValue(def[0]))
                        .put("score", p == null ? null : round(p.getValue(def[0])))
                        .put("tone", p == null ? "" : CoreThresholds.tone(round(p.getValue(def[0])), tones))
                        .put("inputs", def[2]));
            }

            return new JsonObject()
                    .put("period", period)
                    .put("label", label(period))
                    .put("index", index)
                    .put("proposed", index == null ? null : all.resultAt(3))
                    .put("pillars", pillars);
        });
    }

    // ------------------------------------------------------------------ calibration

    /** The calibration table of the viewer's people (not themselves): pillars, index, proposed rating, submission. */
    Future<JsonObject> calibration(JsonObject user)
    {
        String live = liveQuarter();

        PerformanceRepository repo = new PerformanceRepository(ctx.sql());

        return policy.scope(user, PolicyService.DOMAIN_PERSON).compose(repo::people).compose(people ->
                repo.submissions(live.substring(2)).compose(submitted ->
                {
                    List<JsonObject> rows = new ArrayList<>();

                    List<Future<JsonObject>> each = new ArrayList<>();

                    for (Object o : people)
                    {
                        JsonObject p = (JsonObject) o;

                        if (p.getString("id").equals(user.getString("personId")))
                        {
                            continue;
                        }

                        rows.add(p);

                        each.add(breakdown(p.getString("id"), live));
                    }

                    Future<JsonObject> thresholds = ctx.calc().getObject("core.thresholds", new JsonObject());

                    List<Future<?>> waits = new ArrayList<>(each);

                    waits.add(thresholds);

                    return Future.all(waits).map(all ->
                    {
                        JsonArray out = new JsonArray();

                        for (int i = 0; i < rows.size(); i++)
                        {
                            JsonObject b = each.get(i).result();

                            JsonObject pillars = new JsonObject();

                            b.getJsonArray("pillars").forEach(x -> pillars.put(((JsonObject) x).getString("key"), ((JsonObject) x).getValue("score")));

                            out.add(rows.get(i).copy()
                                    .put("pillars", pillars)
                                    .put("index", b.getValue("index"))
                                    .put("proposed", b.getValue("proposed"))
                                    .put("submission", submitted.getJsonObject(rows.get(i).getString("id"))));
                        }

                        return new JsonObject().put("period", live).put("label", label(live)).put("people", out)
                                .put("thresholds", thresholds.result().getJsonObject("index"));
                    });
                }));
    }

    // ------------------------------------------------------------------ submit

    /** Submits one person's review for calibration (reviews.calibrate; 422 on yourself; 409 when already submitted). */
    Future<JsonObject> submit(JsonObject user, String personId, JsonObject request)
    {
        return policy.refuseSelf(user, AuditEvents.REVIEWS_CALIBRATE, personId, null, request)
                .compose(v -> policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON))
                .compose(scope -> record(user, personId))
                .compose(done -> done ? review(user, personId)
                        : Future.failedFuture(ApiException.conflict("already_submitted", "This review is already submitted for calibration.")));
    }

    /**
     * Submits every review of the viewer's people that is not submitted yet (never the viewer's own);
     * people without an index are skipped. Resolves to {@code {submitted, skipped}}.
     */
    Future<JsonObject> submitTeam(JsonObject user)
    {
        return calibration(user).compose(table ->
        {
            Future<JsonObject> chain = Future.succeededFuture(new JsonObject().put("submitted", 0).put("skipped", 0));

            for (Object o : table.getJsonArray("people"))
            {
                JsonObject row = (JsonObject) o;

                if (row.getValue("submission") != null)
                {
                    continue;
                }

                chain = chain.compose(counts -> row.getValue("index") == null
                        ? Future.succeededFuture(counts.put("skipped", counts.getInteger("skipped") + 1))
                        : record(user, row.getString("id")).map(done -> done ? counts.put("submitted", counts.getInteger("submitted") + 1) : counts));
            }

            return chain;
        });
    }

    /** Freezes the live breakdown into a submission with an audit entry; false when already submitted or no index. */
    private Future<Boolean> record(JsonObject user, String personId)
    {
        String live = liveQuarter();

        return breakdown(personId, live).compose(b ->
        {
            if (b.getValue("index") == null)
            {
                return Future.failedFuture(ApiException.unprocessable("no_index", "There is no performance index for this quarter yet."));
            }

            JsonObject pillars = new JsonObject();

            b.getJsonArray("pillars").forEach(x -> pillars.put(((JsonObject) x).getString("key"), ((JsonObject) x).getValue("score")));

            BigDecimal index = BigDecimal.valueOf(b.getDouble("index")).setScale(2, RoundingMode.HALF_UP);

            return ctx.sql().inTransaction(tx -> new PerformanceRepository(tx)
                    .submit(UUID.fromString(personId), live.substring(2), index, pillars, b.getString("proposed"), UUID.fromString(user.getString("id")))
                    .compose(done -> !done ? Future.succeededFuture(false)
                            : AuditService.record(tx, AuditService.entry(user, AuditEvents.REVIEWS_CALIBRATE, "person", personId,
                                    new JsonObject().put("quarter", live.substring(2)).put("index", index.doubleValue())
                                            .put("proposed", b.getString("proposed")))).map(true)));
        });
    }

    // ------------------------------------------------------------------ helpers

    private static boolean canCalibrate(JsonObject user, String personId)
    {
        return PolicyService.permissions(user).contains(CALIBRATE) && !personId.equals(user.getString("personId"));
    }

    private Future<JsonObject> weights()
    {
        return SnapshotStore.currentRules(ctx.sql()).map(r -> r == null ? new JsonObject()
                : r.getJsonObject("rules", new JsonObject()).getJsonObject("weights", new JsonObject()));
    }

    private Future<Object> get(String formula, JsonObject params)
    {
        return ctx.calc().get(formula, params).map(ResultGuard::unguard);
    }

    private static Double round(Object value)
    {
        return value instanceof Number n ? Math.round(n.doubleValue() * 100) / 100.0 : null;
    }
}
