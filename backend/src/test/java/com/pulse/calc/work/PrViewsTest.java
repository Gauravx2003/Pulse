package com.pulse.calc.work;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 10.2: the rewrite suggestions of spec work/pull-requests ("Title fails", "Short issue comment")
 * under the default rules.
 */
class PrViewsTest
{

    private static final JsonObject RULES = new JsonObject().put("prCmtMin", 20);

    @Test
    void aFailingTitleGetsARewriteThatMeetsTheStandard()
    {
        String rewrite = PrViews.titleRewrite("fixed stuff.", "PLAT-2231", null, RULES);

        assertEquals("PLAT-2231 fix: fixed stuff", rewrite);
        assertTrue(PrStandard.titleOk(rewrite, RULES), rewrite);
    }

    @Test
    void theSeededSuggestionWinsAndAGoodTitleNeedsNone()
    {
        assertEquals("PLAT-2240 feat(alerts): threshold preview in rule editor",
                PrViews.titleRewrite("threshold preview", "PLAT-2240", "PLAT-2240 feat(alerts): threshold preview in rule editor", RULES));
        assertNull(PrViews.titleRewrite("PLAT-2240 feat(alerts): threshold preview in rule editor", "PLAT-2240", null, RULES));
    }

    @Test
    void theTypeIsReadFromTheTitleAndAMissingWorkItemIsMarked()
    {
        assertTrue(PrViews.titleRewrite("Add tenant quota page", "PLAT-1", null, RULES).startsWith("PLAT-1 feat: "));
        assertTrue(PrViews.titleRewrite("refactor ingest", null, null, RULES).startsWith(PrViews.NO_WORK_ITEM + " refactor: "));
        assertTrue(PrViews.titleRewrite("x", "PLAT-1", null, RULES).length() >= 10, "padded to the minimum");
    }

    @Test
    void aShortIssueCommentIsTooShortWithARewrite()
    {
        assertEquals(PrStandard.TOO_SHORT, PrStandard.commentClass("issue: too slow", RULES).getString(1));
        assertEquals("issue: too slow — because <reason / impact>", PrViews.commentRewrite("issue: too slow", RULES));
        assertEquals("praise: <what was done well> — or approve without a comment", PrViews.commentRewrite("LGTM", RULES));
        assertNull(PrViews.commentRewrite("issue: this leaks a connection when the retry path throws", RULES));
    }
}
