package com.pulse.platform.notify;

import com.pulse.calc.core.CalendarFormat;
import com.pulse.platform.policy.PolicyService;
import io.vertx.core.json.JsonObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Trigger helpers (task 5.3, spec platform/notifications "Notification triggers"): one builder per
 * trigger, returning the notice that {@link NotificationService#notify} delivers. Every text is built
 * here from names, plan or cycle titles, keys and dates only, so no caller can put a salary value,
 * 360° comment text or a reviewer's identity into a notification ("Notification privacy"). A notice
 * about another person carries {@code aboutPersonId} and the policy domain the recipient must see it
 * in; the 360° request notices carry none, because the request itself is the reviewer's access.
 *
 * <p>Links open the screen, tab or form the item is about; the owning screens read the query.
 *
 * <pre>
 * review360Assigned / review360Reminder              reviewer                  /reviews?tab=360[&amp;request=]
 * trainingAssigned                                   trainee                   /training?assignment=
 * checkinSubmitted                                   mentor                    /training?assignment=
 * checkinVerified / checkinNeedsWork                 trainee                   /training?assignment=
 * checkpointRequested                                reviewer of a checkpoint  /reviews?tab=360[&amp;request=]
 * assessmentDue / finalReviewDue / signOffDue        assessor, reviewer, trainee
 * trainingDisagreement                               mentor or manager
 * mentorSessionLogged / Requested / Blocked          mentee, mentor, manager   /training?tab=mentoring
 * visitPlanned / visitReportDue                      engineer                  /visits?visit=
 * visitFeedbackRequested                             account owner             /visits?visit=
 * lowPulse                                           manager                   /actions
 * attendanceUploaded                                 manager                   /attendance
 * staleNudge                                         assignee                  /work-items?issue=
 * </pre>
 */
public final class NotificationTriggers
{
    public static final String REVIEW360_ASSIGNED = "review360.assigned";

    public static final String REVIEW360_REMINDER = "review360.reminder";

    public static final String TRAINING_ASSIGNED = "training.assigned";

    public static final String CHECKIN_SUBMITTED = "training.checkin_submitted";

    public static final String CHECKIN_VERIFIED = "training.checkin_verified";

    public static final String CHECKIN_NEEDS_WORK = "training.checkin_needs_work";

    public static final String CHECKPOINT_REQUESTED = "training.checkpoint_requested";

    public static final String ASSESSMENT_DUE = "training.assessment_due";

    public static final String FINAL_REVIEW_DUE = "training.final_review_due";

    public static final String SIGN_OFF_DUE = "training.sign_off_due";

    public static final String TRAINING_DISAGREEMENT = "training.disagreement";

    public static final String MENTOR_SESSION_LOGGED = "mentoring.session_logged";

    public static final String MENTOR_SESSION_REQUESTED = "mentoring.session_requested";

    public static final String MENTOR_SESSION_BLOCKED = "mentoring.session_blocked";

    public static final String VISIT_PLANNED = "visit.planned";

    public static final String VISIT_REPORT_DUE = "visit.report_due";

    public static final String VISIT_FEEDBACK_REQUESTED = "visit.feedback_requested";

    public static final String LOW_PULSE = "pulse.low";

    public static final String ATTENDANCE_UPLOADED = "attendance.uploaded";

    public static final String STALE_NUDGE = "work.stale_nudge";

    /** Rater group of a 360° request (spec culture/review-360). */
    public enum RaterGroup
    {
        SELF, MANAGER, INTERNAL, CROSS
    }

    private NotificationTriggers()
    {
    }

    // ------------------------------------------------------------------ 360° reviews

    /**
     * "Share 360° feedback on Rohit Kulkarni · Q3 2026 · due 28 Sep" to the reviewer; the self-review
     * reads "Complete your self-review · …". Names the subject, cycle and due date only.
     */
    public static JsonObject review360Assigned(String reviewerUserId, String subjectName, String cycleName,
                                               LocalDate due, RaterGroup group, String requestId)
    {
        return notice(reviewerUserId, REVIEW360_ASSIGNED, review360Text(subjectName, cycleName, due, group),
                link("/reviews?tab=360", "request", requestId));
    }

    /** "Reminder: Share 360° feedback on … · due 28 Sep" (subject and due date only). */
    public static JsonObject review360Reminder(String reviewerUserId, String subjectName, String cycleName,
                                               LocalDate due, RaterGroup group, String requestId)
    {
        return notice(reviewerUserId, REVIEW360_REMINDER, "Reminder: " + review360Text(subjectName, cycleName, due, group),
                link("/reviews?tab=360", "request", requestId));
    }

    // ------------------------------------------------------------------ training

    /** "Training plan assigned: Software Engineer onboarding · starts 14 Sep" to the trainee. */
    public static JsonObject trainingAssigned(String traineeUserId, String planName, LocalDate start, String assignmentId)
    {
        return notice(traineeUserId, TRAINING_ASSIGNED,
                "Training plan assigned: " + planName + " · starts " + CalendarFormat.date(start),
                link("/training", "assignment", assignmentId));
    }

    /** "Rohit Kulkarni submitted a check-in · Software Engineer onboarding · week 2" to the mentor. */
    public static JsonObject checkinSubmitted(String mentorUserId, String traineePersonId, String traineeName,
                                              String planName, int week, String assignmentId)
    {
        return about(notice(mentorUserId, CHECKIN_SUBMITTED,
                        traineeName + " submitted a check-in · " + planName + " · week " + week,
                        link("/training", "assignment", assignmentId)),
                traineePersonId, PolicyService.DOMAIN_TRAINING);
    }

    /** "Your check-in for week 2 was verified · …" to the trainee. */
    public static JsonObject checkinVerified(String traineeUserId, String planName, int week, String assignmentId)
    {
        return notice(traineeUserId, CHECKIN_VERIFIED,
                "Your check-in for week " + week + " was verified · " + planName,
                link("/training", "assignment", assignmentId));
    }

    /** "Your check-in for week 2 needs work · …" to the trainee; the mentor's comment stays on the plan. */
    public static JsonObject checkinNeedsWork(String traineeUserId, String planName, int week, String assignmentId)
    {
        return notice(traineeUserId, CHECKIN_NEEDS_WORK,
                "Your check-in for week " + week + " needs work · " + planName,
                link("/training", "assignment", assignmentId));
    }

    /** "Day 14 checkpoint review for Rohit Kulkarni · … · due 28 Sep" to a checkpoint reviewer. */
    public static JsonObject checkpointRequested(String reviewerUserId, String traineeName, String planName, int day,
                                                 LocalDate due, String requestId)
    {
        return notice(reviewerUserId, CHECKPOINT_REQUESTED,
                "Day " + day + " checkpoint review for " + traineeName + " · " + planName + " · due " + CalendarFormat.date(due),
                link("/reviews?tab=360", "request", requestId));
    }

    /** "Assessment due for Rohit Kulkarni · … · by 9 Oct" to the assessor. */
    public static JsonObject assessmentDue(String assessorUserId, String traineePersonId, String traineeName,
                                           String planName, LocalDate due, String assignmentId)
    {
        return about(notice(assessorUserId, ASSESSMENT_DUE,
                        "Assessment due for " + traineeName + " · " + planName + " · by " + CalendarFormat.date(due),
                        link("/training", "assignment", assignmentId)),
                traineePersonId, PolicyService.DOMAIN_TRAINING);
    }

    /** "Write your final review for Rohit Kulkarni · …" to the mentor or manager (prototype wording). */
    public static JsonObject finalReviewDue(String reviewerUserId, String traineePersonId, String traineeName,
                                            String planName, String assignmentId)
    {
        return about(notice(reviewerUserId, FINAL_REVIEW_DUE,
                        "Write your final review for " + traineeName + " · " + planName,
                        link("/training", "assignment", assignmentId)),
                traineePersonId, PolicyService.DOMAIN_TRAINING);
    }

    /** "Your final feedback for … is ready — please review and sign off by 12 Oct" to the trainee. */
    public static JsonObject signOffDue(String traineeUserId, String planName, LocalDate due, String assignmentId)
    {
        return notice(traineeUserId, SIGN_OFF_DUE,
                "Your final feedback for " + planName + " is ready — please review and sign off by " + CalendarFormat.date(due),
                link("/training", "assignment", assignmentId));
    }

    /** "Rohit Kulkarni disagreed with the final feedback · …" to the mentor or manager; the reason stays on the plan. */
    public static JsonObject trainingDisagreement(String recipientUserId, String traineePersonId, String traineeName,
                                                  String planName, String assignmentId)
    {
        return about(notice(recipientUserId, TRAINING_DISAGREEMENT,
                        traineeName + " disagreed with the final feedback · " + planName,
                        link("/training", "assignment", assignmentId)),
                traineePersonId, PolicyService.DOMAIN_TRAINING);
    }

    // ------------------------------------------------------------------ mentoring

    /** "Arjun Mehta logged a mentoring session with you · 28 Sep" to the mentee. */
    public static JsonObject mentorSessionLogged(String menteeUserId, String mentorName, LocalDate day)
    {
        return notice(menteeUserId, MENTOR_SESSION_LOGGED,
                mentorName + " logged a mentoring session with you · " + CalendarFormat.date(day), "/training?tab=mentoring");
    }

    /** "Rohit Kulkarni requested a mentoring session" to the mentor. */
    public static JsonObject mentorSessionRequested(String mentorUserId, String menteePersonId, String menteeName)
    {
        return about(notice(mentorUserId, MENTOR_SESSION_REQUESTED, menteeName + " requested a mentoring session",
                "/training?tab=mentoring"), menteePersonId, PolicyService.DOMAIN_TRAINING);
    }

    /** "Rohit Kulkarni is blocked and needs a mentoring session" to the mentor or manager. */
    public static JsonObject mentorSessionBlocked(String recipientUserId, String menteePersonId, String menteeName)
    {
        return about(notice(recipientUserId, MENTOR_SESSION_BLOCKED, menteeName + " is blocked and needs a mentoring session",
                "/training?tab=mentoring"), menteePersonId, PolicyService.DOMAIN_TRAINING);
    }

    // ------------------------------------------------------------------ customer visits

    /** "Customer visit planned for you: Acme (Pune) · 2 Oct – 3 Oct · requested by Sana Mirza" (prototype wording). */
    public static JsonObject visitPlanned(String engineerUserId, String customer, String city, LocalDate from,
                                          LocalDate to, String requestedBy, String visitId)
    {
        return notice(engineerUserId, VISIT_PLANNED,
                "Customer visit planned for you: " + customer + " (" + city + ") · " + CalendarFormat.range(from, to)
                        + " · requested by " + requestedBy,
                link("/visits", "visit", visitId));
    }

    /** "Visit report due: Acme (2 Oct – 3 Oct)" to the engineer (prototype wording). */
    public static JsonObject visitReportDue(String engineerUserId, String customer, LocalDate from, LocalDate to,
                                            String visitId)
    {
        return notice(engineerUserId, VISIT_REPORT_DUE,
                "Visit report due: " + customer + " (" + CalendarFormat.range(from, to) + ")",
                link("/visits", "visit", visitId));
    }

    /** "Record customer feedback: Priya Shah visited Acme (2 Oct – 3 Oct)" to the account owner (prototype wording). */
    public static JsonObject visitFeedbackRequested(String ownerUserId, String engineerPersonId, String engineerName,
                                                    String customer, LocalDate from, LocalDate to, String visitId)
    {
        return about(notice(ownerUserId, VISIT_FEEDBACK_REQUESTED,
                        "Record customer feedback: " + engineerName + " visited " + customer + " ("
                                + CalendarFormat.range(from, to) + ")",
                        link("/visits", "visit", visitId)),
                engineerPersonId, PolicyService.DOMAIN_VISITS);
    }

    // ------------------------------------------------------------------ pulse, attendance, stale work

    /**
     * "Priya Shah's weekly pulse is low · week of 21 Sep — check in" to the manager (spec scenario "Low
     * pulse"). The answers themselves stay on the pulse screen.
     */
    public static JsonObject lowPulse(String managerUserId, String personId, String personName, LocalDate weekStart)
    {
        return about(notice(managerUserId, LOW_PULSE,
                        personName + "'s weekly pulse is low · week of " + CalendarFormat.date(weekStart) + " — check in",
                        "/actions"),
                personId, PolicyService.DOMAIN_PULSE);
    }

    /** "Attendance for Sep 2026 was uploaded" to a manager. */
    public static JsonObject attendanceUploaded(String managerUserId, LocalDate month)
    {
        return notice(managerUserId, ATTENDANCE_UPLOADED,
                "Attendance for " + CalendarFormat.monthYear(month) + " was uploaded", "/attendance");
    }

    /** "Nudge: PLAT-142 has had no activity for 6 days" to the assignee. */
    public static JsonObject staleNudge(String assigneeUserId, String issueKey, int idleDays)
    {
        return notice(assigneeUserId, STALE_NUDGE,
                "Nudge: " + issueKey + " has had no activity for " + idleDays + (idleDays == 1 ? " day" : " days"),
                link("/work-items", "issue", issueKey));
    }

    // ------------------------------------------------------------------ helpers

    private static String review360Text(String subjectName, String cycleName, LocalDate due, RaterGroup group)
    {
        String what = switch (group)
        {
            case SELF -> "Complete your self-review";
            case MANAGER -> "Share 360° feedback on " + subjectName + " (manager review)";
            default -> "Share 360° feedback on " + subjectName;
        };

        return what + " · " + cycleName + " · due " + CalendarFormat.date(due);
    }

    private static JsonObject notice(String userId, String kind, String text, String link)
    {
        return new JsonObject().put("userId", userId).put("kind", kind).put("text", text).put("link", link);
    }

    private static JsonObject about(JsonObject notice, String personId, String domain)
    {
        return notice.put("aboutPersonId", personId).put("domain", domain);
    }

    private static String link(String path, String param, String value)
    {
        if (value == null || value.isBlank())
        {
            return path;
        }

        return path + (path.contains("?") ? "&" : "?") + param + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
