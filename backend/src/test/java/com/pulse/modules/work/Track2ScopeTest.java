package com.pulse.modules.work;

import com.pulse.modules.bugs.BugRepository;
import com.pulse.modules.effort.ExtraItemRepository;
import com.pulse.modules.progress.PulseRepository;
import com.pulse.modules.pull_requests.PullRequestRepository;
import com.pulse.platform.db.Db;
import com.pulse.platform.db.MigrationRunner;
import com.pulse.platform.db.ScriptCatalog;
import com.pulse.platform.db.SqlExecutor;
import com.pulse.platform.db.TestDb;
import com.pulse.platform.policy.PolicyService;
import com.pulse.platform.policy.Scope;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Tuple;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.BiFunction;

import static com.pulse.platform.db.TestDb.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 7.3: every read of every Track 2 repository needs a policy scope, and through none of them can
 * an Employee read a peer's issues, PRs, bugs, tickets, extra-mile items or weekly pulse. Scopes come
 * from the real {@link PolicyService} on the demo organisation: Priya (Employee) and Arjun (her peer)
 * report to Rahul (Manager, team.view); HR holds all.view.
 */
class Track2ScopeTest
{

    private static final LocalDate FROM = LocalDate.parse("2026-09-01");

    private static final LocalDate TO = LocalDate.parse("2026-09-30");

    private static TestDb db;

    private static SqlExecutor owner;

    private static SqlExecutor sql;

    private static PolicyService policy;

    private static final Map<String, JsonObject> USERS = new LinkedHashMap<>();

    /**
     * Ids of the rows that belong to each person ("ps" Priya, "am" Arjun).
     */
    private static final Map<String, JsonObject> ROWS = new LinkedHashMap<>();

    /**
     * Every personal read of the Track 2 repositories, given a scope and the target person's rows.
     */
    private static final Map<String, BiFunction<Scope, JsonObject, Future<?>>> READS = new LinkedHashMap<>();

    @BeforeAll
    static void setUp() throws Exception
    {
        db = TestDb.open();

        ScriptCatalog catalog = ScriptCatalog.load(Track2ScopeTest.class.getClassLoader(),
                ScriptCatalog.sourcesFor(List.of(new WorkModule())));

        await(MigrationRunner.migrate(db.ownerPool(), catalog));

        await(MigrationRunner.seed(db.ownerPool(), catalog, true));

        owner = new SqlExecutor(db.ownerPool());

        sql = new SqlExecutor(db.pool(Db.DEFAULT_APP_ROLE));

        policy = new PolicyService(sql, Set.of(), List.of(), false);

        for (String email : List.of("priya@pulse.demo", "arjun@pulse.demo", "rahul@pulse.demo", "hr@pulse.demo"))
        {
            USERS.put(email, user(email));
        }

        ROWS.put("ps", rowsFor("ps", "am"));

        ROWS.put("am", rowsFor("am", "ps"));

        IssueRepository issues = new IssueRepository(sql);

        PullRequestRepository prs = new PullRequestRepository(sql);

        BugRepository bugs = new BugRepository(sql);

        ExtraItemRepository extras = new ExtraItemRepository(sql);

        PulseRepository pulse = new PulseRepository(sql);

        READS.put("issues.forPerson", (s, r) -> issues.forPerson(s, id(r, "person"), null));
        READS.put("issues.byKey", (s, r) -> issues.byKey(s, r.getString("issueKey")));
        READS.put("issues.events", (s, r) -> issues.events(s, id(r, "issue")));
        READS.put("issues.worklogs", (s, r) -> issues.worklogs(s, id(r, "person"), FROM, TO));
        READS.put("issues.commitDays", (s, r) -> issues.commitDays(s, id(r, "person"), FROM, TO));
        READS.put("issues.page", (s, r) -> issues.page(s, MultiMap.caseInsensitiveMultiMap().add("assigneeId", r.getString("person"))));
        READS.put("prs.byAuthor", (s, r) -> prs.byAuthor(s, id(r, "person"), null, null));
        READS.put("prs.byId", (s, r) -> prs.byId(s, id(r, "pr")));
        READS.put("prs.reviews", (s, r) -> prs.reviews(s, id(r, "pr")));
        READS.put("prs.comments", (s, r) -> prs.comments(s, id(r, "pr")));
        READS.put("prs.reviewsGiven", (s, r) -> prs.reviewsGiven(s, id(r, "person"), FROM, TO));
        READS.put("bugs.inCodeOf", (s, r) -> bugs.inCodeOf(s, id(r, "person")));
        READS.put("bugs.fixedForOthersBy", (s, r) -> bugs.fixedForOthersBy(s, id(r, "person")));
        READS.put("bugs.byKey", (s, r) -> bugs.byKey(s, r.getString("bugKey")));
        READS.put("bugs.ticketsOf", (s, r) -> bugs.ticketsOf(s, id(r, "person")));
        READS.put("extras.forPerson", (s, r) -> extras.forPerson(s, id(r, "person"), FROM, TO));
        READS.put("extras.byId", (s, r) -> extras.byId(s, id(r, "extra")));
        READS.put("pulse.forPerson", (s, r) -> pulse.forPerson(s, id(r, "person")));
        READS.put("pulse.latest", (s, r) -> pulse.latest(s, id(r, "person")));
    }

