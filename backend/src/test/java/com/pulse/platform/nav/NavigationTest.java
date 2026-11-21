package com.pulse.platform.nav;

import com.pulse.platform.module.MenuEntry;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 3.4: menu generation without a database. */
class NavigationTest
{
    @Test
    void theScreenMapHasTwentyOneScreensWithUniqueKeysAndPaths()
    {
        assertEquals(21, Navigation.SCREENS.size());
        assertEquals(21, Navigation.SCREENS.stream().map(MenuEntry::key).distinct().count());
        assertEquals(21, Navigation.SCREENS.stream().map(MenuEntry::path).distinct().count());
    }

    @Test
    void noPermissionsMeansNoMenu()
    {
        assertTrue(Navigation.of(List.of()).menuFor(Set.of(), true).isEmpty());
    }

    @Test
    void anyOfTheListedPermissionsShowsAnEntry()
    {
        JsonArray menu = Navigation.of(List.of()).menuFor(Set.of("rules.manage"), false);

        assertEquals(1, menu.size());
        assertEquals(Navigation.GROUP_ADMIN, menu.getJsonObject(0).getString("group"));
        assertEquals(List.of("Data imports", "Rules & thresholds"), labels(menu.getJsonObject(0)),
                "both entries rules.manage unlocks, in screen-map order");
    }

    @Test
    void moduleGroupsComeAfterTheScreenMapGroups()
    {
        Navigation nav = Navigation.of(List.of(new MenuEntry("lab.home", "Lab", "/lab", "Developer", (String) null, 1)));

        JsonArray menu = nav.menuFor(Set.of("self.view", "team.view"), true);

        assertEquals(List.of(Navigation.GROUP_PERSON, Navigation.GROUP_MANAGER, "Developer"),
                menu.stream().map(g -> ((JsonObject) g).getString("group")).toList());
    }

    @Test
    void aModuleCannotRedeclareAScreen()
    {
        MenuEntry clash = new MenuEntry("okrs", "OKRs", "/my-okrs", "My work", "okr.own", 1);
        MenuEntry samePath = new MenuEntry("okrs.mine", "OKRs", "/okrs", "My work", "okr.own", 1);

        assertThrows(IllegalStateException.class, () -> Navigation.of(List.of(clash)));
        assertThrows(IllegalStateException.class, () -> Navigation.of(List.of(samePath)));
    }

    private static List<String> labels(JsonObject group)
    {
        return group.getJsonArray("items").stream().map(i -> ((JsonObject) i).getString("label")).toList();
    }
}
