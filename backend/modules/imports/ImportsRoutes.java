package com.pulse.modules.imports;

import com.pulse.modules.imports.csv.CsvWriter;
import com.pulse.modules.imports.pipeline.ImportService;
import com.pulse.modules.imports.type.ImportType;
import com.pulse.modules.imports.type.ImportTypeRegistry;
import com.pulse.platform.audit.AuditEvents;
import com.pulse.platform.audit.AuditService;
import com.pulse.platform.http.ApiException;
import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.RateLimiter;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * HTTP API of the CSV pipeline (task 16.2), mounted under {@code /api/v1/imports}. Every route needs a
 * session (the platform's session guard runs first, and writes need the CSRF token). Which import
 * types a user may run depends on each type's own permissions, so that check happens per type in
 * {@link ImportService#allowedType} rather than on the route.
 *
 * <pre>
 * GET    /types                         import cards the caller may run
 * POST   /:type/check?mode=&fileName=   body = the CSV file (Content-Type: text/csv, at most 5 MB)
 * POST   /:type/commit                  {"checkId": "..."}
 * DELETE /checks/:checkId               cancel a check
 * GET    /history?page=&pageSize=&import_type=
 * POST   /history/:importId/undo
 * GET    /:type/template                CSV with the header row and one example row
 * GET    /:type/export                  current data in the same layout, audited as data.export
 * </pre>
 */
public final class ImportsRoutes
{
    static final BodySchema COMMIT = BodySchema.object()
            .uuid("checkId")
            .required("checkId");

    private final ModuleContext ctx;

    private final Future<ImportTypeRegistry> registry;

    private final Future<ImportService> service;

    ImportsRoutes(ModuleContext ctx)
    {
        this.ctx = ctx;

        ClassLoader loader = getClass().getClassLoader();

        // Classpath scanning is blocking I/O: done once, off the event loop.
        this.registry = ctx.vertx().executeBlocking(() -> ImportTypeRegistry.discover(loader), false);

        this.service = registry.map(found -> new ImportService(ctx.vertx(), ctx.sql(), found));
    }

    void mount(Router router)
    {
        RateLimiter heavy = RateLimiter.perUser(ctx.vertx(), "imports", 20, 60);

        router.get("/types").handler(this::types);

        router.get("/history").handler(this::history);

        router.post("/history/:importId/undo").handler(heavy).handler(this::undo);

        router.delete("/checks/:checkId").handler(this::cancel);

        router.post("/:type/check").handler(heavy).handler(this::check);

        router.post("/:type/commit").handler(heavy).handler(COMMIT.handler()).handler(this::commit);

        router.get("/:type/template").handler(this::template);

        router.get("/:type/export").handler(heavy).handler(this::export);
    }

    private void types(RoutingContext rc)
    {
        JsonObject user = PolicyService.user(rc);

        registry.onSuccess(found ->
        {
            JsonArray items = new JsonArray();

            for (ImportType type : found.allowedFor(PolicyService.permissions(user)))
            {
                items.add(new JsonObject()
                        .put("id", type.id())
                        .put("name", type.name())
                        .put("feeds", type.feeds())
                        .put("columns", type.columns())
                        .put("modes", type.modes())
                        .put("defaultMode", ImportTypeRegistry.defaultMode(type)));
            }

            rc.json(new JsonObject().put("items", items));
        }).onFailure(rc::fail);
    }

    private void check(RoutingContext rc)
    {
        String contentType = rc.request().getHeader("Content-Type");

        if (contentType == null || !contentType.toLowerCase().startsWith("text/csv"))
        {
            rc.fail(ApiException.badRequest("invalid_body", "Send the file as text/csv."));

            return;
        }

        Buffer body = rc.body() == null ? null : rc.body().buffer();

        byte[] bytes = body == null ? new byte[0] : body.getBytes();

        service.compose(s -> s.check(rc.pathParam("type"), rc.queryParams().get("mode"), rc.queryParams().get("fileName"),
                        bytes, PolicyService.user(rc)))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void commit(RoutingContext rc)
    {
        String checkId = BodySchema.body(rc).getString("checkId");

        service.compose(s -> s.commit(rc.pathParam("type"), checkId, PolicyService.user(rc)))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void cancel(RoutingContext rc)
    {
        service.compose(s -> s.cancel(rc.pathParam("checkId"), PolicyService.user(rc)))
                .onSuccess(v -> rc.response().setStatusCode(204).end())
                .onFailure(rc::fail);
    }

    private void history(RoutingContext rc)
    {
        service.compose(s -> s.history(rc.queryParams(), PolicyService.user(rc)))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void undo(RoutingContext rc)
    {
        service.compose(s -> s.undo(rc.pathParam("importId"), PolicyService.user(rc)))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void template(RoutingContext rc)
    {
        service.map(s -> s.allowedType(rc.pathParam("type"), PolicyService.user(rc)))
                .onSuccess(type ->
                {
                    JsonArray keys = CsvWriter.keysOf(type.columns());

                    sendCsv(rc, type.id() + "_template.csv", CsvWriter.write(keys, JsonArray.of(type.templateExample())));
                })
                .onFailure(rc::fail);
    }

    private void export(RoutingContext rc)
    {
        JsonObject user = PolicyService.user(rc);

        service.map(s -> s.allowedType(rc.pathParam("type"), user))
                .compose(type -> type.exportRows(user, ctx.sql())
                        .compose(rows -> AuditService.record(ctx.sql(), AuditService.entry(user, AuditEvents.DATA_EXPORT,
                                        "import_type", type.id(), new JsonObject().put("rows", rows.size())))
                                .map(v -> CsvWriter.write(CsvWriter.keysOf(type.columns()), rows)))
                        .onSuccess(csv -> sendCsv(rc, type.id() + "_export.csv", csv)))
                .onFailure(rc::fail);
    }

    private static void sendCsv(RoutingContext rc, String fileName, String csv)
    {
        rc.response()
                .putHeader("Content-Type", "text/csv; charset=utf-8")
                .putHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .end(csv);
    }
}
