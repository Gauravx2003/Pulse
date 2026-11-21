package com.pulse.platform.audit;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The audit event catalogue (task 3.7, spec platform/audit-log). {@link AuditService#record} accepts
 * only actions listed here, so every audited event has one agreed name. The platform lists the events
 * of the spec; a module that needs an event of its own declares it in
 * {@link com.pulse.platform.module.PulseModule#auditEvents()}, and start-up registers it.
 */
public final class AuditEvents
{
    // ------------------------------------------------------------------ identity
    public static final String SIGN_IN = "auth.sign_in";
    public static final String SIGN_IN_FAILED = "auth.sign_in.failed";
    public static final String LOCKOUT = "auth.lockout";
    public static final String SIGN_OUT = "auth.sign_out";
    public static final String PASSWORD_CHANGED = "auth.password.changed";
    public static final String PASSWORD_RESET_REQUESTED = "auth.password.reset_requested";
    public static final String PASSWORD_RESET = "auth.password.reset";
    public static final String INVITE_SENT = "auth.invite.sent";
    public static final String INVITE_ACCEPTED = "auth.invite.accepted";

    // ------------------------------------------------------------------ administration
    public static final String ROLE_CREATED = "role.created";
    public static final String ROLE_PERMISSIONS_CHANGED = "role.permissions_changed";
    public static final String ROLE_DELETED = "role.deleted";
    public static final String USER_CREATED = "user.created";
    public static final String USER_UPDATED = "user.updated";
    public static final String USER_DEACTIVATED = "user.deactivated";
    public static final String USER_DELETED = "user.deleted";
    public static final String CREDENTIAL_CHANGED = "credential.changed";
    public static final String RULES_CHANGED = "rules.changed";
    public static final String RULES_RESET = "rules.reset";

    // ------------------------------------------------------------------ business
    public static final String DISCIPLINE_OVERRIDE = "discipline.override";
    public static final String BUG_ATTRIBUTION_OVERRIDE = "bugs.attribution.override";
    public static final String BUG_DISPUTE_RESOLVE = "bugs.dispute.resolve";
    public static final String APPRAISAL_EXCEPTION = "appraisal.exception";
    public static final String EFFORT_CONFIRM = "effort.confirm";
    public static final String EFFORT_REJECT = "effort.reject";
    public static final String VISIT_VERIFY = "visit.verify";
    public static final String REVIEWS_CALIBRATE = "reviews.calibrate";
    public static final String IMPORT_COMMITTED = "import.committed";
    public static final String IMPORT_UNDONE = "import.undone";
    public static final String REVIEW360_REVIEWER_ASSIGN = "review360.reviewer.assign";
    public static final String REVIEW360_PUBLISHED = "review360.results.published";
    public static final String INCREMENT_PROPOSE = "appraisal.increment.propose";
    public static final String INCREMENT_APPROVE = "appraisal.increment.approve";
    public static final String INCREMENT_SEND_BACK = "appraisal.increment.send_back";
    public static final String LETTER_RELEASED = "appraisal.letter.released";

    // ------------------------------------------------------------------ data access
    public static final String SALARY_READ = "salary.read";
    public static final String SALARY_EXPORT = "salary.export";
    public static final String REVIEW360_RESULTS_READ = "review360.results.read";
    public static final String DATA_EXPORT = "data.export";
    public static final String AUDIT_READ = "audit.read";
    public static final String AUDIT_EXPORT = "audit.export";

    // ------------------------------------------------------------------ refusals
    /** A route permission check refused the caller. */
    public static final String PERMISSION_REFUSED = "permission.refused";

    /** A visibility check refused the caller (person not visible, or not there). */
    public static final String ACCESS_REFUSED = "access.refused";

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+(\\.[a-z0-9_]+)+");

    private static final Map<String, String> PLATFORM = platform();

    private static final Set<String> MODULE_EVENTS = ConcurrentHashMap.newKeySet();

    private AuditEvents()
    {
    }

    /** Platform events with the group they belong to (identity, administration, business, data access, refusal). */
    public static Map<String, String> platformEvents()
    {
        return PLATFORM;
    }

    public static boolean isKnown(String action)
    {
        return action != null && (PLATFORM.containsKey(action) || MODULE_EVENTS.contains(action));
    }

    /** Registers module events at start-up; idempotent, so every HTTP verticle instance may call it. */
    public static void register(Collection<String> events)
    {
        for (String event : events)
        {
            if (!NAME.matcher(event).matches())
            {
                throw new IllegalArgumentException("Audit event '" + event + "' must look like 'area.action'");
            }

            MODULE_EVENTS.add(event);
        }
    }

    private static Map<String, String> platform()
    {
        Map<String, String> events = new LinkedHashMap<>();

        for (String e : new String[] {SIGN_IN, SIGN_IN_FAILED, LOCKOUT, SIGN_OUT, PASSWORD_CHANGED,
                PASSWORD_RESET_REQUESTED, PASSWORD_RESET, INVITE_SENT, INVITE_ACCEPTED})
        {
            events.put(e, "identity");
        }

        for (String e : new String[] {ROLE_CREATED, ROLE_PERMISSIONS_CHANGED, ROLE_DELETED, USER_CREATED,
                USER_UPDATED, USER_DEACTIVATED, USER_DELETED, CREDENTIAL_CHANGED, RULES_CHANGED, RULES_RESET})
        {
            events.put(e, "administration");
        }

        for (String e : new String[] {DISCIPLINE_OVERRIDE, BUG_ATTRIBUTION_OVERRIDE, BUG_DISPUTE_RESOLVE,
                APPRAISAL_EXCEPTION, EFFORT_CONFIRM, EFFORT_REJECT, VISIT_VERIFY, REVIEWS_CALIBRATE, IMPORT_COMMITTED,
                IMPORT_UNDONE, REVIEW360_REVIEWER_ASSIGN, REVIEW360_PUBLISHED, INCREMENT_PROPOSE, INCREMENT_APPROVE,
                INCREMENT_SEND_BACK, LETTER_RELEASED})
        {
            events.put(e, "business");
        }

        for (String e : new String[] {SALARY_READ, SALARY_EXPORT, REVIEW360_RESULTS_READ, DATA_EXPORT, AUDIT_READ,
                AUDIT_EXPORT})
        {
            events.put(e, "data access");
        }

        events.put(PERMISSION_REFUSED, "refusal");
        events.put(ACCESS_REFUSED, "refusal");

        return Collections.unmodifiableMap(events);
    }
}
