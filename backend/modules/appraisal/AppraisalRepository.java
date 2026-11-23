package com.pulse.modules.appraisal;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Appraisal cycles and proposals (V360); CTC is read from {@code people.ctc}, scores from calc. */
final class AppraisalRepository extends ScopedRepository
{
    private static final String PROPOSAL = """
            SELECT id, person_id AS "personId", pct, reason, promotion, status, send_back_reason AS "sendBackReason",
                   submitted_at AS "submittedAt", decided_at AS "decidedAt", released_at AS "releasedAt",
                   snapshot_id AS "snapshotId", version
            FROM appraisal_proposals
            """;

    AppraisalRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /** The cycle of a year, created with the default effective date (1 October) on first use. */
    Future<JsonObject> cycle(int year)
    {
        return execute("INSERT INTO appraisal_cycles (year, effective_on) VALUES ($1, $2) ON CONFLICT (year) DO NOTHING",
                        Tuple.of(year, LocalDate.of(year, 10, 1)))
                .compose(n -> queryOne("SELECT id, year, effective_on AS \"effectiveOn\", version FROM appraisal_cycles WHERE year = $1",
                        Tuple.of(year)));
    }

    Future<JsonObject> setEffectiveOn(UUID cycleId, LocalDate effectiveOn, int expectedVersion)
    {
        return updateVersioned("appraisal_cycles", "effective_on = $1", Tuple.of(effectiveOn), cycleId, expectedVersion);
    }

    /** People in the salary scope: {@code [{id, name, title, level, department, userId, joinedOn, ctc}]}, by name. */
    Future<JsonArray> people(Scope scope)
    {
        return scopedList(scope, "p.id", """
                SELECT p.id, p.name, p.title, p.level, p.department, p.user_id AS "userId", p.joined_on AS "joinedOn", p.ctc
                FROM people p WHERE {scope} ORDER BY p.name
                """,
                Tuple.tuple());
    }

    Future<JsonArray> proposals(UUID cycleId)
    {
        return queryList(PROPOSAL + " WHERE cycle_id = $1", Tuple.of(cycleId));
    }

    Future<JsonObject> proposal(UUID cycleId, UUID personId)
    {
        return queryOne(PROPOSAL + " WHERE cycle_id = $1 AND person_id = $2 FOR UPDATE", Tuple.of(cycleId, personId));
    }

    /** Creates or edits a Draft (or Sent back) proposal; resolves to the row, or null when it is past Draft. */
    Future<JsonObject> saveDraft(UUID cycleId, UUID personId, BigDecimal pct, String reason, boolean promotion, UUID by)
    {
        return queryOne("""
                INSERT INTO appraisal_proposals (cycle_id, person_id, pct, reason, promotion, proposed_by)
                VALUES ($1, $2, $3, $4, $5, $6)
                ON CONFLICT (cycle_id, person_id) DO UPDATE
                   SET pct = EXCLUDED.pct, reason = EXCLUDED.reason, promotion = EXCLUDED.promotion, status = 'Draft',
                       proposed_by = EXCLUDED.proposed_by, updated_at = now(), version = appraisal_proposals.version + 1
                 WHERE appraisal_proposals.status IN ('Draft', 'Sent back')
                RETURNING id, version
                """,
                Tuple.tuple().addValue(cycleId).addValue(personId).addValue(pct).addValue(reason).addValue(promotion).addValue(by));
    }

    Future<JsonObject> submit(UUID id, UUID by, UUID snapshotId)
    {
        return queryOne("""
                UPDATE appraisal_proposals SET status = 'Submitted', submitted_by = $2, submitted_at = now(), snapshot_id = $3,
                       send_back_reason = NULL, updated_at = now(), version = version + 1
                WHERE id = $1 AND status = 'Draft' RETURNING id, version
                """,
                Tuple.of(id, by, snapshotId));
    }

    /** Moves a proposal from one status to another; resolves to the row, or null when it was not in {@code from}. */
    Future<JsonObject> decide(UUID id, String from, String to, UUID by, String sendBackReason)
    {
        return queryOne("""
                UPDATE appraisal_proposals SET status = $3, decided_by = $4, decided_at = now(),
                       send_back_reason = coalesce($5, send_back_reason),
                       released_at = CASE WHEN $3 = 'Released' THEN now() ELSE released_at END,
                       updated_at = now(), version = version + 1
                WHERE id = $1 AND status = $2 RETURNING id, version
                """,
                Tuple.tuple().addValue(id).addValue(from).addValue(to).addValue(by).addValue(sendBackReason));
    }

    /** The person's latest 360° cycle (not a training checkpoint) with a submitted review of them, or null. */
    Future<JsonObject> latest360(UUID personId)
    {
        return queryOne("SELECT to_regclass('review_requests') IS NOT NULL AS installed", Tuple.tuple()).compose(t -> !t.getBoolean("installed")
                ? Future.succeededFuture(null)
                : queryOne("""
                        SELECT c.id, c.name FROM review_cycles c
                        WHERE c.kind = '360' AND EXISTS (SELECT 1 FROM review_requests r WHERE r.cycle_id = c.id AND r.subject_id = $1
                                                         AND r.status = 'submitted')
                        ORDER BY c.due_on DESC LIMIT 1
                        """,
                        Tuple.of(personId)));
    }
}
