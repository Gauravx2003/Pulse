package com.pulse.modules.work;

import com.pulse.calc.core.CalcService;
import com.pulse.calc.core.FormulaRegistry;
import com.pulse.calc.core.PulseClock;
import com.pulse.calc.core.ResultGuard;
import com.pulse.calc.work.WorkloadFormulas;
import com.pulse.testsupport.TestApi;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 1.1 (change jira-derived-hours): migration V295 adds {@code worklogs.account_id}, the table
 * {@code issue_work_intervals} and the read views {@code worklogs_with_person} and
 * {@code issue_work_intervals_with_person}. Seed worklogs keep their stored person; a live worklog or interval
 * takes the person linked to its tool account in {@code external_accounts}, and none while the account is
 * unmatched or a bot.
 *
 * <p>Task 1.2: every worklog reader goes through {@code worklogs_with_person}, so a live worklog of an
 * unmatched account adds no hours logged for anyone, and counts for the person from the next read once the
 * account is linked, with no re-sync.
 */
class LiveWorklogsTest
{

    private static final int PORT = 18_297;

    private static final String TODAY = "2026-09-28";

    private static TestApi api;

    private static String priya;

    private static String issue;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", TODAY), "work", "integrations");

        priya = api.queryOne("SELECT id::text AS id FROM people WHERE source = 'seed' AND external_id = 'ps'").getString("id");

