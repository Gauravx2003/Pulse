# Proposal

## Why

Pulse (employee performance and growth system for engineering teams) has a complete product spec, clickable prototype and design system in `reference/`, but no production specs. The reference material is pilot-grade: it targets a prototype with in-memory data, a 6-character temporary password, hardcoded dates and no persistence. Pulse holds salary, attendance, 360° feedback and performance data, so the build needs production-grade, modular, testable specs first, with the security, privacy and data gaps found in the reference review closed up front.

## What Changes

- Create the baseline capability specs for Pulse v1, one cohesive capability per module, split into cross-cutting **platform** capabilities and **domain** capabilities.
- Lock the stack: Vue.js (SPA), Vert.x (API, workers), PostgreSQL (data, audit table).
- Apply the locked product decisions:
  - Password sign-in with hashed passwords; SSO is out of scope for v1.
  - Canonical attendance CSV columns: `employee_email, date, status, leave_type, applied_on, check_in, check_out`.
  - Office policy is a global default (3 days a week), configurable in Rules & thresholds.
  - Only `attendance.upload` holders (HR/Admin) upload attendance and the holiday calendar.
  - Appraisal budget defaults to 12% of payroll and is editable in Appraisal Standards. The effective date (default 1 Oct) is a per-cycle field, not a constant.
  - 360° comments are kept anonymized when a user is deleted; reviewer identity and subject identity are detached (refined in `platform/privacy-retention`).
  - Attendance, the working-day calendar and the import framework ship in the foundation phase, because capacity and Discipline rule h depend on them.
- Close the gaps found in the reference spec, as explicit requirements:
  - Auth: password reset and change, password policy, bcrypt, one-time set-password invite links instead of emailed passwords, session revocation, per-IP and per-account throttling. Demo accounts (`demo123`) exist only behind `PULSE_DEMO_SEED`.
  - Authorization: one service-layer policy enforced in the Vert.x layer with per-role contract tests, separation of duties (no self-approval), effective-dated reporting lines, custom least-privilege roles, UUID identifiers.
  - Salary/CTC comes from the people seed and an optional Users CSV column, with access limited to appraisal roles and read auditing.
  - 360° anonymity hardening (threshold enforced server-side, no differencing via preliminary results, pseudonymous reviewer ids).
  - A simple append-only audit table with an extended event list.
  - CSV safety (formula-injection escaping, bound check ids, snapshot-based undo, enumerated attendance status and leave values).
  - Rule versioning: closed periods and submitted appraisals are frozen and reproducible.
  - Seeded tool data with a Sync now simulation for TFS, Slack and HRMS, then, later in the same week and only after seed-based acceptance, live read-only GitHub (personal access token) and Jira Cloud (API token) connectors for the demo; time zone Asia/Kolkata with a pinnable demo date.
- Defer to a later release: MFA, KMS and field-level encryption, PostgreSQL row-level security, audit hash chain, live OAuth, webhooks, scheduled live polling and real connectors for TFS, Slack, HRMS and PagerDuty (see "Deferred hardening" in `design.md`).
- Record the remaining open questions in `design.md`, so they are decided rather than assumed.

## Capabilities

### New Capabilities

