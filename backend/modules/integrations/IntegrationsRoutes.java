package com.pulse.modules.integrations;

import com.pulse.platform.http.BodySchema;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * Integrations under {@code /api/v1/integrations} (task 5.4), all behind {@code integrations.manage}
 * (spec "Access"), following docs/backend-guide.md.
 *
 * <pre>
 * GET    /tools                       tiles: state, status text, action, last sync
 * GET    /tools/:tool                 settings form: methods and fields, masked stored values, scopes, sync mode
 * PUT    /tools/:tool/credentials     {method, values}: validate, store masked placeholders, log, audit
 * DELETE /tools/:tool/credentials     disconnect
 * PUT    /tools/:tool/settings        {syncMode}
 * POST   /tools/:tool/sync            Sync now: {sync, tile}
 * GET    /jira/config                 boards, story points field, sync mode, ignore bot edits
 * PUT    /jira/config                 save the Jira configuration
 * GET    /sync-log[?tool=]            daily sync log, newest first
 * GET    /credential-log              credential activity log, newest first
 * </pre>
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

    static final String PERMISSION = "integrations.manage";

    private final PolicyService policy;

    private final IntegrationsService integrations;

    IntegrationsRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();
        this.integrations = new IntegrationsService(ctx, IntegrationsService.connectors(ctx));
    }

    void mount(Router router)
    {
        router.get("/tools").handler(policy.requires(PERMISSION)).handler(this::tiles);

        router.get("/tools/:tool").handler(policy.requires(PERMISSION)).handler(this::tool);

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
}