    @AfterAll
    static void tearDown() throws Exception
    {
        if (db != null)
        {
            db.close();
        }
    }

    @Test
    void anEmployeeCannotReadAPeersDataThroughAnyRepository() throws Exception
    {
        for (String read : READS.keySet())
        {
            assertFalse(seen("priya@pulse.demo", "am", read), "Priya read Arjun's data through " + read);

            assertFalse(seen("arjun@pulse.demo", "ps", read), "Arjun read Priya's data through " + read);
        }
    }

    @Test
    void everyoneSeesTheirOwnDataAndTheManagerAndAdminSeeTheReports() throws Exception
    {
        for (String read : READS.keySet())
        {
            assertTrue(seen("priya@pulse.demo", "ps", read), "Priya's own data through " + read);

            assertTrue(seen("rahul@pulse.demo", "am", read), "Rahul, Arjun's manager, through " + read);

            assertTrue(seen("hr@pulse.demo", "am", read), "HR (all.view) through " + read);
        }
    }

    @Test
    void theFixerOfABugInSomeoneElsesCodeSeesIt() throws Exception
    {
        String key = "BUG-T-AM-FIX";

        await(owner.execute("""
                INSERT INTO bugs (key, title, severity, status, environment, origin_person_id, assignee_id, reported_on,
                                  source, external_id)
                VALUES ($1, 'Fix picked up by Priya', 'Low', 'In Progress', 'Staging', $2::uuid, $3::uuid, '2026-09-20',
                        'test', $1)""",
                Tuple.of(key, ROWS.get("am").getString("person"), ROWS.get("ps").getString("person"))));

        Scope priya = scope("priya@pulse.demo", PolicyService.DOMAIN_PERSON);

        assertNotNull(await(new BugRepository(sql).byKey(priya, key)), "Priya is fixing it");

        assertTrue(await(new BugRepository(sql).inCodeOf(priya, id(ROWS.get("am"), "person"))).isEmpty(),
                "but Arjun's other bugs stay hidden");
    }

    @Test
    void pulseNeedsAPulseScopeAndTheQueueNeedsNone() throws Exception
    {
        Scope person = scope("hr@pulse.demo", PolicyService.DOMAIN_PERSON);

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> await(new PulseRepository(sql).forPerson(person, id(ROWS.get("ps"), "person"))));

        assertInstanceOf(IllegalArgumentException.class, refused.getCause());

        assertThrows(NullPointerException.class, () -> new IssueRepository(sql).forPerson(null, id(ROWS.get("ps"), "person"), null));

        await(owner.execute("""
                INSERT INTO support_tickets (key, title, priority, status, opened_on, source, external_id)
                VALUES ('SUP-T-QUEUE', 'Unassigned', 'Low', 'Open', '2026-09-27', 'test', 'SUP-T-QUEUE')""", Tuple.tuple()));

