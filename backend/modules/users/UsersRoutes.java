package com.pulse.modules.users;

import com.pulse.calc.core.CalcService;
import com.pulse.platform.http.BodySchema;
import com.pulse.platform.http.ExpectedVersion;
import com.pulse.platform.http.Ids;
import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import java.util.UUID;

/**
 * Users and Roles &amp; permissions under {@code /api/v1/users} (task 5.1), following docs/backend-guide.md.
 *
 * <pre>
 * GET    /items?q=&role=&status=&sort=     directory with KPI counts       users.team | users.all
 * GET    /items/:id                        one account
 * POST   /items                            add (Invited, invite link emailed, no password)
 * PUT    /items/:id                        edit (never the email); version required
 * DELETE /items/:id                        delete: reports move to the caller, personal data erased
 * GET    /items/:id/deletion               what a delete removes and keeps (the confirmation)
 * POST   /items/:id/invite                 send a new set-password link to an Invited user
 * GET    /options                          the Add / Edit form's roles, managers, levels, statuses
 *
 * GET    /roles                            permission matrix, user counts, menu per role    roles.manage
 * POST   /roles                            custom role from an existing role
 * PUT    /roles/:id/permissions/:perm      grant or revoke: {granted}
 * DELETE /roles/:id                        delete a custom role nobody holds
 * </pre>
 */
final class UsersRoutes
{
    static final BodySchema CREATE = BodySchema.object()
            .string("name", 120)
            .string("email", 254)
            .uuid("roleId")
            .uuid("managerId")
            .string("title", 120)
            .string("department", 120)
            .oneOf("level", UsersService.LEVELS.toArray(String[]::new))
            .required("name", "email");

    static final BodySchema EDIT = BodySchema.object()
            .string("name", 120)
            .uuid("roleId")
            .uuid("managerId")
            .string("title", 120)
            .string("department", 120)
            .oneOf("level", UsersService.LEVELS.toArray(String[]::new))
            .oneOf("status", UsersService.STATUSES.toArray(String[]::new))
            .integer("version", 1, Integer.MAX_VALUE);

    static final BodySchema NEW_ROLE = BodySchema.object()
            .string("name", 60)
            .uuid("fromRoleId")
            .string("description", 200)
            .required("name", "fromRoleId");

    static final BodySchema TOGGLE = BodySchema.object()
            .bool("granted")
            .required("granted");

    private final PolicyService policy;

    private final UsersService users;

    private final RolesService roles;

    private final CalcService calc;

    UsersRoutes(ModuleContext ctx)
    {
        this.policy = ctx.policy();
        this.calc = ctx.calc();
        this.users = new UsersService(ctx);
        this.roles = new RolesService(ctx);
    }

    void mount(Router router)
    {
        String[] managers = {"users.team", "users.all"};

        router.get("/items").handler(policy.requiresAny(managers)).handler(this::directory);

        router.post("/items").handler(policy.requiresAny(managers)).handler(CREATE.handler()).handler(this::create);

        router.get("/options").handler(policy.requiresAny(managers)).handler(this::options);

        router.get("/items/:id").handler(policy.requiresAny(managers)).handler(this::get);

        router.put("/items/:id").handler(policy.requiresAny(managers)).handler(EDIT.handler()).handler(this::update);

        router.delete("/items/:id").handler(policy.requiresAny(managers)).handler(this::delete);

        router.get("/items/:id/deletion").handler(policy.requiresAny(managers)).handler(this::deletionPreview);

        router.post("/items/:id/invite").handler(policy.requiresAny(managers)).handler(this::invite);

        router.get("/roles").handler(policy.requires("roles.manage")).handler(this::matrix);

        router.post("/roles").handler(policy.requires("roles.manage")).handler(NEW_ROLE.handler()).handler(this::createRole);

        router.put("/roles/:id/permissions/:permission").handler(policy.requires("roles.manage")).handler(TOGGLE.handler())
                .handler(this::toggle);

        router.delete("/roles/:id").handler(policy.requires("roles.manage")).handler(this::deleteRole);
    }

    // ------------------------------------------------------------------ users

    private void directory(RoutingContext rc)
    {
        users.directory(PolicyService.user(rc), rc.queryParams()).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void get(RoutingContext rc)
    {
        users.get(PolicyService.user(rc), Ids.path(rc, "id")).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void options(RoutingContext rc)
    {
        users.options(PolicyService.user(rc)).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void create(RoutingContext rc)
    {
        users.create(PolicyService.user(rc), BodySchema.body(rc), policy.request(rc))
                .onSuccess(row ->
                {
                    rc.response().setStatusCode(201);
                    rc.json(row);
                })
                .onFailure(rc::fail);
    }

    private void update(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        users.update(PolicyService.user(rc), Ids.path(rc, "id"), body, ExpectedVersion.of(rc, body), policy.request(rc))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    /**
     * A deletion erases the person's name and unlinks their live tool accounts (task 24.8), so every cached
     * result naming them or counting their live rows is dropped, here and on other instances.
     */
    private void delete(RoutingContext rc)
    {
        users.delete(PolicyService.user(rc), Ids.path(rc, "id"), policy.request(rc))
                .onSuccess(result ->
                {
                    calc.invalidate(CalcService.ALL);

                    CalcService.publish(rc.vertx(), CalcService.ALL);

                    rc.json(result);
                })
                .onFailure(rc::fail);
    }

    private void deletionPreview(RoutingContext rc)
    {
        users.deletionPreview(PolicyService.user(rc), Ids.path(rc, "id")).onSuccess(rc::json).onFailure(rc::fail);
    }

    private void invite(RoutingContext rc)
    {
        users.resendInvite(PolicyService.user(rc), Ids.path(rc, "id"), policy.request(rc))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    // ------------------------------------------------------------------ roles

    private void matrix(RoutingContext rc)
    {
        roles.matrix().onSuccess(rc::json).onFailure(rc::fail);
    }

    private void createRole(RoutingContext rc)
    {
        JsonObject body = BodySchema.body(rc);

        roles.create(PolicyService.user(rc), body.getString("name"), UUID.fromString(body.getString("fromRoleId")),
                        body.getString("description"))
                .onSuccess(role ->
                {
                    rc.response().setStatusCode(201);
                    rc.json(role);
                })
                .onFailure(rc::fail);
    }

    private void toggle(RoutingContext rc)
    {
        roles.toggle(PolicyService.user(rc), Ids.path(rc, "id"), rc.pathParam("permission"),
                        BodySchema.body(rc).getBoolean("granted"))
                .onSuccess(rc::json)
                .onFailure(rc::fail);
    }

    private void deleteRole(RoutingContext rc)
    {
        roles.delete(PolicyService.user(rc), Ids.path(rc, "id"))
                .onSuccess(v -> rc.response().setStatusCode(204).end())
                .onFailure(rc::fail);
    }
}
