package com.pulse.modules.imports.types;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The team of a Team OKR row (rule confirmed with Track 2): own team, reports' one team, organisation's one team for all.view. */
class ActorTeamTest
{
    private static final JsonArray NONE = new JsonArray();

    @Test
    void ownTeamComesFirst()
    {
        assertEquals("Platform", ActorTeamRepository.resolve("Platform", JsonArray.of("QA", "Data"), JsonArray.of("Platform", "QA"), true)
                .getString("team"));
    }

    @Test
    void aManagerWithoutAProfileGetsTheirReportsOneTeam()
    {
        assertEquals("Platform", ActorTeamRepository.resolve(null, JsonArray.of("Platform"), JsonArray.of("Platform", "QA"), false)
                .getString("team"));
    }

    @Test
    void reportsInSeveralTeamsAreAmbiguous()
    {
        JsonObject team = ActorTeamRepository.resolve(null, JsonArray.of("Platform", "QA"), JsonArray.of("Platform", "QA"), true);

        assertNull(team.getString("team"));

        assertEquals(ActorTeamRepository.SEVERAL_TEAMS, team.getString("reason"));

        assertTrue(OkrsImportType.noTeamMessage(team).contains("several teams (Platform, QA)"));
    }

    @Test
    void hrWithoutProfileOrProfiledReportsGetsTheOrganisationsOneTeam()
    {
        assertEquals("Platform", ActorTeamRepository.resolve(null, NONE, JsonArray.of("Platform"), true).getString("team"));

        JsonObject several = ActorTeamRepository.resolve(null, NONE, JsonArray.of("Platform", "QA"), true);

        assertEquals(ActorTeamRepository.SEVERAL_TEAMS, several.getString("reason"));
    }

    @Test
    void withoutAllViewNoTeamIsNoTeam()
    {
        JsonObject team = ActorTeamRepository.resolve(null, NONE, JsonArray.of("Platform"), false);

        assertEquals(ActorTeamRepository.NO_TEAM, team.getString("reason"));

        assertEquals("Neither you nor your direct reports belong to a team, so Team OKRs can’t be imported.",
                OkrsImportType.noTeamMessage(team));
    }
}