        issue = api.queryOne("INSERT INTO issues (key, summary, status, source, external_id, synced_at)"
                + " VALUES ('WL-1', 'Live issue WL-1', 'In Progress', 'jira', 'WL-1', now()) RETURNING id::text AS id")
                .getString("id");
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    @Test
    void migrationAddsTheColumnTheTableAndTheViews() throws Exception
    {
        assertEquals(1, api.count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'worklogs'
                  AND column_name = 'account_id' AND data_type = 'text' AND is_nullable = 'YES'"""));

        assertEquals(1, api.count("SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema()"
                + " AND indexname = 'issue_work_intervals_source_external_uq' AND indexdef LIKE '%UNIQUE%(source, external_id)%'"));

        // the app role (api.sql()) reads both views
        assertTrue(api.queryOne("SELECT count(*) AS n FROM worklogs_with_person").getLong("n") > 0);

        assertEquals(0, api.queryOne("SELECT count(*) AS n FROM issue_work_intervals_with_person WHERE source = 'seed'")
                .getLong("n"), "the seed has no intervals");
    }

    @Test
    void seedWorklogsKeepTheirStoredPerson() throws Exception
    {
        assertEquals(api.count("SELECT count(*) FROM worklogs"), api.count("SELECT count(*) FROM worklogs_with_person"));

        assertEquals(0, api.count("""
                SELECT count(*) FROM worklogs w JOIN worklogs_with_person v ON v.id = w.id
                WHERE w.account_id IS NULL AND v.person_id IS DISTINCT FROM w.person_id"""));
    }

    @Test
    void liveWorklogsTakeTheLinkedPersonAndNoneWhenUnmatchedOrABot() throws Exception
    {
        account("jira", "w-linked", priya, false);
        account("jira", "w-bot", priya, true);
        account("gh", "w-other-tool", priya, false);
        account("jira", "w-unmatched", null, false);

        worklog("WL-W1", "w-linked");
        worklog("WL-W2", "w-bot");
        worklog("WL-W3", "w-other-tool");
        worklog("WL-W4", "w-unmatched");
        worklog("WL-W5", "w-never-seen");

        assertEquals(priya, personOf("worklogs_with_person", "WL-W1"));
        assertNull(personOf("worklogs_with_person", "WL-W2"), "a bot account counts for nobody");
        assertNull(personOf("worklogs_with_person", "WL-W3"), "an account of another tool does not match a jira row");
        assertNull(personOf("worklogs_with_person", "WL-W4"), "an unmatched account counts for nobody");
        assertNull(personOf("worklogs_with_person", "WL-W5"), "an account not in external_accounts counts for nobody");
    }

    @Test
    void intervalsTakeTheLinkedPersonAndNoneWhenUnmatchedOrABot() throws Exception
    {
        account("jira", "i-linked", priya, false);
        account("jira", "i-bot", priya, true);
        account("jira", "i-unmatched", null, false);

        interval("WL-1|dev", "i-linked");
        interval("WL-1|qa", "i-bot");
        interval("WL-2|dev", "i-unmatched");
        interval("WL-3|dev", null);

        assertEquals(priya, personOf("issue_work_intervals_with_person", "WL-1|dev"));
        assertNull(personOf("issue_work_intervals_with_person", "WL-1|qa"), "a bot account counts for nobody");
        assertNull(personOf("issue_work_intervals_with_person", "WL-2|dev"), "an unmatched account counts for nobody");
        assertNull(personOf("issue_work_intervals_with_person", "WL-3|dev"), "an interval without an account counts for nobody");

        JsonObject row = api.queryOne("SELECT kind, end_source, account_id FROM issue_work_intervals_with_person"
                + " WHERE external_id = 'WL-1|dev'");

        assertEquals("dev", row.getString("kind"));
        assertEquals("field", row.getString("end_source"));
        assertEquals("i-linked", row.getString("account_id"));
    }

    @Test
    void aLiveWorklogCountsForThePersonOnlyOnceItsAccountIsLinked() throws Exception
    {
        // a past working day on which Priya already logs seed hours, so the day surely counts
        String day = api.queryOne("SELECT min(work_date)::text AS day FROM worklogs WHERE person_id = '" + priya
                + "' AND work_date BETWEEN '2026-09-01' AND '2026-09-25' AND hours > 0").getString("day");

        double before = logged();

        api.exec("INSERT INTO external_accounts (tool, tool_account_id, login) VALUES ('jira', 'priya.worklog', 'priya.worklog')");

        api.exec("INSERT INTO worklogs (issue_id, work_date, hours, source, external_id, synced_at, account_id) VALUES ('"
                + issue + "', '" + day + "', 3, 'jira', 'WL-LINK-1', now(), 'priya.worklog')");

        assertEquals(before, logged(), 0.001, "unmatched: the worklog counts for nobody");

        api.exec("UPDATE external_accounts SET person_id = '" + priya + "' WHERE tool = 'jira' AND tool_account_id = 'priya.worklog'");

        assertEquals(before + 3, logged(), 0.001, "linked: Priya's hours logged include it from the next read");

        api.exec("UPDATE external_accounts SET person_id = NULL WHERE tool = 'jira' AND tool_account_id = 'priya.worklog'");

        assertEquals(before, logged(), 0.001, "unlinked: back to nobody");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Priya's hours logged in September 2026, read through a fresh {@link CalcService} so nothing is cached.
     */
    private static double logged() throws Exception
    {
        CalcService calc = new CalcService(api.sql(), PulseClock.of(Clock.systemUTC(), TODAY, false), FormulaRegistry.discover());

        JsonObject work = (JsonObject) ResultGuard.unguard(TestApi.await(calc.get(WorkloadFormulas.PERIOD_WORK,
                new JsonObject().put("person", priya).put("period", "m:2026-09"))));

        return work.getDouble("logged");
    }

    private static void account(String tool, String accountId, String personId, boolean bot) throws Exception
    {
        api.exec("INSERT INTO external_accounts (tool, tool_account_id, login, person_id, is_bot) VALUES ('" + tool + "', '"
                + accountId + "', '" + accountId + "', " + (personId == null ? "NULL" : "'" + personId + "'") + ", " + bot + ")");
    }

    /**
     * Adds a live Jira worklog of 3 hours on the issue, credited to a tool account and with no stored person,
     * as the connector writes it.
     */
    private static void worklog(String externalId, String accountId) throws Exception
    {
        api.exec("INSERT INTO worklogs (issue_id, work_date, hours, source, external_id, synced_at, account_id) VALUES ('"
                + issue + "', '2026-09-24', 3, 'jira', '" + externalId + "', now(), '" + accountId + "')");
    }

    private static void interval(String externalId, String accountId) throws Exception
    {
        api.exec("INSERT INTO issue_work_intervals (issue_id, kind, starts_at, ends_at, end_source, account_id, source,"
                + " external_id, synced_at) VALUES ('" + issue + "', '" + externalId.substring(externalId.indexOf('|') + 1)
                + "', '2026-09-24T10:00:00+05:30', '2026-09-25T13:00:00+05:30', 'field', "
                + (accountId == null ? "NULL" : "'" + accountId + "'") + ", 'jira', '" + externalId + "', now())");
    }

    private static String personOf(String view, String externalId) throws Exception
    {
        return api.queryOne("SELECT person_id::text AS id FROM " + view + " WHERE source = 'jira' AND external_id = '"
                + externalId + "'").getString("id");
    }
}
