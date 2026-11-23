package com.pulse.modules.roadmap;

import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.policy.Scope;
import com.pulse.platform.policy.ScopedRepository;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;

import java.util.UUID;

/**
 * Milestones and the skills matrix of a road map (tasks 15.1 and 15.2). Every read and lock takes a
 * {@link Scope} of the {@code person} domain, so only the person, their manager and {@code all.view}
 * reach a road map.
 */
public class RoadmapRepository extends ScopedRepository
{

    private static final String MILESTONE = """
            SELECT m.id, m.person_id AS "personId", m.sort_order AS "order", m.title, m.when_label AS "when", m.stage,
                   m.progress_pct AS progress, m.note, m.culture_value AS value, m.linked_issues AS "linkedIssues",
                   m.status, m.confirmed_at AS "confirmedAt", m.version
            FROM milestones m
            """;

    private static final String SKILL = """
            SELECT s.id, s.person_id AS "personId", s.sort_order AS "order", s.skill, s.current_level AS current,
                   s.needed_level AS needed, s.rated_at AS "ratedAt", s.version
            FROM skills_matrix s
            """;

    public RoadmapRepository(SqlExecutor sql)
    {
        super(sql);
    }

    /**
     * A person's milestones in timeline order.
     */
    public Future<JsonArray> milestones(Scope scope, UUID personId)
    {
        return scopedList(scope, "m.person_id", MILESTONE + " WHERE m.person_id = $1 AND {scope} ORDER BY m.sort_order, m.id",
                Tuple.of(personId));
    }

    /**
     * Proposed milestones of the people in scope, oldest first (for the manager's Action center).
     */
    public Future<JsonArray> proposed(Scope scope)
    {
        return scopedList(scope, "m.person_id", """
                SELECT m.id, m.person_id AS "personId", p.name AS "personName", m.title, m.when_label AS "when"
                FROM milestones m JOIN people p ON p.id = m.person_id
                WHERE m.status = 'proposed' AND {scope}
                ORDER BY m.created_at, m.id""", Tuple.tuple());
    }

    /**
     * Adds a proposed milestone at the end of the timeline.
     */
    public Future<JsonObject> propose(UUID personId, String title, String when, String value, UUID by)
    {
        return queryOne("""
                INSERT INTO milestones (person_id, sort_order, title, when_label, stage, note, culture_value, status, proposed_by)
                VALUES ($1, (SELECT coalesce(max(sort_order), 0) + 1 FROM milestones WHERE person_id = $1), $2, $3, 'next',
                        'Proposed', $4, 'proposed', $5)
                RETURNING id""", Tuple.of(personId, title, when, value, by))
                .compose(row -> milestone(UUID.fromString(row.getString("id"))));
    }

    /**
     * One visible milestone, locked until the transaction ends, or {@code null}.
     */
    public Future<JsonObject> lockMilestone(Scope scope, UUID id)
    {
        return scopedOne(scope, "m.person_id", MILESTONE + " WHERE m.id = $1 AND {scope} FOR UPDATE OF m", Tuple.of(id));
    }

    /**
     * Confirms a milestone on the version the manager saw.
     */
    public Future<JsonObject> confirm(UUID id, UUID by, int expectedVersion)
    {
        return updateVersioned("milestones",
                "status = 'confirmed', confirmed_by = $1, confirmed_at = now(), note = CASE WHEN note = 'Proposed' THEN 'Planned' ELSE note END",
                Tuple.of(by), id, expectedVersion)
                .compose(row -> milestone(id));
    }

    /**
     * A person's skills matrix in order.
     */
    public Future<JsonArray> skills(Scope scope, UUID personId)
    {
        return scopedList(scope, "s.person_id", SKILL + " WHERE s.person_id = $1 AND {scope} ORDER BY s.sort_order, s.id",
                Tuple.of(personId));
    }

    /**
     * Rates a skill: updates the person's skill of that name (any case) or adds it at the end.
     */
    public Future<JsonObject> rate(UUID personId, String skill, int current, int needed, UUID by)
    {
        return queryOne("""
                INSERT INTO skills_matrix (person_id, sort_order, skill, current_level, needed_level, rated_by)
                VALUES ($1, (SELECT coalesce(max(sort_order), 0) + 1 FROM skills_matrix WHERE person_id = $1), $2, $3, $4, $5)
                ON CONFLICT (person_id, lower(skill)) DO UPDATE SET current_level = $3, needed_level = $4, rated_by = $5,
                    rated_at = now(), updated_at = now(), version = skills_matrix.version + 1
                RETURNING id""", Tuple.of(personId, skill, current, needed, by))
                .compose(row -> queryOne(SKILL + " WHERE s.id = $1", Tuple.of(UUID.fromString(row.getString("id")))));
    }

    /**
     * Removes one of the person's skills; resolves to the number of rows removed.
     */
    public Future<Integer> removeSkill(UUID personId, UUID skillId)
    {
        return execute("DELETE FROM skills_matrix WHERE id = $1 AND person_id = $2", Tuple.of(skillId, personId));
    }

    private Future<JsonObject> milestone(UUID id)
    {
        return queryOne(MILESTONE + " WHERE m.id = $1", Tuple.of(id));
    }
}