Platform (cross-cutting):
- `platform/auth-session`: password sign-in, lockout and throttling, password policy and reset, sessions and revocation, flag-gated demo accounts.
- `platform/rbac-visibility`: permissions, built-in and custom roles, visibility rules, server-side enforcement in one policy service, separation of duties.
- `platform/audit-log`: append-only audit table, event catalogue, access to the log.
- `platform/secrets-credentials`: integration credential forms in v1: validation, encrypted storage for the GitHub and Jira tokens, masked placeholders for seeded tools, write-only display, rotation display.
- `platform/security-baseline`: transport and browser security headers, CSP, CSRF, CORS, input validation, output encoding, rate limiting, dependency and static scanning.
- `platform/privacy-retention`: retention windows, user deletion and anonymization, 360° comment retention, data-subject requests, salary protection.
- `platform/period-calendar`: sprint, month, release, quarter and all periods; working-day calendar; time zone and the clock.
- `platform/calculation-engine`: single calculation service, formulas and defaults, snapshots for closed periods, rule versioning.
- `platform/rules-thresholds`: every editable threshold, weight and point value, validation, reset to defaults.
- `platform/csv-import-framework`: check, commit, undo pipeline, parsing, limits, safety, check ids and history.
- `platform/jobs-scheduler`: in-process scheduled jobs, manual trigger, run records, retries.
- `platform/api-conventions`: versioned REST API, errors, pagination, optimistic locking, identifiers.
- `platform/notifications`: in-app notifications, bell, menu badges, optional email, Slack and Teams copies.
- `platform/app-shell-ui`: Vue shell, navigation, design tokens, theming, responsive and accessibility requirements, empty states.

Administration:
- `admin/users-roles`: user directory, add, edit, deactivate, delete, invites, roles and permission matrix, custom roles.
- `admin/integrations-connectors`: connector tiles, live GitHub and Jira connectors with real Test connection and Sync now, seeded and simulated behaviour for other tools, coexistence of seed and live data, tile states, person matching, Jira configuration.
- `admin/data-imports-exports`: import cards, templates, exports, data-source map, upload history; per-import column and check definitions.

Work data:
- `work/work-items`: live and history views, stale logic, issue drawer, nudge.
- `work/team-utilization`: heatmap, people table, rebalance, period views.
- `work/pull-requests`: PR standard, review comment standard, rubber-stamp, PR detail.
- `work/effort-extra-mile`: hours, code-only days, extra-mile detection, confirmation, caps.
- `work/bugs-support`: attribution, dispute, take, proactive fixes, support tickets.

Time away:
- `time/customer-visits`: visit lifecycle, validation, report, feedback, verification, points.
- `time/attendance`: monthly record, statuses, rules, score, upload, effects on capacity, Discipline h and Collaboration.

Goals and growth:
- `growth/okrs`: objectives, key results, progress, check-ins, import and export.
- `growth/roadmap`: milestones, promotion readiness, skills matrix.
- `growth/training-mentoring`: plan library, assignment, lifecycle, check-ins, assessment, reviews, sign-off, mentoring, rating.

Culture and reviews:
- `culture/culture-values`: eleven values, value score, data parameters, Discipline rules and override.
- `culture/reviews-performance`: performance index breakdown, calibration, review summary.
- `culture/one-on-ones-kudos`: 1:1 agenda, notes, action items, kudos.
- `culture/review-360`: cycles, assignment, form, results, anonymity, publish.

Appraisal:
- `appraisal/appraisal`: score, bands, increments, budget, workflow, evidence, standards, export.

Manager and employee insight:
- `insight/action-center`: do-today list, people signals, forecast, capacity, skills coverage, digest.
- `insight/progress-pulse`: next steps, achievements, trends, weekly pulse, team wins.

### Modified Capabilities

None. The project has no existing specs.

## Impact

- **New code base:** Vue.js SPA, Vert.x service (API, workers, in-process scheduler), PostgreSQL schema and migrations.
- **External systems:** only GitHub (`api.github.com`) and Jira Cloud (`*.atlassian.net`) are called, read-only, when an Admin connects them. TFS, Slack and HRMS data is pre-seeded; nudges, kudos and invites go to an outbox (optional SMTP). Other live connectors are a later release.
- **Data sensitivity:** salary, attendance check-in times, 360° feedback, weekly pulse notes. These drive the security and privacy requirements.
- **Reference material** (`reference/`) stays the behavioural reference. Where a spec here differs from it, this change wins and the difference is called out in `design.md`.
- **Scope note:** this change defines the whole of v1. The one-week cut line is chosen later, in `tasks`, not here.
