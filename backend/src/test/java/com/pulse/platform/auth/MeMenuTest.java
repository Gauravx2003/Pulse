package com.pulse.platform.auth;

import com.pulse.platform.nav.Navigation;
import com.pulse.testsupport.TestApi;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.pulse.testsupport.TestApi.DEMO_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 3.4: the permission catalogue and {@code GET /me}. Admin, Manager and Employee each get
 * exactly the menu of the screen map (reference section 3), with groups in menu order.
 */
class MeMenuTest
{
    private static final int PORT = 18_194;

    private static final List<String> PERSON_SCREENS = List.of("Overview", "Progress", "Work items", "Pull requests",
            "Effort", "Bugs & support", "Customer visits", "Attendance", "OKRs", "Training plans", "Road map",
            "Culture values", "Reviews");

    private static final List<String> MANAGER_SCREENS = List.of("Action center", "Team utilization",
            "Appraisal & increments");

    private static final Set<String> SCREEN_MAP_GROUPS = Set.of(Navigation.GROUP_PERSON, Navigation.LABEL_PEOPLE,
            Navigation.LABEL_FEEDBACK, Navigation.GROUP_MANAGER, Navigation.GROUP_ADMIN);

    private static TestApi api;

    @BeforeAll
    static void start() throws Exception
    {
        api = TestApi.start(PORT, null);
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
    void employeeGetsOnlyTheirOwnPages() throws Exception
    {
        JsonObject me = me("priya@pulse.demo");

        assertEquals(Map.of(Navigation.GROUP_PERSON, PERSON_SCREENS), screenMapMenu(me));
        assertEquals(List.of(Navigation.GROUP_PERSON), groups(me), "no Manager or Administration group");
        assertEquals(Set.of("self.view", "okr.own", "oneonone", "review360.give"), permissions(me),
                "default Employee grants");
        assertTrue(me.getJsonObject("user").getString("personId") != null);
    }

    @Test
    void managerGetsTeamPagesAndTheirAdministrationItems() throws Exception
    {
        JsonObject me = me("rahul@pulse.demo");

        Map<String, List<String>> expected = new LinkedHashMap<>();

        expected.put(Navigation.LABEL_PEOPLE, PERSON_SCREENS);
        expected.put(Navigation.GROUP_MANAGER, MANAGER_SCREENS);
        expected.put(Navigation.GROUP_ADMIN, List.of("Users", "Data imports"));

        assertEquals(expected, screenMapMenu(me));
        assertEquals(List.copyOf(expected.keySet()), groups(me), "groups in menu order");
        assertEquals("/actions", me.getString("landing"));
    }

    @Test
    void adminGetsEveryScreen() throws Exception
    {
        JsonObject me = me("hr@pulse.demo");

        Map<String, List<String>> expected = new LinkedHashMap<>();

        expected.put(Navigation.LABEL_PEOPLE, PERSON_SCREENS);
        expected.put(Navigation.GROUP_MANAGER, MANAGER_SCREENS);
        expected.put(Navigation.GROUP_ADMIN, List.of("Users", "Data imports", "Roles & permissions", "Integrations",
                "Rules & thresholds"));

        assertEquals(expected, screenMapMenu(me));
        assertEquals(21, expected.values().stream().mapToInt(List::size).sum(), "the 21 screens of the screen map");
        assertTrue(permissions(me).containsAll(PermissionCatalogue.builtInKeys()), "Admin holds every built-in permission");
        assertTrue(permissions(me).contains("sample.view"), "and every module permission");
    }

    @Test
    void employeeWithoutAProfileSeesOnlyWhatTheyTakePartIn() throws Exception
    {
        // sana@ (account manager) has no performance profile: no own person pages, only the
        // screens where she records visit feedback and gives reviews (prototype routesFor).
        JsonObject me = me("sana@pulse.demo");

        assertEquals(Map.of(Navigation.LABEL_FEEDBACK, List.of("Customer visits", "Reviews")), screenMapMenu(me));
    }

    @Test
    void menuFollowsARolePermissionChange() throws Exception
    {
        api.exec("INSERT INTO roles (name, description) VALUES ('Attendance clerk', 'Uploads attendance')");
        api.exec("INSERT INTO role_permissions (role_id, permission) SELECT id, 'attendance.upload' FROM roles "
                + "WHERE name = 'Attendance clerk'");
        api.exec("UPDATE users SET role_id = (SELECT id FROM roles WHERE name = 'Attendance clerk') "
                + "WHERE email = 'meera@pulse.demo'");

        JsonObject me = me("meera@pulse.demo");

        Map<String, List<String>> expected = new LinkedHashMap<>();

        expected.put(Navigation.LABEL_FEEDBACK, List.of("Attendance"));
        expected.put(Navigation.GROUP_ADMIN, List.of("Data imports"));

        assertEquals(expected, screenMapMenu(me));
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject me(String email) throws Exception
    {
        TestApi.Session session = api.signIn(email, DEMO_PASSWORD);

        var res = api.get("/api/v1/me", session);

        assertEquals(200, res.statusCode(), res.bodyAsString());

        JsonObject me = res.bodyAsJsonObject();

        assertEquals(session.body().getJsonArray("menu"), me.getJsonArray("menu"), "sign-in returns the same menu");

        return me;
    }

    /** Group label -> item labels, for the screen-map groups only (module groups such as the sample's are left out). */
    private static Map<String, List<String>> screenMapMenu(JsonObject me)
    {
        Map<String, List<String>> out = new LinkedHashMap<>();

        for (Object g : me.getJsonArray("menu"))
        {
            JsonObject group = (JsonObject) g;

            if (SCREEN_MAP_GROUPS.contains(group.getString("group")))
            {
                List<String> labels = new ArrayList<>();

                group.getJsonArray("items").forEach(i -> labels.add(((JsonObject) i).getString("label")));

                out.put(group.getString("group"), labels);
            }
        }

        return out;
    }

    private static List<String> groups(JsonObject me)
    {
        return me.getJsonArray("menu").stream()
                .map(g -> ((JsonObject) g).getString("group"))
                .filter(SCREEN_MAP_GROUPS::contains)
                .toList();
    }

    private static Set<String> permissions(JsonObject me)
    {
        JsonArray p = me.getJsonArray("permissions");

        return Set.copyOf(p.stream().map(String::valueOf).toList());
    }
}
