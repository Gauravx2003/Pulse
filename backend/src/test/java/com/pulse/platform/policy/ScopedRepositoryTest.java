package com.pulse.platform.policy;

import com.pulse.platform.db.ListQuery;
import com.pulse.platform.db.SqlExecutor;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 3.5, spec rbac-visibility "Repository without a scope": personal-data repository helpers
 * cannot run without a policy scope. ({@link Scope}'s constructor is package-private, so code outside
 * the policy package cannot build one: that part is enforced by the compiler.)
 */
class ScopedRepositoryTest
{
    private static final String PRIYA = UUID.randomUUID().toString();

    private static final String ARJUN = UUID.randomUUID().toString();

    private static final Vertx VERTX = Vertx.vertx();

    /** A pool that is never connected: any SQL reaching it would fail the test. */
    private static final SqlExecutor UNUSED = new SqlExecutor(PgPool.pool(VERTX,
            new PgConnectOptions().setHost("unused.invalid"), new PoolOptions().setMaxSize(1)));

    @AfterAll
    static void close()
    {
        VERTX.close();
    }

    /** A repository that would return rows from an executor that must never be reached. */
    private static final class People extends ScopedRepository
    {
        People()
        {
            super(UNUSED);
        }

        void read(Scope scope)
        {
            scopedList(scope, "p.id", "SELECT * FROM people p WHERE {scope}", Tuple.tuple());
        }

        void page(Scope scope)
        {
            scopedPage(scope, "p.id", ListQuery.from("SELECT * FROM people p"), MultiMap.caseInsensitiveMultiMap());
        }

        void unmarked(Scope scope)
        {
            scopedOne(scope, "p.id", "SELECT * FROM people p", Tuple.tuple());
        }
    }

    @Test
    void readWithoutScopeIsRejectedBeforeAnySql()
    {
        People repo = new People();

        assertThrows(NullPointerException.class, () -> repo.read(null));
        assertThrows(NullPointerException.class, () -> repo.page(null));
    }

    @Test
    void statementMustMarkWhereTheScopeGoes()
    {
        assertThrows(IllegalArgumentException.class, () -> new People().unmarked(scope(false, PRIYA)));
    }

    @Test
    void scopeConditionIsNumberedAfterTheStatementParameters()
    {
        var bound = ScopedRepository.bind(scope(false, PRIYA, ARJUN), "o.person_id", (Scope.Owner) null,
                "SELECT * FROM okrs o WHERE o.quarter = $1 AND {scope} ORDER BY o.id", Tuple.of("Q3"));

        assertEquals("SELECT * FROM okrs o WHERE o.quarter = $1 AND o.person_id = ANY($2) ORDER BY o.id", bound.sql());
        assertEquals(2, bound.params().size());
        assertEquals("Q3", bound.params().getValue(0));
        assertArrayEquals(new UUID[] {UUID.fromString(PRIYA), UUID.fromString(ARJUN)}, (UUID[]) bound.params().getValue(1));
    }

    @Test
    void ownerColumnWidensToRowsTheViewerOwns()
    {
        Scope.Condition c = scope(false, PRIYA).condition("v.person_id", "v.owner_id");

        assertEquals("(v.person_id = ANY(?) OR v.owner_id = ?)", c.sql());
        assertEquals(UUID.fromString(PRIYA), c.values().get(1));
    }

