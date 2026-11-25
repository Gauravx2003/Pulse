package com.pulse.modules.integrations;

import com.pulse.calc.core.CalcService;
import com.pulse.platform.accounts.ExternalAccountService;
import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.Ids;
import com.pulse.platform.live.LiveConfig;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.function.Supplier;

/**
 * Integrations under {@code /api/v1/integrations} (task 5.4), all behind {@code integrations.manage}
 * (spec "Access"), following docs/backend-guide.md.
 *
 * <pre>
 * GET    /tools                       tiles: state, status text, action, last sync
 * GET    /tools/:tool                 settings form: methods and fields, masked stored values, scopes, sync mode
 * POST   /tools/:tool/test            {method, values}: Test connection (simulated in Phase 1, nothing stored)
 * PUT    /tools/:tool/credentials     {method, values}: validate, store masked placeholders, log, audit
 * DELETE /tools/:tool/credentials     disconnect
 * PUT    /tools/:tool/settings        {syncMode}
 * POST   /tools/:tool/sync            Sync now: {sync, tile}
 * GET    /jira/config                 boards, story points field, sync mode, ignore bot edits
 * PUT    /jira/config                 save the Jira configuration
 * GET    /sync-log[?tool=]            daily sync log, newest first
 * GET    /credential-log              credential activity log, newest first
 * GET    /accounts[?status=&tool=&q=] live tool accounts, unmatched by default (task 24.1)
 * PUT    /accounts/:id/link           {personId}: link an account to a person; 409 when already linked
 * DELETE /accounts/:id/link           unlink
 * PUT    /accounts/:id/bot            {isBot}: mark or unmark a bot
 * GET    /:tool/connection            live connector: schema, secret spec, stored config and last4 (task 24.5)
 * POST   /:tool/test                  {config, secret, expiresAt?}: real Test connection; a pass carries a proof
 * PUT    /:tool/connection            {config, secret, expiresAt?, proof}: save, only with a passing test's proof
 * DELETE /:tool/connection            disconnect: delete the secret, keep every ingested row
 * POST   /:tool/sync                  live Sync now: {sync, tile}; 409 while a sync of the tool runs (task 24.6)
 * </pre>
 * The {@code /:tool/...} routes answer only for tools with a registered live connector (404 otherwise);
 * seeded tools keep the {@code /tools/:tool/...} routes.
 */
final class IntegrationsRoutes
{
    static final BodySchema CREDENTIALS = BodySchema.object()
            .string("method", 100)
            .anyObject("values", 20)
            .required("method", "values");

    static final BodySchema SETTINGS = BodySchema.object()
            .oneOf("syncMode", IntegrationsService.SYNC_MODES.toArray(String[]::new))
            .required("syncMode");

    static final BodySchema JIRA_CONFIG = BodySchema.object()
            .stringList("boards", 50, 100)
            .string("storyPointsField", 100)
            .oneOf("syncMode", IntegrationsService.SYNC_MODES.toArray(String[]::new))
            .bool("ignoreBotEdits")
            .required("boards", "storyPointsField", "syncMode", "ignoreBotEdits");

    static final BodySchema LINK = BodySchema.object()
            .uuid("personId")
            .required("personId");

    static final BodySchema BOT = BodySchema.object()
            .bool("isBot")
            .required("isBot");

    static final BodySchema LIVE_TEST = BodySchema.object()
            .anyObject("config", 50)
            .string("secret", LiveConfig.MAX_SECRET)
            .string("expiresAt", 40)
            .required("config", "secret");

    static final BodySchema LIVE_SAVE = BodySchema.object()
            .anyObject("config", 50)
            .string("secret", LiveConfig.MAX_SECRET)
            .string("expiresAt", 40)
            .string("proof", 100)
            .required("config", "secret", "proof");

    static final String PERMISSION = "integrations.manage";

    private final PolicyService policy;

    private final IntegrationsService integrations;

    private final ExternalAccountService accounts;

    private final LiveConnectionsService live;

    private final CalcService calc;

    IntegrationsRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();
        this.integrations = new IntegrationsService(ctx, IntegrationsService.connectors(ctx));
        this.accounts = ctx.accounts();
        this.live = new LiveConnectionsService(ctx);
        this.calc = ctx.calc();
    }

    void mount(Router router)
    {
        router.get("/tools").handler(policy.requires(PERMISSION)).handler(this::tiles);

        router.get("/tools/:tool").handler(policy.requires(PERMISSION)).handler(this::tool);

        router.post("/tools/:tool/test").handler(policy.requires(PERMISSION)).handler(CREDENTIALS.handler())
                .handler(this::testConnection);

        router.put("/tools/:tool/credentials").handler(policy.requires(PERMISSION)).handler(CREDENTIALS.handler())
                .handler(this::saveCredentials);

        router.delete("/tools/:tool/credentials").handler(policy.requires(PERMISSION)).handler(this::disconnect);

        router.put("/tools/:tool/settings").handler(policy.requires(PERMISSION)).handler(SETTINGS.handler())
                .handler(this::saveSettings);

        router.post("/tools/:tool/sync").handler(policy.requires(PERMISSION)).handler(this::syncNow);

        router.get("/jira/config").handler(policy.requires(PERMISSION)).handler(this::jiraConfig);

        router.put("/jira/config").handler(policy.requires(PERMISSION)).handler(JIRA_CONFIG.handler())
                .handler(this::saveJiraConfig);

        router.get("/sync-log").handler(policy.requires(PERMISSION)).handler(this::syncLog);

        router.get("/credential-log").handler(policy.requires(PERMISSION)).handler(this::credentialLog);

        router.get("/accounts").handler(policy.requires(PERMISSION)).handler(this::accounts);

        router.put("/accounts/:id/link").handler(policy.requires(PERMISSION)).handler(LINK.handler())
                .handler(this::linkAccount);

        router.delete("/accounts/:id/link").handler(policy.requires(PERMISSION)).handler(this::unlinkAccount);

        router.put("/accounts/:id/bot").handler(policy.requires(PERMISSION)).handler(BOT.handler())
                .handler(this::setBot);

        // live connectors (task 24.5): generic for any registered tool
        router.get("/:tool/connection").handler(policy.requires(PERMISSION)).handler(this::liveConnection);

        router.post("/:tool/test").handler(policy.requires(PERMISSION)).handler(LIVE_TEST.handler())
                .handler(this::liveTest);

        router.put("/:tool/connection").handler(policy.requires(PERMISSION)).handler(LIVE_SAVE.handler())
                .handler(this::liveSave);

        router.delete("/:tool/connection").handler(policy.requires(PERMISSION)).handler(this::liveDisconnect);

        // live Sync now (task 24.6); /tools/:tool/sync also runs the live sync for a live tool
        router.post("/:tool/sync").handler(policy.requires(PERMISSION)).handler(this::liveSyncNow);
    }

    private void liveConnection(RoutingContext rc)
    {
        reply(rc, () -> live.connection(rc.pathParam("tool")));
    }

    private void liveTest(RoutingContext rc)
    {
        reply(rc, () -> live.test(rc.pathParam("tool"), BodySchema.body(rc)));
    }

    private void liveSave(RoutingContext rc)
    {
        reply(rc, () -> live.save(PolicyService.user(rc), rc.pathParam("tool"), BodySchema.body(rc)));
    }

    private void liveDisconnect(RoutingContext rc)
    {
        reply(rc, () -> live.disconnect(PolicyService.user(rc), rc.pathParam("tool")));
    }

    /** Runs {@code call}, sending a validation error thrown before its future exists to the FailureHandler too. */
    private static void reply(RoutingContext rc, Supplier<Future<JsonObject>> call)
    {
        Future<JsonObject> future;

        try
        {
            future = call.get();
        }
        catch (RuntimeException e)
        {
            future = Future.failedFuture(e);
        }

        future.onSuccess(rc::json).onFailure(rc::fail);
    }

    private void tiles(RoutingContext rc)
    {
        integrations.tiles().onSuccess(rc::json).onFailure(rc::fail);
    }

    private void tool(RoutingContext rc)
    {
        integrations.tool(rc.pathParam("tool")).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void saveCredentials(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        integrations.saveCredentials(PolicyService.user(rc), rc.pathParam("tool"), body.getString("method"),
                body.getJsonObject("values")).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void testConnection(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        integrations.testConnection(rc.pathParam("tool"), body.getString("method"), body.getJsonObject("values"))
                .onSuccess(rc::json).onFailure(rc::fail);
    }

    private void disconnect(RoutingContext rc)
    {
        integrations.disconnect(PolicyService.user(rc), rc.pathParam("tool")).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void saveSettings(RoutingContext rc)
    {
        integrations.saveSyncMode(PolicyService.user(rc), rc.pathParam("tool"), BodySchema.body(rc).getString("syncMode"))
                .onSuccess(rc::json).onFailure(rc::fail);
    }

    private void syncNow(RoutingContext rc)
    {
        integrations.syncNow(PolicyService.user(rc), rc.pathParam("tool")).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void liveSyncNow(RoutingContext rc)
    {
        integrations.liveSyncNow(PolicyService.user(rc), rc.pathParam("tool")).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void jiraConfig(RoutingContext rc)
    {
        integrations.jiraConfig().onSuccess(rc::json).onFailure(rc::fail);
    }

    private void saveJiraConfig(RoutingContext rc)
    {
        integrations.saveJiraConfig(PolicyService.user(rc), BodySchema.body(rc)).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void syncLog(RoutingContext rc)
    {
        integrations.syncLog(rc.queryParams().get("tool")).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void credentialLog(RoutingContext rc)
    {
        integrations.credentialLog().onSuccess(rc::json).onFailure(rc::fail);
    }

    private void accounts(RoutingContext rc)
    {
        accounts.list(rc.queryParams()).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void linkAccount(RoutingContext rc)
    {
        accounts.link(PolicyService.user(rc), Ids.path(rc, "id"),
                Ids.parse(BodySchema.body(rc).getString("personId"))).onSuccess(account -> accountChanged(rc, account))
                .onFailure(rc::fail);
    }

    private void unlinkAccount(RoutingContext rc)
    {
        accounts.unlink(PolicyService.user(rc), Ids.path(rc, "id")).onSuccess(account -> accountChanged(rc, account))
                .onFailure(rc::fail);
    }

    private void setBot(RoutingContext rc)
    {
        accounts.setBot(PolicyService.user(rc), Ids.path(rc, "id"), BodySchema.body(rc).getBoolean("isBot"))
                .onSuccess(account -> accountChanged(rc, account)).onFailure(rc::fail);
    }

    /**
     * A link, unlink or bot mark changes whose figures the account's live rows count for (task 25.3 of
     * pulse-v2-live-connectors): live rows resolve their person at read time, so every cached result is
     * dropped, here and on other instances, and the next read counts them for the new person with no re-sync.
     */
    private void accountChanged(RoutingContext rc, JsonObject account)
    {
        calc.invalidate(CalcService.ALL);

        CalcService.publish(rc.vertx(), CalcService.ALL);

        rc.json(account);
    }
}
