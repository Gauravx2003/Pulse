package com.pulse.platform.nav;

import com.pulse.platform.module.MenuEntry;
import com.pulse.platform.module.ModuleRegistry;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The generated side menu of {@code GET /me} (task 3.4, spec platform/app-shell-ui "Shell and
 * navigation", reference section 3 "Navigation and screen map").
 *
 * <p>The platform owns the 21 screens of the screen map with the permission rules of the
 * reference (prototype {@code menuFor} and {@code routesFor}), so the menu is correct before the
 * screens' modules land and no track edits a shared registry. Modules add menu entries only for
 * screens beyond the screen map; an entry whose key or path repeats a screen-map entry fails
 * start-up.
 *
 * <p>Groups come in a fixed order: the person group, Manager, Administration, then module groups
 * by name. The person group is labelled "My work" for users with a performance profile, "People"
 * for team or organisation viewers without one, and "Feedback" otherwise. A user with neither a
 * profile nor team or organisation visibility (e.g. an account manager outside engineering) has no
 * person pages of their own, so those screens are left out of their menu.
 */
public final class Navigation
{
    public static final String GROUP_PERSON = "My work";

    public static final String LABEL_PEOPLE = "People";

    public static final String LABEL_FEEDBACK = "Feedback";

    public static final String GROUP_MANAGER = "Manager";

    public static final String GROUP_ADMIN = "Administration";

    private static final List<String> PEOPLE = List.of("self.view", "team.view", "all.view");

    private static final List<String> TEAM = List.of("team.view", "all.view");

    /** The screen map, in menu order. */
    public static final List<MenuEntry> SCREENS = List.of(
            screen("overview", "Overview", "/overview", GROUP_PERSON, PEOPLE, 10),
            screen("progress", "Progress", "/progress", GROUP_PERSON, PEOPLE, 20),
            screen("work-items", "Work items", "/work-items", GROUP_PERSON, PEOPLE, 30),
            screen("pull-requests", "Pull requests", "/pull-requests", GROUP_PERSON, PEOPLE, 40),
            screen("effort", "Effort", "/effort", GROUP_PERSON, PEOPLE, 50),
            screen("bugs-support", "Bugs & support", "/bugs-support", GROUP_PERSON, PEOPLE, 60),
            screen("visits", "Customer visits", "/visits", GROUP_PERSON, PEOPLE, 70),
            screen("attendance", "Attendance", "/attendance", GROUP_PERSON, plus(PEOPLE, "attendance.upload"), 80),
            screen("okrs", "OKRs", "/okrs", GROUP_PERSON, List.of("okr.own", "okr.team"), 90),
            screen("training", "Training plans", "/training", GROUP_PERSON, plus(PEOPLE, "training.manage"), 100),
            screen("roadmap", "Road map", "/roadmap", GROUP_PERSON, PEOPLE, 110),
            screen("culture", "Culture values", "/culture", GROUP_PERSON, PEOPLE, 120),
            screen("reviews", "Reviews", "/reviews", GROUP_PERSON,
                    plus(PEOPLE, "oneonone", "review360.give", "review360.manage"), 130),
            screen("actions", "Action center", "/actions", GROUP_MANAGER, TEAM, 10),
            screen("team-utilization", "Team utilization", "/team-utilization", GROUP_MANAGER, TEAM, 20),
            screen("appraisal", "Appraisal & increments", "/appraisal", GROUP_MANAGER,
                    List.of("appraisal.manage", "appraisal.approve"), 30),
            screen("users", "Users", "/users", GROUP_ADMIN, List.of("users.team", "users.all"), 10),
            screen("imports", "Data imports", "/imports", GROUP_ADMIN,
                    List.of("okr.team", "users.team", "users.all", "attendance.upload", "rules.manage"), 20),
            screen("roles", "Roles & permissions", "/roles", GROUP_ADMIN, List.of("roles.manage"), 30),
            screen("integrations", "Integrations", "/integrations", GROUP_ADMIN, List.of("integrations.manage"), 40),
            screen("rules", "Rules & thresholds", "/rules", GROUP_ADMIN, List.of("rules.manage"), 50));

    /** Person screens that need the user's own profile (prototype {@code routesFor}). */
    private static final Set<String> PROFILE_SCREENS = Set.of("overview", "progress", "work-items", "pull-requests",
            "effort", "bugs-support", "okrs", "roadmap", "culture");

    private static final List<String> GROUP_ORDER = List.of(GROUP_PERSON, GROUP_MANAGER, GROUP_ADMIN);

    private final List<MenuEntry> entries;

    private Navigation(List<MenuEntry> entries)
    {
        this.entries = List.copyOf(entries);
    }

    /** The screen map plus the module entries; fails when a module repeats a key or path. */
    public static Navigation of(ModuleRegistry registry)
    {
        return of(registry.modules().stream().flatMap(m -> m.menu().stream()).toList());
    }

    static Navigation of(List<MenuEntry> moduleEntries)
    {
        List<MenuEntry> all = new ArrayList<>(SCREENS);
        Map<String, String> keys = new HashMap<>();
        Map<String, String> paths = new HashMap<>();

        SCREENS.forEach(s ->
        {
            keys.put(s.key(), "the screen map");
            paths.put(s.path(), "the screen map");
        });

        for (MenuEntry e : moduleEntries)
        {
            String keyOwner = keys.putIfAbsent(e.key(), "a module");
            String pathOwner = paths.putIfAbsent(e.path(), "a module");

            if (keyOwner != null || pathOwner != null)
            {
                throw new IllegalStateException("Menu entry '" + e.key() + "' (" + e.path() + ") repeats an entry of "
                        + (keyOwner != null ? keyOwner : pathOwner)
                        + "; screen-map screens are declared by the platform (platform/nav/Navigation)");
            }

            all.add(e);
        }

        return new Navigation(all);
    }

    /**
     * The menu for a user holding {@code granted}: {@code [{group, items: [{key, label, path, group,
     * order}]}]}, groups in the fixed order and items by their order.
     *
     * @param hasProfile whether the user has a performance profile (a {@code people} row)
     */
    public JsonArray menuFor(Set<String> granted, boolean hasProfile)
    {
        boolean teamViewer = TEAM.stream().anyMatch(granted::contains);
        boolean noOwnPages = !hasProfile && !teamViewer;

        String personLabel = hasProfile ? GROUP_PERSON : teamViewer ? LABEL_PEOPLE : LABEL_FEEDBACK;

        Map<String, JsonArray> groups = new LinkedHashMap<>();

        GROUP_ORDER.forEach(g -> groups.put(g, new JsonArray()));

        entries.stream()
                .filter(e -> e.visibleTo(granted))
                .filter(e -> !(noOwnPages && hiddenWithoutProfile(e, granted)))
                .sorted(Comparator.comparingInt((MenuEntry e) -> groupRank(e.group()))
                        .thenComparing(MenuEntry::group)
                        .thenComparingInt(MenuEntry::order)
                        .thenComparing(MenuEntry::key))
                .forEach(e ->
                {
                    JsonObject item = e.toJson();

                    if (GROUP_PERSON.equals(e.group()))
                    {
                        item.put("group", personLabel);
                    }

                    groups.computeIfAbsent(e.group(), g -> new JsonArray()).add(item);
                });

        JsonArray menu = new JsonArray();

        groups.forEach((group, items) ->
        {
            if (!items.isEmpty())
            {
                menu.add(new JsonObject()
                        .put("group", GROUP_PERSON.equals(group) ? personLabel : group)
                        .put("items", items));
            }
        });

        return menu;
    }

    private static boolean hiddenWithoutProfile(MenuEntry e, Set<String> granted)
    {
        if (PROFILE_SCREENS.contains(e.key()))
        {
            return true;
        }

        if ("training".equals(e.key()))
        {
            return !granted.contains("training.manage");
        }

        if ("attendance".equals(e.key()))
        {
            return !granted.contains("attendance.upload");
        }

        return false;
    }

    private static int groupRank(String group)
    {
        int i = GROUP_ORDER.indexOf(group);

        return i < 0 ? GROUP_ORDER.size() : i;
    }

    private static MenuEntry screen(String key, String label, String path, String group, List<String> anyOf, int order)
    {
        return new MenuEntry(key, label, path, group, anyOf, order);
    }

    private static List<String> plus(List<String> base, String... more)
    {
        List<String> out = new ArrayList<>(base);

        out.addAll(List.of(more));

        return out;
    }
}