    @Test
    void userOwnerColumnMatchesTheViewersAccountEvenWithoutAProfile()
    {
        String sanaUser = UUID.randomUUID().toString();

        // an account owner who is a user without a performance profile (no person id)
        Scope sana = new Scope("visit", sanaUser, null, false, Set.of());

        Scope.Condition byUser = sana.condition("v.person_id", Scope.Owner.user("v.owner_id"));

        assertEquals("(v.person_id = ANY(?) OR v.owner_id = ?)", byUser.sql());
        assertEquals(UUID.fromString(sanaUser), byUser.values().get(1));

        // the person-id form has nothing to compare for her, so it adds no owner clause
        assertEquals("v.person_id = ANY(?)", sana.condition("v.person_id", Scope.Owner.person("v.owner_id")).sql());

        var bound = ScopedRepository.bind(sana, "v.person_id", Scope.Owner.user("v.owner_id"),
                "SELECT * FROM visits v WHERE v.id = $1 AND {scope}", Tuple.of(UUID.randomUUID()));

        assertEquals("SELECT * FROM visits v WHERE v.id = $1 AND (v.person_id = ANY($2) OR v.owner_id = $3)", bound.sql());
        assertEquals(UUID.fromString(sanaUser), bound.params().getValue(2));

        assertThrows(IllegalArgumentException.class, () -> Scope.Owner.user("v.owner_id OR TRUE"));
    }

    @Test
    void allViewIsUnfilteredAndEmptyScopeMatchesNothing()
    {
        assertEquals(new Scope.Condition("TRUE", List.of()), scope(true).condition("p.id"));

        Scope none = new Scope("person", UUID.randomUUID().toString(), null, false, Set.of());

        assertEquals(0, ((UUID[]) none.condition("p.id").values().get(0)).length);
        assertFalse(none.allows(PRIYA));
    }

    @Test
    void columnNamesAreNotInjectable()
    {
        assertThrows(IllegalArgumentException.class, () -> scope(false, PRIYA).condition("p.id OR TRUE"));
        assertThrows(IllegalArgumentException.class, () -> scope(false, PRIYA).condition("p.id; DROP TABLE people"));
    }

    @Test
    void checkInTimesCannotBeWidenedByARelation()
    {
        assertThrows(IllegalArgumentException.class, () -> new VisibilityRelation(PolicyService.DOMAIN_CHECKIN, "mentor",
                "SELECT mentee_id AS person_id FROM m WHERE mentor_id = $1"));
    }

    @Test
    void unknownPermissionFailsAtMountTime()
    {
        PolicyService policy = new PolicyService(UNUSED, Set.of("team.view"), List.of(), false);

        assertThrows(IllegalArgumentException.class, () -> policy.requires("team.veiw"));
        assertThrows(IllegalArgumentException.class, () -> policy.requires());
    }

    @Test
    void separationOfDutiesPassesForSomeoneElse()
    {
        PolicyService policy = new PolicyService(UNUSED, Set.of(), List.of(), false);

        JsonObject user = new JsonObject().put("id", UUID.randomUUID().toString()).put("personId", PRIYA);

        assertTrue(policy.refuseSelf(user, "effort.confirm", ARJUN, null, null).succeeded());
        assertTrue(policy.refuseSelf(user, "no.such.action", ARJUN, null, null).failed());
    }

    @Test
    void modulesCanDeclareTheirOwnSeparationOfDutiesActions()
    {
        PolicyService policy = new PolicyService(UNUSED, Set.of(), List.of(), false,
                java.util.Map.of("kudos.give", "You can't give yourself kudos."));

        JsonObject user = new JsonObject().put("id", UUID.randomUUID().toString()).put("personId", PRIYA);

        assertEquals("You can't give yourself kudos.", policy.selfRefused().get("kudos.give"));
        assertTrue(policy.selfRefused().containsKey("effort.confirm"));
        assertTrue(policy.refuseSelf(user, "kudos.give", ARJUN, null, null).succeeded());

        assertThrows(IllegalStateException.class, () -> new PolicyService(UNUSED, Set.of(), List.of(), false,
                java.util.Map.of("effort.confirm", "Duplicate of a platform action.")));
        assertThrows(IllegalStateException.class, () -> new PolicyService(UNUSED, Set.of(), List.of(), false,
                java.util.Map.of("kudos.give", " ")));
    }

    private static Scope scope(boolean all, String... people)
    {
        return new Scope("person", UUID.randomUUID().toString(), PRIYA, all, new LinkedHashSet<>(List.of(people)));
    }
}
