package com.pulse.platform.notify;

import com.pulse.platform.notify.NotificationTriggers.RaterGroup;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 5.3: every trigger helper of spec platform/notifications "Notification triggers" builds the
 * expected text, kind and in-app link, carries the visibility subject where it is about another
 * person, and passes the privacy rule.
 */
class NotificationTriggersTest
{
    private static final String USER = "00000000-0000-0000-0000-000000000001";

    private static final String PERSON = "00000000-0000-0000-0000-000000000002";

    private static final LocalDate DUE = LocalDate.of(2026, 9, 28);

    @Test
    void review360AssignedNamesTheSubjectCycleAndDueDateOnly()
    {
        JsonObject n = NotificationTriggers.review360Assigned(USER, "Rohit Kulkarni", "Software Engineer onboarding · Day 14",
                DUE, RaterGroup.INTERNAL, "r97");

        assertNotice(n, NotificationTriggers.REVIEW360_ASSIGNED,
                "Share 360° feedback on Rohit Kulkarni · Software Engineer onboarding · Day 14 · due 28 Sep",
                "/reviews?tab=360&request=r97");
        assertNull(n.getString("aboutPersonId"), "the request itself is the reviewer's access");

        assertEquals("Share 360° feedback on Rohit Kulkarni (manager review) · Q3 · due 28 Sep",
                NotificationTriggers.review360Assigned(USER, "Rohit Kulkarni", "Q3", DUE, RaterGroup.MANAGER, null).getString("text"));
        assertEquals("Complete your self-review · Q3 · due 28 Sep",
                NotificationTriggers.review360Assigned(USER, "Rohit Kulkarni", "Q3", DUE, RaterGroup.SELF, null).getString("text"));
    }

    @Test
    void review360ReminderNamesTheSubjectAndDueDateOnly()
    {
        JsonObject n = NotificationTriggers.review360Reminder(USER, "Priya Shah", "Q3 2026", DUE, RaterGroup.CROSS, null);

        assertNotice(n, NotificationTriggers.REVIEW360_REMINDER, "Reminder: Share 360° feedback on Priya Shah · Q3 2026 · due 28 Sep",
                "/reviews?tab=360");
    }

    @Test
    void trainingTriggers()
    {
        assertNotice(NotificationTriggers.trainingAssigned(USER, "Software Engineer onboarding", LocalDate.of(2026, 9, 14), "a1"),
                NotificationTriggers.TRAINING_ASSIGNED, "Training plan assigned: Software Engineer onboarding · starts 14 Sep",
                "/training?assignment=a1");

        JsonObject submitted = NotificationTriggers.checkinSubmitted(USER, PERSON, "Rohit Kulkarni", "Onboarding", 2, "a1");

        assertNotice(submitted, NotificationTriggers.CHECKIN_SUBMITTED, "Rohit Kulkarni submitted a check-in · Onboarding · week 2",
                "/training?assignment=a1");
        assertAbout(submitted, PolicyService.DOMAIN_TRAINING);

        assertNotice(NotificationTriggers.checkinVerified(USER, "Onboarding", 2, "a1"), NotificationTriggers.CHECKIN_VERIFIED,
                "Your check-in for week 2 was verified · Onboarding", "/training?assignment=a1");
        assertNotice(NotificationTriggers.checkinNeedsWork(USER, "Onboarding", 3, "a1"), NotificationTriggers.CHECKIN_NEEDS_WORK,
                "Your check-in for week 3 needs work · Onboarding", "/training?assignment=a1");
        assertNotice(NotificationTriggers.checkpointRequested(USER, "Rohit Kulkarni", "Onboarding", 14, DUE, "r95"),
                NotificationTriggers.CHECKPOINT_REQUESTED, "Day 14 checkpoint review for Rohit Kulkarni · Onboarding · due 28 Sep",
                "/reviews?tab=360&request=r95");

        JsonObject assessment = NotificationTriggers.assessmentDue(USER, PERSON, "Rohit Kulkarni", "Onboarding",
                LocalDate.of(2026, 10, 9), "a1");

        assertNotice(assessment, NotificationTriggers.ASSESSMENT_DUE, "Assessment due for Rohit Kulkarni · Onboarding · by 9 Oct",
                "/training?assignment=a1");
        assertAbout(assessment, PolicyService.DOMAIN_TRAINING);

        JsonObject finalReview = NotificationTriggers.finalReviewDue(USER, PERSON, "Rohit Kulkarni", "Onboarding", "a1");

        assertNotice(finalReview, NotificationTriggers.FINAL_REVIEW_DUE, "Write your final review for Rohit Kulkarni · Onboarding",
                "/training?assignment=a1");
        assertAbout(finalReview, PolicyService.DOMAIN_TRAINING);

        assertNotice(NotificationTriggers.signOffDue(USER, "Onboarding", LocalDate.of(2026, 10, 12), "a1"),
                NotificationTriggers.SIGN_OFF_DUE, "Your final feedback for Onboarding is ready — please review and sign off by 12 Oct",
                "/training?assignment=a1");

        JsonObject disagreement = NotificationTriggers.trainingDisagreement(USER, PERSON, "Rohit Kulkarni", "Onboarding", "a1");

        assertNotice(disagreement, NotificationTriggers.TRAINING_DISAGREEMENT,
                "Rohit Kulkarni disagreed with the final feedback · Onboarding", "/training?assignment=a1");
        assertAbout(disagreement, PolicyService.DOMAIN_TRAINING);
    }