        assertTrue(await(new BugRepository(sql).ticketQueue()).stream()
                .anyMatch(t -> "SUP-T-QUEUE".equals(((JsonObject) t).getString("key"))));
    }

    // ------------------------------------------------------------------ helpers

    /**
     * True when {@code viewer} gets {@code target}'s rows through {@code read}.
     */
    private static boolean seen(String viewer, String target, String read) throws Exception
    {
        String domain = read.startsWith("pulse.") ? PolicyService.DOMAIN_PULSE : PolicyService.DOMAIN_PERSON;

        Object result = await(READS.get(read).apply(scope(viewer, domain), ROWS.get(target)));

        if (result instanceof JsonArray rows)
        {
            return !rows.isEmpty();
        }

        if (result instanceof JsonObject row && row.containsKey("total") && row.containsKey("items"))
        {
            return row.getLong("total") > 0;
        }

        return result != null;
    }

    private static Scope scope(String email, String domain) throws Exception
    {
        return await(policy.scope(USERS.get(email), domain));
    }

    /**
     * One row in every Track 2 table for {@code who}; {@code other} reviews and comments on their PR.
     */
    private static JsonObject rowsFor(String who, String other) throws Exception
    {
        String person = personId(who);

        String peer = personId(other);

        String tag = "T-" + who.toUpperCase();

        String issue = await(owner.queryOne("""
                INSERT INTO issues (key, summary, status, assignee_id, source, external_id)
                VALUES ($1, 'Scope test issue', 'In Progress', $2::uuid, 'test', $1) RETURNING id""",
                Tuple.of(tag + "-1", person))).getString("id");

        await(owner.execute("""
                INSERT INTO issue_events (issue_id, occurred_at, kind, actor_id, actor_name, summary)
                VALUES ($1::uuid, '2026-09-22T10:00:00Z', 'worklog', $2::uuid, 'Assignee', 'Worklog 2h')""",
                Tuple.of(issue, person)));

        await(owner.execute("INSERT INTO worklogs (person_id, issue_id, work_date, hours) VALUES ($1::uuid, $2::uuid, '2026-09-22', 6)",
                Tuple.of(person, issue)));

        await(owner.execute("INSERT INTO commit_days (person_id, commit_date, commit_count) VALUES ($1::uuid, '2026-09-22', 2)",
                Tuple.of(person)));

        String pr = await(owner.queryOne("""
                INSERT INTO pull_requests (number, tool, repo, title, author_id, status, opened_at, lines_changed,
                                           source, external_id)
                VALUES ($1, 'GitHub', 'pulse-agent', 'Scope test PR', $2::uuid, 'Completed', '2026-09-21T10:00:00Z', 120,
                        'test', $1) RETURNING id""",
                Tuple.of("#" + tag, person))).getString("id");

        await(owner.execute("INSERT INTO pr_reviews (pull_request_id, reviewer_id, vote) VALUES ($1::uuid, $2::uuid, 'Approved')",
                Tuple.of(pr, peer)));

        await(owner.execute("""
                INSERT INTO pr_comments (pull_request_id, author_id, body, commented_at)
                VALUES ($1::uuid, $2::uuid, 'suggestion: rename this helper because it is shared', '2026-09-22T09:00:00Z')""",
                Tuple.of(pr, peer)));

        await(owner.execute("""
                INSERT INTO bugs (key, title, severity, status, environment, origin_person_id, assignee_id, fixed_by_id,
                                  reported_on, resolved_on, source, external_id)
                VALUES ($1, 'Scope test bug', 'High', 'Resolved', 'Production', $2::uuid, $3::uuid, $3::uuid,
                        '2026-09-10', '2026-09-12', 'test', $1)""",
                Tuple.of("BUG-" + tag, peer, person)));

        await(owner.execute("""
                INSERT INTO bugs (key, title, severity, status, environment, origin_person_id, reported_on, source, external_id)
                VALUES ($1, 'Bug in own code', 'Medium', 'Open', 'Staging', $2::uuid, '2026-09-15', 'test', $1)""",
                Tuple.of("BUG-" + tag + "-OWN", person)));

        await(owner.execute("""
                INSERT INTO support_tickets (key, title, priority, person_id, status, opened_on, source, external_id)
                VALUES ($1, 'Scope test ticket', 'Medium', $2::uuid, 'Open', '2026-09-20', 'test', $1)""",
                Tuple.of("SUP-" + tag, person)));

        String extra = await(owner.queryOne("""
                INSERT INTO extra_items (detection_key, person_id, kind, ref, title, requested_by, detection_note,
                                         evidence_source, occurred_on, hours)
                VALUES ($1, $2::uuid, 'midsprint', 'PLAT-1', 'Added mid-sprint', 'Self-initiated', 'Changelog',
                        'Jira', '2026-09-22', 3) RETURNING id""",
                Tuple.of("test:" + tag, person))).getString("id");

        await(owner.execute("INSERT INTO weekly_pulse (person_id, week_start, mood, workload) VALUES ($1::uuid, '2026-09-21', 3, 4)",
                Tuple.of(person)));

        return new JsonObject()
                .put("person", person)
                .put("issue", issue)
                .put("issueKey", tag + "-1")
                .put("pr", pr)
                .put("bugKey", "BUG-" + tag + "-OWN")
                .put("extra", extra);
    }

    private static JsonObject user(String email) throws Exception
    {
        JsonObject row = await(owner.queryOne("""
                SELECT u.id, r.name AS role, p.id AS "personId",
                       array(SELECT permission FROM role_permissions WHERE role_id = r.id) AS permissions
                FROM users u JOIN roles r ON r.id = u.role_id LEFT JOIN people p ON p.user_id = u.id
                WHERE u.email = $1""", Tuple.of(email)));

        assertEquals(email.startsWith("hr") || email.startsWith("rahul"), row.getString("personId") == null, email);

        return row;
    }

    private static String personId(String externalId) throws Exception
    {
        return await(owner.queryOne("SELECT id FROM people WHERE source = 'seed' AND external_id = $1", Tuple.of(externalId)))
                .getString("id");
    }

    private static UUID id(JsonObject rows, String name)
    {
        return UUID.fromString(rows.getString(name));
    }
}
