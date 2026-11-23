package com.pulse.platform.jobs;

import com.pulse.testsupport.RoleMatrix;
import com.pulse.testsupport.TestApi;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.client.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

import static com.pulse.testsupport.TestApi.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Task 5.5, spec platform/jobs-scheduler: every job runs when an Admin triggers it and records a run
 * row; non-Admins are refused; a failed job retries with backoff, then shows as failed and as an Admin
 * Action center item; a run never overlaps the previous one; the scheduler fires a job at its cron time.
 */
class JobsTest
{
    private static final int PORT = 18_155;

    private static final String BASE = "/api/v1/jobs";

    /** The six jobs of task 5.5. */
    private static final List<String> SPEC_JOBS = List.of("progress.friday-pulse", "progress.monday-digest",
            "attendance.month-end-reminder", "work.stale-signal-refresh", "integrations.token-expiry-check",
            PlatformJobs.RETENTION_PURGE);

    private static final String FAILING = "probe.always-fails";

    private static TestApi api;

    private static TestApi.Session admin;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, new JsonObject().put("today", "2026-09-28").put("jobs.attempts", 2).put("jobs.backoffMs", 100),
                "integrations", "work", "action_center");

        admin = RoleMatrix.session(api, RoleMatrix.ADMIN);
    }

    @AfterAll
    static void stop() throws Exception
    {
        if (api != null)
        {
            api.close();
        }
    }

    private static JsonObject trigger(String job) throws Exception
    {
        HttpResponse<Buffer> res = api.post(BASE + "/" + job + "/run", admin, new JsonObject());

        assertEquals(200, res.statusCode(), res.bodyAsString());

        return res.bodyAsJsonObject().getJsonObject("run");
    }

    private static JsonObject waitForOutcome(String runId, String outcome) throws Exception
    {
        for (int i = 0; i < 100; i++)
        {
            JsonObject row = api.queryOne("SELECT outcome, attempts, error FROM job_runs WHERE id = '" + runId + "'");

            if (outcome.equals(row.getString("outcome")))
            {
                return row;
            }

            Thread.sleep(50);
        }

        throw new AssertionError("run " + runId + " never became " + outcome);
    }

    // ------------------------------------------------------------------ access

    @Test
    void onlyAdminsSeeAndRunJobs() throws Exception
    {
        Map<String, Integer> adminOnly = Map.of(RoleMatrix.ADMIN, 200, RoleMatrix.MANAGER, 403, RoleMatrix.EMPLOYEE, 403,
                RoleMatrix.PEER, 403);

        RoleMatrix.assertRoleMatrix(api, "GET " + BASE, adminOnly);
        RoleMatrix.assertRoleMatrix(api, "GET " + BASE + "/runs", adminOnly);
        RoleMatrix.assertRoleMatrix(api, "POST " + BASE + "/work.stale-signal-refresh/run",
                Map.of(RoleMatrix.ADMIN, 200, RoleMatrix.MANAGER, 403, RoleMatrix.EMPLOYEE, 403, RoleMatrix.PEER, 403));
    }

    @Test
    void listShowsEveryRegisteredJobWithItsNextRun() throws Exception
    {
        JsonArray items = api.get(BASE, admin).bodyAsJsonObject().getJsonArray("items");

        Set<String> names = new TreeSet<>();

        for (Object o : items)
        {
            JsonObject job = (JsonObject) o;

            names.add(job.getString("name"));
            assertNotNull(job.getString("schedule"));
            assertFalse(job.getString("description").isBlank(), job.encode());

            if (!FAILING.equals(job.getString("name")))
            {
                assertNotNull(job.getString("nextRunAt"), job.encode());
            }
        }

        assertTrue(names.containsAll(SPEC_JOBS), names.toString());
    }

    @Test
    void unknownJobIsNotFound() throws Exception
    {
        assertEquals(404, api.post(BASE + "/nope.nothing/run", admin, new JsonObject()).statusCode());
        assertEquals(404, api.get(BASE + "/runs?job=nope.nothing", admin).statusCode());
        assertEquals(400, api.get(BASE + "/runs?limit=0", admin).statusCode());
    }

    // ------------------------------------------------------------------ manual trigger

    @Test
    void everyJobRunsWhenTriggeredAndRecordsARun() throws Exception
    {
        for (String job : SPEC_JOBS)
        {
            JsonObject run = trigger(job);

            assertEquals("succeeded", run.getString("outcome"), job + ": " + run.encode());
            assertEquals("manual", run.getString("trigger"));
            assertEquals(1, run.getInteger("attempts"));
            assertNotNull(run.getString("triggeredBy"));
            assertNotNull(run.getString("endedAt"));

            JsonObject row = api.queryOne("SELECT count(*)::int AS n FROM job_runs WHERE id = '" + run.getString("id") + "'");

            assertEquals(1, row.getInteger("n"));

            JsonObject audit = api.queryOne("SELECT count(*)::int AS n FROM audit_log WHERE action = 'job.triggered' "
                    + "AND target_id = '" + job + "'");

            assertTrue(audit.getInteger("n") >= 1, job + " trigger not audited");
        }

        JsonArray runs = api.get(BASE + "/runs?job=" + PlatformJobs.RETENTION_PURGE + "&limit=5", admin).bodyAsJsonObject()
                .getJsonArray("items");

        assertFalse(runs.isEmpty());
        assertEquals(PlatformJobs.RETENTION_PURGE, runs.getJsonObject(0).getString("job"));
    }

    @Test
    void retentionPurgeDeletesOnlyRowsPastThirteenMonths() throws Exception
    {
        LocalDate today = LocalDate.parse("2026-09-28");

        await(api.sql().execute("INSERT INTO sync_log (tool, started_at, outcome, message) VALUES "
                + "('jira', $1, 'success', 'purge-old'), ('jira', $2, 'success', 'purge-recent')",
                io.vertx.sqlclient.Tuple.of(today.minusMonths(13).minusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC),
                        today.minusMonths(12).atStartOfDay().atOffset(java.time.ZoneOffset.UTC))));

        JsonObject run = trigger(PlatformJobs.RETENTION_PURGE);

        assertEquals("succeeded", run.getString("outcome"));
        assertTrue(run.getJsonObject("counts").getInteger("sync_log", 0) >= 1, run.encode());

        assertEquals(0, api.queryOne("SELECT count(*)::int AS n FROM sync_log WHERE message = 'purge-old'").getInteger("n"));
        assertEquals(1, api.queryOne("SELECT count(*)::int AS n FROM sync_log WHERE message = 'purge-recent'").getInteger("n"));
    }

    @Test
    void expiringTokenWarnsAdminsOncePerDay() throws Exception
    {
        String tool = api.queryOne("SELECT tool FROM integration_settings WHERE method IS NOT NULL ORDER BY tool LIMIT 1")
                .getString("tool");

        await(api.sql().execute("UPDATE integration_settings SET token_expires_on = $2 WHERE tool = $1",
                io.vertx.sqlclient.Tuple.of(tool, LocalDate.parse("2026-10-01"))));

        try
        {
            JsonObject first = trigger("integrations.token-expiry-check");

            assertEquals("succeeded", first.getString("outcome"), first.encode());
            assertTrue(first.getJsonObject("counts").getInteger("warned") >= 1, first.encode());
            assertTrue(first.getJsonObject("counts").getInteger("notified") >= 1, first.encode());

            JsonObject log = api.queryOne("SELECT message FROM credential_log WHERE tool = '" + tool
                    + "' AND action = 'warning' ORDER BY at DESC LIMIT 1");

            assertEquals("Token expires in 3 days", log.getString("message"));

            JsonArray notices = api.get("/api/v1/notifications", admin).bodyAsJsonObject().getJsonArray("items");

            assertTrue(notices.stream().map(o -> ((JsonObject) o).getString("text")).anyMatch(t -> t.endsWith("Token expires in 3 days")),
                    notices.encode());

            JsonObject again = trigger("integrations.token-expiry-check");

            assertEquals(0, again.getJsonObject("counts").getInteger("warned"), again.encode());
        }
        finally
        {
            await(api.sql().execute("UPDATE integration_settings SET token_expires_on = NULL WHERE tool = $1",
                    io.vertx.sqlclient.Tuple.of(tool)));
        }
    }

    // ------------------------------------------------------------------ retries, failure and overlap

    @Test
    void failedJobRetriesThenShowsAsFailedAndAsAnAdminItem() throws Exception
    {
        JsonObject first = trigger(FAILING);

        assertEquals("running", first.getString("outcome"), first.encode());
        assertEquals(1, first.getInteger("attempts"));
        assertNotNull(first.getString("nextAttemptAt"));

        JsonObject done = waitForOutcome(first.getString("id"), "failed");

        assertEquals(2, done.getInteger("attempts"));
        assertTrue(done.getString("error").startsWith("probe failure"), done.encode());
        assertFalse(done.getString("error").contains("hunter2"), "secret in the run record: " + done.getString("error"));

        JsonArray items = api.get("/api/v1/action_center/items", admin).bodyAsJsonObject().getJsonArray("items");

        JsonObject item = items.stream().map(o -> (JsonObject) o)
                .filter(i -> "integrations.job-failed".equals(i.getString("key")) && FAILING.equals(i.getString("personName")))
                .findFirst().orElse(null);

        assertNotNull(item, items.encode());
        assertEquals("High", item.getString("priority"));

        JsonObject listed = api.get(BASE, admin).bodyAsJsonObject().getJsonArray("items").stream().map(o -> (JsonObject) o)
                .filter(j -> FAILING.equals(j.getString("name"))).findFirst().orElseThrow();

        assertEquals("failed", listed.getJsonObject("lastRun").getString("outcome"));
    }

    @Test
    void runIsSkippedWhileThePreviousRunIsUnfinished() throws Exception
    {
        String job = "progress.monday-digest";

        JsonObject running = api.queryOne("INSERT INTO job_runs (job, trigger, outcome) VALUES ('" + job
                + "', 'schedule', 'running') RETURNING id");

        try
        {
            JsonObject run = trigger(job);

            assertEquals("skipped", run.getString("outcome"), run.encode());
            assertNotNull(run.getString("endedAt"));
        }
        finally
        {
            api.queryOne("UPDATE job_runs SET outcome = 'succeeded', ended_at = now() WHERE id = '"
                    + running.getString("id") + "' RETURNING id");
        }

        assertEquals("succeeded", trigger(job).getString("outcome"));
    }

    // ------------------------------------------------------------------ scheduler

    /** A clock the test moves. */
    private static final class MovableClock extends Clock
    {
        private final AtomicReference<Instant> now;

        private final ZoneId zone;

        MovableClock(Instant start, ZoneId zone)
        {
            this.now = new AtomicReference<>(start);
            this.zone = zone;
        }

        void set(Instant instant)
        {
            now.set(instant);
        }

        @Override
        public ZoneId getZone()
        {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return now.get();
        }
    }

    @Test
    void schedulerFiresTheFridayPulseAtItsTime() throws Exception
    {
        ZoneId zone = com.pulse.calc.core.PulseClock.ZONE;

        MovableClock clock = new MovableClock(LocalDateTime.parse("2026-11-27T15:59:00").atZone(zone).toInstant(), zone);

        JsonObject config = api.config().copy().put("jobs.tickMs", 50);

        String id = await(api.vertx().deployVerticle(new SchedulerVerticle(clock), new DeploymentOptions().setConfig(config)));

        try
        {
            int before = api.queryOne("SELECT count(*)::int AS n FROM job_runs WHERE job = 'progress.friday-pulse' "
                    + "AND trigger = 'schedule'").getInteger("n");

            Thread.sleep(200);

            assertEquals(before, api.queryOne("SELECT count(*)::int AS n FROM job_runs WHERE job = 'progress.friday-pulse' "
                    + "AND trigger = 'schedule'").getInteger("n"), "fired before its minute");

            clock.set(LocalDateTime.parse("2026-11-27T16:00:10").atZone(zone).toInstant());

            for (int i = 0; i < 100; i++)
            {
                int n = api.queryOne("SELECT count(*)::int AS n FROM job_runs WHERE job = 'progress.friday-pulse' "
                        + "AND trigger = 'schedule' AND outcome = 'succeeded'").getInteger("n");

                if (n == before + 1)
                {
                    Thread.sleep(200);

                    assertEquals(before + 1, api.queryOne("SELECT count(*)::int AS n FROM job_runs WHERE job = "
                            + "'progress.friday-pulse' AND trigger = 'schedule'").getInteger("n"), "fired twice");

                    assertNull(api.queryOne("SELECT id FROM job_runs WHERE trigger = 'schedule' AND job = "
                            + "'progress.monday-digest' AND started_at > now() - interval '5 seconds'"), "wrong job fired");

                    return;
                }

                Thread.sleep(50);
            }

            fail("the Friday pulse did not run at 16:00");
        }
        finally
        {
            await(api.vertx().undeploy(id));
        }
    }
}
