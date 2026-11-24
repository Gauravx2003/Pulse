package com.pulse.modules.culture;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.CultureParameters;
import com.pulse.calc.core.ResultGuard;
import com.pulse.calc.core.SnapshotStore;
import com.pulse.calc.people.CultureInputsFormulas;
import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Culture values (task 19.1, spec culture/culture-values). Every number comes from the calc service
 * ({@code core.culture}, {@code core.pillars}, {@code core.discipline}, {@code people.cultureDetail}); this
 * service picks the person, checks visibility, adds the definitions and the evidence, and keeps the
 * Discipline override (reviews.calibrate, never your own, audited both ways).
 */
final class CultureService
{
    static final int MIN_REASON = 10;

    static final String OVERRIDE_CLEARED = "discipline.override.cleared";

    private final ModuleContext ctx;

    private final PolicyService policy;

    CultureService(ModuleContext ctx)
    {
        this.ctx = ctx;

        this.policy = ctx.policy();
    }

    LocalDate today()
    {
        return ctx.calc() == null ? LocalDate.now(PolicyService.ZONE) : ctx.calc().clock().today();
    }

    /** The quarter key of a day, e.g. {@code q:2026-Q3}. */
    static String quarterOf(LocalDate day)
    {
        return "q:" + day.getYear() + "-Q" + ((day.getMonthValue() - 1) / 3 + 1);
    }

    // ------------------------------------------------------------------ periods and rules

    /** The period selector: this quarter and the three before it, newest first; the default is this quarter. */
    JsonObject periods()
    {
        JsonArray items = new JsonArray();

        LocalDate d = today();

        for (int i = 0; i < 4; i++)
        {
            LocalDate q = d.minusMonths(3L * i);

            String key = quarterOf(q);

            items.add(new JsonObject().put("key", key).put("label", key.substring(7) + " " + q.getYear()).put("live", i == 0));
        }

        return new JsonObject().put("items", items).put("default", quarterOf(d));
    }

    /** The calculation rules for all values: weight, data share, definitions and data parameters. */
    Future<JsonObject> rules()
    {
        return Future.all(new CultureRepository(ctx.sql()).values(), rulesNow()).map(both ->
                {
                    JsonArray values = both.resultAt(0);

                    JsonObject rules = both.resultAt(1);

                    JsonArray weights = rules.getJsonArray("vw", new JsonArray());

                    double share = rules.getDouble("dataShare", 40d);

                    JsonArray out = new JsonArray();

                    for (int i = 0; i < CultureParameters.VALUES.size(); i++)
                    {
                        String value = CultureParameters.VALUES.get(i);

                        boolean moral = CultureParameters.MORAL_AND_ETHICS.equals(value);

                        boolean disc = CultureParameters.DISCIPLINE.equals(value);

                        out.add(new JsonObject()
                                .put("value", value)
                                .put("weight", weights.size() > i ? weights.getValue(i) : null)
                                .put("dataShare", moral ? 0 : disc ? 100 : share)
                                .put("note", moral ? "360° only" : disc ? "Data only (rules a–h)" : null)
                                .put("definition", definition(values, value))
                                .put("parameters", moral || disc ? new JsonArray() : CultureParameters.of(value)));
                    }

                    return new JsonObject().put("values", out).put("dataShare", share)
                            .put("bonusPerPoint", rules.getDouble("bonusPerPoint", 0.05)).put("bonusCap", rules.getDouble("bonusCap", 0.3));
                });
    }

    private Future<JsonObject> rulesNow()
    {
        return SnapshotStore.currentRules(ctx.sql()).map(r -> r == null ? new JsonObject() : r.getJsonObject("rules"));
    }

    private static JsonObject definition(JsonArray values, String value)
    {
        for (Object o : values)
        {
            if (value.equals(((JsonObject) o).getString("name")))
            {
                JsonObject v = (JsonObject) o;

                return new JsonObject().put("description", v.getString("description")).put("signals", array(v.getValue("signals")))
                        .put("anchors", array(v.getValue("anchors")));
            }
        }

        return null;
    }

    /** Whether a detail explains the shown score (a snapshot score may come from older inputs). */
    private static boolean same(Object score, JsonObject detail)
    {
        return score instanceof Number n && detail != null && detail.getValue("score") instanceof Number d
                && Math.abs(n.doubleValue() - d.doubleValue()) < 1e-9;
    }

    /** A jsonb column as an array (the driver may hand it over as text). */
    private static JsonArray array(Object value)
    {
        return value instanceof JsonArray a ? a : value instanceof String text && text.startsWith("[") ? new JsonArray(text) : new JsonArray();
    }

    // ------------------------------------------------------------------ a person

