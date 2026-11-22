package com.pulse.testsupport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code TestApi.start(port, config, modules...)} applies the named modules' migrations and seeds. */
class TestApiModulesTest
{
    @Test
    void namedModuleMigrationsAndSeedsAreApplied() throws Exception
    {
        try (TestApi api = TestApi.start(18_199, null, "reviews"))
        {
            assertEquals(1, api.count("SELECT count(*) FROM information_schema.tables "
                    + "WHERE table_schema = current_schema() AND table_name = 'review_cycles'"));
            assertTrue(api.count("SELECT count(*) FROM review_cycles") > 0, "S340 demo seed should have run");
        }
    }

    @Test
    void unknownModuleFailsFast()
    {
        assertThrows(IllegalArgumentException.class, () -> TestApi.start(18_199, null, "no-such-module"));
    }
}
