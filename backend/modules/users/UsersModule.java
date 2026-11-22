package com.pulse.modules.users;

import com.pulse.platform.module.ModuleContext;
import com.pulse.platform.module.PulseModule;
import io.vertx.ext.web.Router;

import java.util.List;
import java.util.Map;

/**
 * Users and Roles &amp; permissions (Track 1, task 5.1). Uses the platform tables (users, roles,
 * role_permissions, people, reporting_lines; V130 adds the lifecycle columns and the deletion record) and
 * the platform audit events for users and roles. It adds two separation-of-duties actions: nobody edits
 * or deletes their own account through the directory.
 */
public class UsersModule implements PulseModule
{
    @Override
    public String id()
    {
        return "users";
    }

    /** The refused self-edit and self-delete are audited under their action names. */
    @Override
    public List<String> auditEvents()
    {
        return List.of(UsersService.ACTION_EDIT, UsersService.ACTION_DELETE);
    }

    @Override
    public Map<String, String> selfRefusedActions()
    {
        return Map.of(
                UsersService.ACTION_EDIT, "You can't edit your own account here. Ask another admin.",
                UsersService.ACTION_DELETE, "You can't delete your own account.");
    }

    @Override
    public void routes(Router router, ModuleContext ctx)
    {
        new UsersRoutes(ctx).mount(router);
    }
}