    /**
     * One person's culture page for a quarter: the 11 values with their parts, the culture pillar, the team
     * average per value (the viewer's people), the Discipline score with any override, and the evidence log.
     */
    Future<JsonObject> person(JsonObject user, String personId, String period)
    {
        CultureRepository repo = new CultureRepository(ctx.sql());

        JsonObject pp = new JsonObject().put("person", personId).put("period", period);

        return policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON).compose(scope ->
        {
            List<Future<Object>> details = new ArrayList<>();

            for (String value : CultureParameters.VALUES)
            {
                details.add(get("people.cultureDetail", pp.copy().put("value", value)));
            }

            Future<Object> scores = get("core.culture", pp);

            Future<Object> pillars = get("core.pillars", pp);

            Future<Object> discipline = get("core.discipline", new JsonObject().put("person", personId));

            Future<JsonObject> override = repo.override(UUID.fromString(personId));

            Future<JsonArray> team = policy.scope(user, PolicyService.DOMAIN_PERSON).compose(repo::people).compose(people -> average(people, period));

            Future<JsonArray> evidence = evidence(personId, period, null);

            Future<JsonObject> who = repo.person(UUID.fromString(personId));

            return Future.all(List.of(Future.all(details), scores, pillars, discipline, override, team, evidence, who)).map(all ->
            {
                // Scores from core.culture (a closed quarter answers from its snapshot); the parts explain a live one.
                JsonArray shown = scores.result() instanceof JsonArray a ? a : null;

                JsonArray values = new JsonArray();

                for (int i = 0; i < CultureParameters.VALUES.size(); i++)
                {
                    JsonObject d = details.get(i).result() instanceof JsonObject o ? o : null;

                    Object score = shown == null || shown.size() <= i ? null : shown.getValue(i);

                    values.add(new JsonObject()
                            .put("value", CultureParameters.VALUES.get(i))
                            .put("score", score)
                            .put("parts", !same(score, d) ? null : d.getJsonObject("parts")));
                }

                boolean scored = shown != null;

                JsonObject p = pillars.result() instanceof JsonObject o ? o : new JsonObject();

                return new JsonObject()
                        .put("person", who.result())
                        .put("period", period)
                        .put("scored", scored)
                        .put("values", values)
                        .put("pillar", p.getValue("culture"))
                        .put("teamAverage", team.result())
                        .put("discipline", disciplineView((JsonObject) discipline.result(), override.result()))
                        .put("evidence", evidence.result())
                        .put("canOverride", canOverride(user, personId));
            });
        }).recover(this::periodError);
    }

    /** The detail of one value: the formula parts, raters, parameters, bonus items, anchors and evidence. */
    Future<JsonObject> value(JsonObject user, String personId, String value, String period)
    {
        if (!CultureParameters.VALUES.contains(value))
        {
            return Future.failedFuture(PolicyService.notFound());
        }

        JsonObject pp = new JsonObject().put("person", personId).put("period", period).put("value", value);

        return policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON).compose(scope ->
                Future.all(get("people.cultureDetail", pp), new CultureRepository(ctx.sql()).values(), evidence(personId, period, value)).map(all ->
                {
                    JsonObject detail = all.resultAt(0) instanceof JsonObject d ? d : null;

                    JsonObject def = definition(all.resultAt(1), value);

                    Integer band = detail == null || detail.getValue("score") == null ? null
                            : (int) Math.max(1, Math.min(5, Math.round(detail.getDouble("score"))));

                    return new JsonObject()
                            .put("value", value)
                            .put("period", period)
                            .put("detail", detail)
                            .put("definition", def)
                            .put("currentBand", band)
                            .put("evidence", all.resultAt(2));
                })).recover(this::periodError);
    }

    /** The Discipline view: rules a–h with the metrics, adjustments, base, automatic score and override. */
    Future<JsonObject> discipline(JsonObject user, String personId)
    {
        return policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON).compose(scope ->
                Future.all(get("core.discipline", new JsonObject().put("person", personId)), new CultureRepository(ctx.sql())
                        .override(UUID.fromString(personId))).map(all -> disciplineView(all.resultAt(0), all.resultAt(1))
                        .put("canOverride", canOverride(user, personId))));
    }

    // ------------------------------------------------------------------ Discipline override

    /** Overrides a person's Discipline score (reviews.calibrate; 422 on your own; reason of 10+ characters). */
    Future<JsonObject> setOverride(JsonObject user, String personId, double score, String reason, JsonObject request)
    {
        if (score < 1 || score > 5 || score * 4 != Math.rint(score * 4))
        {
            return Future.failedFuture(ApiException.unprocessable("invalid_score", "Use a score from 1.0 to 5.0 in steps of 0.25.").field("score"));
        }

        String why = reason == null ? "" : reason.strip();

        if (why.length() < MIN_REASON)
        {
            return Future.failedFuture(ApiException.unprocessable("reason_required", "Give a reason of at least " + MIN_REASON
                    + " characters; the employee sees it.").field("reason"));
        }

        return policy.refuseSelf(user, AuditEvents.DISCIPLINE_OVERRIDE, personId, null, request)
                .compose(v -> policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON))
                .compose(scope -> ctx.sql().inTransaction(tx -> new CultureRepository(tx)
                        .setOverride(UUID.fromString(personId), BigDecimal.valueOf(score), why, UUID.fromString(user.getString("id")))
                        .compose(row -> AuditService.record(tx, AuditService.entry(user, AuditEvents.DISCIPLINE_OVERRIDE, "person", personId,
                                new JsonObject().put("score", score).put("reason", why))))))
                .compose(v -> changed())
                .compose(v -> discipline(user, personId));
    }

    /** Returns the person to the automatic score (reviews.calibrate; never your own). */
    Future<JsonObject> clearOverride(JsonObject user, String personId, JsonObject request)
    {
        return policy.refuseSelf(user, AuditEvents.DISCIPLINE_OVERRIDE, personId, null, request)
                .compose(v -> policy.requireVisible(user, personId, PolicyService.DOMAIN_PERSON))
                .compose(scope -> ctx.sql().inTransaction(tx -> new CultureRepository(tx)
                        .clearOverride(UUID.fromString(personId), UUID.fromString(user.getString("id")))
                        .compose(n -> n == 0 ? Future.failedFuture(ApiException.conflict("no_override", "This Discipline score is already automatic."))
                                : AuditService.record(tx, AuditService.entry(user, OVERRIDE_CLEARED, "person", personId, new JsonObject())))))
                .compose(v -> changed())
                .compose(v -> discipline(user, personId));
    }

    // ------------------------------------------------------------------ helpers

    private Future<Void> changed()
    {
        ctx.calc().invalidate(CultureInputsFormulas.OVERRIDES);

        CalcService.publish(ctx.vertx(), CultureInputsFormulas.OVERRIDES);

        return Future.succeededFuture();
    }

    private static boolean canOverride(JsonObject user, String personId)
    {
        return PolicyService.permissions(user).contains("reviews.calibrate") && !personId.equals(user.getString("personId"));
    }

    private static JsonObject disciplineView(JsonObject discipline, JsonObject override)
    {
        JsonObject d = discipline == null ? new JsonObject() : discipline.copy();

        return d.put("override", override);
    }

    /** Average score per value over the people (null for a value nobody has). */
    private Future<JsonArray> average(JsonArray people, String period)
    {
        List<Future<Object>> scores = new ArrayList<>();

        for (Object o : people)
        {
            scores.add(get("core.culture", new JsonObject().put("person", ((JsonObject) o).getString("id")).put("period", period)));
        }

        return Future.all(scores).map(all ->
        {
            double[] sums = new double[CultureParameters.VALUES.size()];

            int[] n = new int[sums.length];

            for (Future<Object> f : scores)
            {
                if (f.result() instanceof JsonArray a)
                {
                    for (int i = 0; i < sums.length && i < a.size(); i++)
                    {
                        if (a.getValue(i) instanceof Number x)
                        {
                            sums[i] += x.doubleValue();

                            n[i]++;
                        }
                    }
                }
            }

            JsonArray out = new JsonArray();

            for (int i = 0; i < sums.length; i++)
            {
                out.add(n[i] == 0 ? null : Math.round(sums[i] / n[i] * 100) / 100.0);
            }

            return out;
        });
    }

    /**
     * The evidence log: kudos received in the quarter and the confirmed extra-mile items crediting the values
     * (one value when {@code value} is given), newest first. Kudos are evidence, never a score.
     */
    private Future<JsonArray> evidence(String personId, String period, String value)
    {
        return ctx.calc().get("core.period", new JsonObject().put("period", period)).compose(p ->
        {
            JsonObject q = (JsonObject) p;

            CultureRepository repo = new CultureRepository(ctx.sql());

            Future<JsonArray> kudos = repo.kudos(UUID.fromString(personId), LocalDate.parse(q.getString("from")),
                    LocalDate.parse(q.getString("to")));

            Future<JsonArray> items = repo.extraItems(UUID.fromString(personId), LocalDate.parse(q.getString("from")),
                    LocalDate.parse(q.getString("to")));

            return Future.all(kudos, items).map(all ->
            {
                JsonArray out = new JsonArray();

                for (Object o : kudos.result())
                {
                    JsonObject k = (JsonObject) o;

                    if (value == null || value.equals(k.getString("value")))
                    {
                        out.add(new JsonObject().put("kind", "kudos").put("at", String.valueOf(k.getValue("givenAt"))).put("value", k.getString("value"))
                                .put("text", k.getString("message")).put("by", k.getString("fromName")));
                    }
                }

                for (Object o : items.result())
                {
                    JsonObject it = (JsonObject) o;

                    JsonArray credits = it.getJsonArray("values", new JsonArray());

                    if (value == null || credits.contains(value))
                    {
                        out.add(new JsonObject().put("kind", "extra-mile").put("at", String.valueOf(it.getValue("occurredOn")))
                                .put("values", credits).put("text", it.getString("title")).put("source", it.getString("source")));
                    }
                }

                out.getList().sort((a, b) -> String.valueOf(((JsonObject) b).getString("at")).compareTo(String.valueOf(((JsonObject) a).getString("at"))));

                return out;
            });
        });
    }

    /** A formula's value with missing parts as plain nulls. */
    private Future<Object> get(String formula, JsonObject params)
    {
        return ctx.calc().get(formula, params).map(ResultGuard::unguard);
    }

    private <T> Future<T> periodError(Throwable error)
    {
        return Future.failedFuture(error instanceof IllegalArgumentException
                ? ApiException.badRequest("invalid_parameter", error.getMessage()).field("period") : error);
    }
}
