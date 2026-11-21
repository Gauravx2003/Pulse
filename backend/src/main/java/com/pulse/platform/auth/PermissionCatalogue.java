package com.pulse.platform.auth;

import com.pulse.platform.module.ModuleRegistry;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The permission catalogue (task 3.4, spec platform/rbac-visibility "Built-in roles and
 * permissions"): the 19 built-in permissions of the reference permission matrix, each with the area
 * it is grouped under on Roles & permissions and what it unlocks, followed by the permissions that
 * modules register through their descriptor. The built-in Admin role always holds every entry.
 */
public final class PermissionCatalogue
{
    /** Built-in permissions in matrix order: key, area, description (prototype {@code PERMS}). */
    private static final List<String[]> BUILT_IN = List.of(
            new String[] {"self.view", "My work", "View own overview, sprint, effort, culture, road map, reviews"},
            new String[] {"team.view", "People", "View direct reports, team utilization and team effort"},
            new String[] {"all.view", "People", "View every person in the organisation"},
            new String[] {"okr.own", "OKRs", "Create, update and delete own OKRs"},
            new String[] {"okr.team", "OKRs", "Manage team OKRs and OKRs of visible people"},
            new String[] {"oneonone", "Feedback", "1:1 notes and kudos"},
            new String[] {"effort.confirm", "Reviews", "Confirm or reject extra-mile points"},
            new String[] {"reviews.calibrate", "Reviews", "Submit reviews and see team calibration"},
            new String[] {"users.team", "Admin", "Add, edit and remove employees in own team"},
            new String[] {"users.all", "Admin", "Manage every user and assign any role"},
            new String[] {"roles.manage", "Admin", "Create roles and edit permissions"},
            new String[] {"integrations.manage", "Admin", "Connect and configure integrations"},
            new String[] {"rules.manage", "Admin", "Change rules, thresholds and weights"},
            new String[] {"review360.give", "360 reviews", "Give 360° feedback when asked (self, peer, cross-department)"},
            new String[] {"review360.manage", "360 reviews",
                    "Approve nominations, track progress and see results for visible people; with users.all, run cycles"},
            new String[] {"appraisal.manage", "Appraisal",
                    "See the appraisal dashboard and propose increments for visible people"},
            new String[] {"appraisal.approve", "Appraisal", "Approve increments, release letters and set appraisal standards"},
            new String[] {"training.manage", "Training",
                    "Create, edit, delete and assign training plans; request checkpoint reviews"},
            new String[] {"attendance.upload", "Attendance", "Upload monthly attendance and the holiday calendar"});

    /**
     * Platform permissions beyond the reference matrix: {@code audit.read} guards the audit log (spec
     * audit-log "Access to the audit log"); like every permission, Admin holds it.
     */
    private static final List<String[]> PLATFORM = List.<String[]>of(
            new String[] {"audit.read", "Admin", "Read and export the audit log"});

    /** Area shown for permissions a module registers. */
    public static final String MODULE_AREA = "Modules";

    /** key -> {key, area, description, builtIn}, in catalogue order. */
    private final Map<String, JsonObject> entries;

    private PermissionCatalogue(Map<String, JsonObject> entries)
    {
        this.entries = Collections.unmodifiableMap(entries);
    }

    /** The built-in permissions followed by every permission the discovered modules register. */
    public static PermissionCatalogue of(ModuleRegistry registry)
    {
        Map<String, JsonObject> entries = new LinkedHashMap<>();

        for (String[] p : BUILT_IN)
        {
            entries.put(p[0], entry(p[0], p[1], p[2], true));
        }

        for (String[] p : PLATFORM)
        {
            entries.put(p[0], entry(p[0], p[1], p[2], true));
        }

        for (String key : registry.permissions())
        {
            entries.putIfAbsent(key, entry(key, MODULE_AREA, "", false));
        }

        return new PermissionCatalogue(entries);
    }

    /** The keys of the 19 built-in permissions, in matrix order. */
    public static List<String> builtInKeys()
    {
        return BUILT_IN.stream().map(p -> p[0]).toList();
    }

    /** Every permission key, built-in first. */
    public Set<String> keys()
    {
        return new LinkedHashSet<>(entries.keySet());
    }

    public boolean contains(String key)
    {
        return entries.containsKey(key);
    }

    /** {@code [{key, area, description, builtIn}]} for the Roles & permissions matrix. */
    public JsonArray toJson()
    {
        JsonArray out = new JsonArray();

        entries.values().forEach(e -> out.add(e.copy()));

        return out;
    }

    private static JsonObject entry(String key, String area, String description, boolean builtIn)
    {
        return new JsonObject()
                .put("key", key)
                .put("area", area)
                .put("description", description)
                .put("builtIn", builtIn);
    }
}