    @Test
    void mentoringTriggers()
    {
        assertNotice(NotificationTriggers.mentorSessionLogged(USER, "Arjun Mehta", DUE), NotificationTriggers.MENTOR_SESSION_LOGGED,
                "Arjun Mehta logged a mentoring session with you · 28 Sep", "/training?tab=mentoring");

        JsonObject requested = NotificationTriggers.mentorSessionRequested(USER, PERSON, "Rohit Kulkarni");

        assertNotice(requested, NotificationTriggers.MENTOR_SESSION_REQUESTED, "Rohit Kulkarni requested a mentoring session",
                "/training?tab=mentoring");
        assertAbout(requested, PolicyService.DOMAIN_TRAINING);

        JsonObject blocked = NotificationTriggers.mentorSessionBlocked(USER, PERSON, "Rohit Kulkarni");

        assertNotice(blocked, NotificationTriggers.MENTOR_SESSION_BLOCKED, "Rohit Kulkarni is blocked and needs a mentoring session",
                "/training?tab=mentoring");
        assertAbout(blocked, PolicyService.DOMAIN_TRAINING);
    }

    @Test
    void visitTriggersUseThePrototypeWording()
    {
        LocalDate from = LocalDate.of(2026, 10, 2);
        LocalDate to = LocalDate.of(2026, 10, 3);

        assertNotice(NotificationTriggers.visitPlanned(USER, "Acme", "Pune", from, to, "Sana Mirza", "v1"),
                NotificationTriggers.VISIT_PLANNED, "Customer visit planned for you: Acme (Pune) · 2 Oct – 3 Oct · requested by Sana Mirza",
                "/visits?visit=v1");
        assertNotice(NotificationTriggers.visitReportDue(USER, "Acme", from, to, "v1"), NotificationTriggers.VISIT_REPORT_DUE,
                "Visit report due: Acme (2 Oct – 3 Oct)", "/visits?visit=v1");

        JsonObject feedback = NotificationTriggers.visitFeedbackRequested(USER, PERSON, "Priya Shah", "Acme", from, to, "v1");

        assertNotice(feedback, NotificationTriggers.VISIT_FEEDBACK_REQUESTED, "Record customer feedback: Priya Shah visited Acme (2 Oct – 3 Oct)",
                "/visits?visit=v1");
        assertAbout(feedback, PolicyService.DOMAIN_VISITS);
    }

    @Test
    void pulseAttendanceAndStaleWorkTriggers()
    {
        JsonObject pulse = NotificationTriggers.lowPulse(USER, PERSON, "Priya Shah", LocalDate.of(2026, 9, 21));

        assertNotice(pulse, NotificationTriggers.LOW_PULSE, "Priya Shah's weekly pulse is low · week of 21 Sep — check in", "/actions");
        assertAbout(pulse, PolicyService.DOMAIN_PULSE);

        assertNotice(NotificationTriggers.attendanceUploaded(USER, LocalDate.of(2026, 9, 1)), NotificationTriggers.ATTENDANCE_UPLOADED,
                "Attendance for Sep 2026 was uploaded", "/attendance");
        assertNotice(NotificationTriggers.staleNudge(USER, "PLAT-142", 6), NotificationTriggers.STALE_NUDGE,
                "Nudge: PLAT-142 has had no activity for 6 days", "/work-items?issue=PLAT-142");
        assertEquals("Nudge: PLAT-1 has had no activity for 1 day", NotificationTriggers.staleNudge(USER, "PLAT-1", 1).getString("text"));
    }

    @Test
    void privacyRuleRefusesSalaryValues()
    {
        for (String text : List.of("Increment approved: ₹1,20,000", "New CTC is ready", "Salary revised for Priya",
                "Raise of 2 lakh", "Bonus INR 5000", "$4000 increment"))
        {
            assertThrows(IllegalArgumentException.class, () -> NotificationService.checkText(text), text);
        }

        assertThrows(IllegalArgumentException.class, () -> NotificationService.checkText(" "));
        assertThrows(IllegalArgumentException.class, () -> NotificationService.checkText("x".repeat(NotificationService.MAX_TEXT + 1)));
        assertDoesNotThrow(() -> NotificationService.checkText("Approved · Priya Shah"));
    }

    @Test
    void linksStayInsidePulse()
    {
        assertDoesNotThrow(() -> NotificationService.checkLink("/reviews?tab=360"));
        assertDoesNotThrow(() -> NotificationService.checkLink(null));

        for (String link : List.of("https://evil.example", "//evil.example/x", "javascript:alert(1)", "/a\\b"))
        {
            assertThrows(IllegalArgumentException.class, () -> NotificationService.checkLink(link), link);
        }
    }

    private static void assertNotice(JsonObject n, String kind, String text, String link)
    {
        assertEquals(USER, n.getString("userId"));
        assertEquals(kind, n.getString("kind"));
        assertEquals(text, n.getString("text"));
        assertEquals(link, n.getString("link"));
        assertDoesNotThrow(() -> NotificationService.checkText(n.getString("text")), "privacy rule: " + text);
        assertDoesNotThrow(() -> NotificationService.checkLink(n.getString("link")));
        assertFalse(n.getString("text").contains("₹"));
    }

    private static void assertAbout(JsonObject n, String domain)
    {
        assertEquals(PERSON, n.getString("aboutPersonId"));
        assertEquals(domain, n.getString("domain"));
        assertTrue(n.containsKey("domain"));
    }
}
