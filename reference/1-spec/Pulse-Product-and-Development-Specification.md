# Pulse — Product & Development Specification

Oct 1, 2026 · @Ranjit Sindhav

## 1. Overview

Pulse is an employee performance and growth system for engineering teams. It turns data from Jira, GitHub, Microsoft TFS, the support desk, the HR system and in-app workflows into one picture that the manager and the employee share. This specification covers every feature in the clickable prototype (prototype v11, design system v30) and is the build reference for the development team.

**Goals**

- Give managers a daily, prioritized view of what needs attention: workload, stale work, reviews, training, visits, attendance and appraisal.
- Measure the 11 culture values transparently, from 360° ratings plus traceable data signals.
- Credit extra effort (mid-sprint work, incidents, customer visits, proactive fixes) that sprint metrics alone miss.
- Run the full people cycle in one place: OKRs, training and mentoring, 1:1s, 360° reviews, performance review and the appraisal with increments.
- Let each employee see exactly what their manager sees about them, and where every number comes from.

**Non-goals for v1**

- Payroll processing. Pulse exports increment proposals for payroll and does not pay anyone.
- Leave approval. Leave is approved in the HR system, and Pulse only reads the monthly attendance record.
- Ranking people against each other on employee-facing screens.
- Automatic ratings. Every signal is an input to a conversation, and managers confirm or override.

**Product principles**

1. **Signals, not verdicts.** Copy describes facts, for example "4 issues have no update in 2+ working days", never judgements.
2. **Always show the reference.** Utilization shows capacity, a count shows its total, a score shows its inputs.
3. **Transparent by default.** Every score has a detail view with its formula, sources and parameters.
4. **Fair by design.** Leave, holidays, pre-joining days and onboarding are excluded from denominators. No opportunity means no penalty, and sick or emergency leave is always excused.
5. **Nothing saved without confirmation.** Imports are checked first and can be undone. Overrides need a written reason.

**Glossary**

| Term | Meaning |
| --- | --- |
| Utilization | Assigned work in days ÷ capacity in days for the period |
| Capacity | Working days in the period minus leave, holidays and customer-visit days, × focus hours ÷ day hours |
| Stale issue | An open issue with no update for more than 2 working days (configurable) |
| Extra-mile points | Confirmed extra work that adds a bonus to culture values |
| Performance index | Weighted average of the Delivery, Quality, Culture, OKR and Growth pillars, on a 1–5 scale |
| Trainee | An Employee with an active training plan. There is no trainee role or login |
| Mentor | Any user assigned to guide a trainee on a plan |
| Account owner | The sales or support employee who records customer feedback after a visit |
| Data share | The part of a culture value score that comes from data signals (default 40%) |

## 2. Users, roles and permissions

Pulse has exactly three built-in roles: Admin, Manager and Employee. Every menu item, page, tab and button is shown or hidden based on the signed-in user's permissions. A trainee, an intern, an account manager and a colleague from another department are all Employees.

**Sign-in**

- Work email and password (SSO with Google, Microsoft or Okta is the production target). A wrong password shows "Email or password is incorrect." Deactivated accounts cannot sign in.
- After sign-in the user lands on the first page their role allows: Action center for managers and Admin, Overview for employees.
- Sign-in resets every per-session view state (tabs, selected person, period, open imports).
- Sign out is in the top bar and the side menu.

**Permission matrix**

| Permission | Admin | Manager | Employee | Unlocks |
| --- | --- | --- | --- | --- |
| self.view | ✓ | ✓ | ✓ | Own Overview, Progress, Work items, PRs, Effort, Bugs & support, Visits, Attendance, Road map, Culture values, Reviews |
| team.view | ✓ | ✓ |  | Direct reports on every person page, Action center, Team utilization, team tabs |
| all.view | ✓ |  |  | Everyone in the organization |
| okr.own | ✓ | ✓ | ✓ | Create, edit, check in, delete and import own OKRs |
| okr.team | ✓ | ✓ |  | Team OKRs and OKRs of visible people, including CSV import |
| oneonone | ✓ | ✓ | ✓ | 1:1s & feedback tab, kudos |
| effort.confirm | ✓ | ✓ |  | Confirm or reject extra-mile points and visit points |
| reviews.calibrate | ✓ | ✓ |  | Submit performance reviews, calibration table, Discipline override |
| users.team | ✓ | ✓ |  | Add, edit, delete and import Employees who report to them |
| users.all | ✓ |  |  | Manage every user, assign any role and manager |
| roles.manage | ✓ |  |  | Roles & permissions matrix, custom roles |
| integrations.manage | ✓ |  |  | Integrations and credentials |
| rules.manage | ✓ |  |  | Rules & thresholds, holiday calendar import |
| review360.give | ✓ | ✓ | ✓ | Answer assigned 360° reviews |
| review360.manage | ✓ | ✓ |  | Assign reviewers, track progress, see team results; with users.all, run cycles |
| appraisal.manage | ✓ | ✓ |  | Appraisal dashboard for visible people, propose increments |
| appraisal.approve | ✓ |  |  | Approve increments, release letters, set appraisal standards |
| training.manage | ✓ | ✓ |  | Plan library, assign plans and mentors, verify, final reviews |
| attendance.upload | ✓ |  |  | Upload monthly attendance and the holiday calendar |

**Visibility rules**

- A person's data is visible to that person, to their manager (team.view, direct reports) and to all.view holders. Peers never see each other's pages.
- Managers see deactivated reports only with users.all.
- Mentors see the training data of their own mentees, even when they are not the manager.
- Account owners see and record feedback only for visits where they are the owner.
- 360° peer answers are anonymous, and a rater group with fewer than 3 responses is merged or hidden.
- Admin always keeps every permission. Built-in roles can be edited, and custom roles can be created from an existing role and deleted when no user has them.
- A page opened without permission shows a "You don't have access" card, never an error.

**Demo accounts** (password demo123): hr@ (Admin), rahul@ (Manager), priya@, arjun@, neha@, kiran@, dev@, sara@ (Employees), rohit@ and anjali@ (new joiners on training plans), sana@ (account manager), meera@ (Product, cross-department).

**Screen detail — Sign in**

- Centered card: Pulse logo, title "Sign in", tagline "Performance, road maps and live workload", Work email and Password fields, and a primary **Sign in** button (Enter submits).
- Error line under the fields: "Email or password is incorrect.", "This account is deactivated. Contact HR." or "Your role no longer exists. Contact HR."
- "Demo accounts · password demo123" lists one card per role (Admin, Manager, Employee) that fills the form, plus a line naming the other demo employees. Trainees are Employees, so there is no trainee login.
- After sign-in a toast confirms "Signed in as \<name> · \<role>".

## 3. Navigation and screen map

The side menu has three groups, and each item appears only when the user's permissions allow it. Twenty-one screens make up the product, and the table below is the complete list.

**Shell (every screen)**

- Side menu groups: **My work / People** (Overview, Progress, Work items, Pull requests, Effort, Bugs & support, Customer visits, Attendance, OKRs, Training plans, Road map, Culture values, Reviews), **Manager** (Action center, Team utilization, Appraisal & increments) and **Administration** (Users, Data imports, Roles & permissions, Integrations, Rules & thresholds).
- Menu badges show counts: stale work items, pending effort items, visits needing action, training actions, 360° reviews waiting, Action center items.
- Top bar: breadcrumb with the user and role, a person selector on person pages (managers and Admin), Jira sync status, the notification bell, a light / dark theme toggle and Sign out.
- At phone width the side menu collapses behind a menu button, KPI tiles wrap two per row and tables scroll inside their card.

**Screens**

| Screen | Tabs and detail views | Employee | Manager | Admin |
| --- | --- | --- | --- | --- |
| Action center | Do today · people signals · sprint forecast · next-sprint capacity · skills coverage · team pulse · team wins · weekly digest | – | ✓ (landing) | ✓ (landing) |
| Progress | Next steps · achievements · index trend · utilization by sprint · culture Q2→Q3 · OKRs, PRs, training · weekly pulse · team wins | own | reports | all |
| Overview | Sprint, month or release · overall utilization by sprint · overload alert · needs-attention issues → issue drawer · bugs & support · visits · training · culture radar · road map | own | reports | all |
| Work items | Sprint, month or release · summary · issue history with filters · live list with search and sort · issue drawer (date and time checks, activity, nudge) · daily load | own | reports | all |
| Pull requests | Team / Person · sprint, month or all · period table · standard checks · review comments · PR list → PR detail · PR standard | own | reports | all |
| Effort | Team / Person · sprint or month · period table · daily log · unplanned and extra work → evidence · confirm / reject | own | + confirm | + confirm |
| Bugs & support | Team / Person · sprint or month · history · open bugs in own code (take, dispute) · fixed for others · pick up · support queue | own | + resolve disputes | all |
| Customer visits | Team / Person · Q3 or year · compared with the team · visit detail · plan, log, edit, delete · report · customer feedback · verify · CSV import | own + feedback as owner | + verify | all |
| Attendance | Team / Person · month, sprint or quarter · people table · day-by-day grid · calendar · leave list · late check-ins · month by month · culture impact · monthly uploads | own | reports | all + upload |
| OKRs | Person / Team · quarter dropdown · create, edit, check-in, delete · CSV import and export | own | + team | all |
| Training plans | My training · Mentoring · Trainees & assignments → plan detail · Plan library | own / mentor | + library, assign, verify | all |
| Road map | Milestones · promotion readiness · skills | own | reports | all |
| Culture values | 11 values · radar · per-value calculation detail · Discipline rules a–h · override · rules for all values | own | reports + override | all |
| Reviews | Performance review · 1:1s & feedback · 360° (To give, My feedback, Team, Cycles) → review form, results | own | + assign 360°, calibration | + cycles |
| Team utilization | Sprint, month or release · heatmap · people table · rebalance | – | ✓ | ✓ |
| Appraisal & increments | Bands · budget · evidence panel · propose · submit · approve · release letters · export | – | propose | approve |
| Users | Directory · add, edit, deactivate, delete · CSV import and export | – | own team | ✓ |
| Data imports | Import cards · exports · data sources map · upload history with undo | – | OKRs, users, visits | ✓ |
| Roles & permissions | Permission matrix · custom roles · what each role sees | – | – | ✓ |
| Integrations | Tiles · connect · settings (credentials, scopes, sync) · credentials table · activity log | – | – | ✓ |
| Rules & thresholds | Every threshold, weight and point value · reset to defaults | – | – | ✓ |

The sign-in screen is the 22nd view and is shown to signed-out users only.

**How the data flows**

&#91;embedded content: data flow · six sources, one calculation service, five pillars\]

Every screen reads from the same calculation service, so a number never differs between screens. The pillars combine into the performance index, which is half of the appraisal score.

**Design system and UI patterns**

Every screen is built from the Pulse design system (design system artifact v30). The screen-detail blocks in sections 6–11 use these names.

| Pattern | Spec |
| --- | --- |
| Page frame | 220 px side menu + fluid main; page padding 24 px; sections 32 px apart; cards 16 px apart |
| Page header (hdr) | Eyebrow (11 px, 600, uppercase, +0.06em), H1, caption (ink-muted). Right side: tabs, period controls, person selector, primary action. Person pages use an identity header: avatar, name, title · team · level · reports to |
| KPI row | 4 or 5 StatTiles: eyebrow label, value in IBM Plex Mono with unit, foot line or a Badge. 2 per row on phones |
| Card | surface-raised, 1 px border, radius-lg, 20 px padding. Head: H2 title + caption subtitle; right slot for a badge, link button or select |
| Grids | g2 (two equal), g21 (2:1), g12 (1:2), g3 (three); single column under 1100 px |
| Table | Uppercase 11 px head on surface-sunken; 14 px rows with a 1 px divider; numbers right-aligned in mono; clickable rows show a pointer and open a detail; wraps in a scroll container on small screens |
| Badge | Pill with a word, always: ok (good, done, verified), brand (info, selected), accent (attention, pending), warn (over), danger (critical, error), neutral |
| Utilization meter | Bar + 100% mark, colored by band (info, ok, accent, warn, danger) |
| Heatmap cell | 28 px cell with value, u0–u5 band classes; special cells: Leave, Visit, Training, — (before joining) |
| Tabs | Segmented control (pl-tabs); the active tab is raised. Reviews uses large tabs with counts |
| Period control | Sprint / Month / Release segmented buttons + a dropdown of periods; the current one is marked "(active)" or "(to date)" |
| Buttons | Primary (brand fill), default (outline), small (sm), danger (red outline / fill for confirm) |
| Forms | Label above field (label.f); required fields marked \*; inline error line in danger-ink under the form; selects with "Select…" placeholders for required choices |
| Modal | Centered, 480 px default, up to 900 px for wizards; max 90 vh, scrolls inside; Cancel left of the primary action at the bottom right; Esc and scrim click close |
| Drawer | Right-side panel for issue detail with a scrim |
| Toast | Bottom center, 2.4 s, confirms every save ("Objective created", "Import undone · file") |
| Alert strip | pl-alert: badge + message + optional action (for example a rebalance suggestion or a missing upload) |
| Empty state | Centered muted text with an optional primary action; never a blank card |
| Colors | Tokens only (surface, ink, brand, ok, info, accent, warn, danger, each with -soft and -ink); light and dark themes |
| Type | IBM Plex Sans for UI, IBM Plex Mono for every number, key and date in tables |
| Icons | Lucide-style 16 px line icons in the menu; no vendor logos (integration tiles use monograms) |

## 4. Shared models

All period data uses one period model, one working-day calendar and one set of entities, so a number is identical on every screen.

**Period model**

| Period | Key | Range | Used on |
| --- | --- | --- | --- |
| Sprint | s:S43 | Monday of the first week to the Sunday before the next sprint. Two weeks by default; the active sprint may be longer (S43 is three weeks, 21 Sep – 9 Oct) | Overview, Work items, Team utilization, Effort, Bugs & support, PRs, Attendance |
| Month | m:2026-09 | Calendar month; the current month shows "(to date)" | Same screens as sprint |
| Release | r:8.2 | Release start to release date; status released or in progress | Overview, Work items, Team utilization |
| Quarter | q:Q3 | Calendar quarter | Attendance, OKRs, culture values, visits (Q3 / year) |
| All | all | April – September 2026 | Pull requests |

- The sprint/month/release choice is shared: choosing Sprint 42 on Effort opens Bugs & support on Sprint 42 too. Attendance keeps its own choice because it defaults to the last uploaded month.
- Every period screen has a "sprint by sprint" or "month by month" table. Clicking a row opens that period.
- A live period uses the live view (open issues, today's load). A closed period uses history snapshots.

**Working-day calendar**

A working day is a weekday that is not a holiday, not a leave day (from the leave calendar or the attendance upload), not a customer-visit day and not before the person's joining date. Every denominator uses this calendar: capacity, daily update compliance, office policy and checks for missing worklogs.

**Core entities**

| Entity | Key fields | Source |
| --- | --- | --- |
| User | id, name, email, role, manager, department, title, status (Active / Invited / Deactivated), last login | Pulse, CSV import |
| Person (performance profile) | id, name, role title, team, level L1–L5, manager, joined date, leave dates, pillars, quarterly trend, 360° culture ratings by rater group | Created for Employees |
| Role | name, built-in flag, description, permission list | Pulse |
| Sprint / Release | id, number, from, end, to / id, from, release date, status | Jira |
| Issue | key, summary, type, status, assignee, estimate, remaining, spent, story points, due, sprint, release, started, last activity, reopened | Jira |
| Worklog / commit day | person, date, hours, linked commit | Jira, GitHub, TFS |
| Pull request | id, repo, title, author, work item, size, reviewers and votes, threads and comments, build, merged date, standard check results | TFS, GitHub |
| Bug | key, severity, status, origin person (via git blame), fixer, proactive flag, disputed flag | Jira + git |
| Support ticket | id, person, hours, CSAT, status | Support desk |
| Extra-mile item | id, person, month, kind, ref, title, requested by, evidence, date, hours, values credited, state (pending / confirmed / rejected) | Effort, visits |
| Customer visit | id, person, customer, city, purpose, from, to, requested by, account owner, reference, report, feedback, notes | Pulse, CSV |
| Attendance record | person, date, status (O / W / D / L / H / A), leave type, applied on, check-in, check-out | Monthly CSV |
| Attendance upload | month, by, at, file, rows | Data imports |
| Holiday | date, name | CSV, Rules |
| Objective / key result | id, owner or team, level, quarter, objective, epic, value / text, start, target, current, unit | Pulse, CSV |
| Training plan / assignment | plan modules, duration, pass mark, assessment type / person, plan, mentor, meeting cadence, period, check-ins, checkpoints, sessions, assessment, mentor and manager final reviews, decision, acknowledgements, sign-off, trainee rating | Pulse |
| 360° cycle / request | id, name, quarter, due, participants, reviewer limits, anonymity threshold / cycle, subject, reviewer, group, status, answers | Pulse |
| 1:1 / action item / kudos | date, notes, agenda / owner, due, done / from, to, value, text | Pulse, Slack |
| Weekly pulse | person, week, mood 1–5, workload 1–5, note | Pulse |
| Appraisal proposal | person, recommended %, proposed %, reason, promotion flag, status (Draft / Submitted / Approved / Released) | Pulse |
| Notification | id, user, kind, text, link, read, date | Pulse |
| Import log | id, type, file, by, at, rows, imported, errors, warnings, mode, undo | Pulse |
| Integration / credential | tool, status, method, masked secrets, saved by, rotation, expiry, last test / activity log | Admin |
| Rules | every threshold, weight and point value in section 12 | Admin |

## 5. Integrations and credentials

Pulse only reads from connected tools. The two exceptions are explicit user actions: a stale-task nudge and kudos sent to Slack or Teams. Data with no live source comes in through CSV (section 11).

**Connectors**

| Tool | Auth methods | Data read | Feeds |
| --- | --- | --- | --- |
| Jira Software / Cloud | API token, OAuth 2.0 (3LO); webhooks | Boards, sprints, issues, changelog, worklogs, comments, estimates, story points, releases | Work items, utilization, staleness, Effort, Delivery and Quality pillars, Discipline a–f, many culture signals |
| Azure DevOps Boards | OAuth, PAT | Iterations, work items, history | Alternative to Jira |
| Microsoft TFS / Azure DevOps Server | PAT (Code, Work items, Build — read), Windows NTLM service account | PRs, reviewers and votes, threads and comments, builds, linked work items | Pull requests, PR standard, Discipline g, Collaboration |
| GitHub / GitLab / Bitbucket | GitHub App, PAT | PRs, reviews, commits matched to issue keys | Staleness last activity, commit days in Effort, PRs, Agility, Collaboration |
| Confluence / Notion | API token, OAuth | Pages authored: ADRs, design docs, RCAs, templates | Decision Making, Problem Solving, Resourcefulness |
| Slack / Microsoft Teams | App install | /kudos messages | Kudos (Collaboration); sends nudges and the weekly digest |
| Google Calendar / Outlook | Service account, OAuth | Full-day out-of-office, meeting load | Capacity |
| HRMS (Keka, Darwinbox, BambooHR, Zoho People) | API key, client credentials | Employees, reporting lines, levels, holidays, approved leave | Org structure, capacity |
| ServiceNow / Freshdesk / Zendesk | API key | Tickets resolved, hours, CSAT | Bugs & support, Problem Solving |
| PagerDuty / Opsgenie | Read-only API key | On-call shifts, incidents | Effort (incident points), Resilience |
| CI/CD (GitHub Actions, Jenkins) | Token | Deployments, failed changes | Team change-failure rate (team OKRs) |

**Sync rules**

- Webhooks for Jira, GitHub, TFS service hooks and Slack (near real time); hourly poll fallback; nightly full reconcile.
- People are matched across tools by work email. Unmatched accounts are listed in Integrations for manual linking.
- Bots and service accounts are excluded from activity (configurable).
- Tile states: Connected · synced N min ago (ok), Token expires in N days (accent), Sync failed · code (danger), Not connected (neutral).
- Retention: raw events 13 months; aggregated scores are kept with the review record.

**Credential lifecycle (Admin)**

1. **Connect.** Choose the auth method, fill the fields (each says where to find the value), then run Test connection. The test checks formats, authenticates and lists granted scopes; a failure shows the HTTP code and what to check.
2. **Save & connect** is enabled only after a passing test. The first sync runs, and the sync log and credential activity log get an entry.
3. **Settings** opens Credentials (method, non-secret values, secrets masked to the last 4 characters, saved by and date, rotation, expiry, last test), Data & scopes, and Sync (today's events, Sync now).
4. **Change credentials** opens a prefilled form. A secret left blank keeps the current one. New values replace the old only after a passing test, and synced history is kept.
5. **Fix** appears on a failed sync or a token near expiry and opens Change credentials with the error at the top.
6. **Disconnect** asks for confirmation, stops syncing and deletes the stored secrets; synced data stays.

- The **credentials table** lists each tool's method, saver, rotation status against policy (Jira and Confluence 90 days, HRMS 180, GitHub, Google and PagerDuty 365), expiry and last test.
- The **credential activity log** records every add, change, disconnect and failed sync, with the time and the person.
- In production, secrets are encrypted at rest with KMS and never returned to the browser after saving.

## 6. Feature specs — manager tools

The Action center is the manager's landing page: one prioritized list from every module, so nobody hunts through screens. Progress gives the same proactive view to every employee about themselves.

### 6.1 Action center (managers, Admin)

| Section | Behaviour | Source |
| --- | --- | --- |
| KPIs | High-priority items, people to watch, current-sprint forecast (% of remaining work that fits), team pulse, next-sprint capacity in SP | All modules |
| Do today | One list sorted High → Medium → Low, each with a source badge, the person and one action button that opens the right screen, tab and person | See triggers below |
| People signals | Per person: risk High / Watch / Low / OK, each signal with its reason and a suggested next step; click opens Progress | Section 12 thresholds |
| Sprint forecast | Per person: remaining hours vs focus hours left. Open issues fill in due-date order, and what does not fit is listed as likely to carry over | Jira |
| Next-sprint capacity | Working days − leave − planned visits − training and mentoring hours = focus days, × velocity (done SP per day, last 3 sprints) = suggested SP per person and team | HRMS, attendance, visits, training, Jira |
| Skills coverage | Areas × people from PRs and Jira components (6 months): Single point of failure / Thin / Covered, with a pairing suggestion | TFS, GitHub, Jira |
| Team pulse | 6-week mood and workload trend | Weekly pulse |
| Team wins | Recent kudos, 5-star visits, completed training, proactive fixes | Several modules |
| Weekly digest | Preview of the Monday email / Slack summary | All |

**Do today triggers.** Priority is shown in brackets.

- **Workload and work items:** over the overload threshold (High), 2+ stale issues (Medium), days with commits but no Jira worklog (Low).
- **People:** 1:1 older than 10 working days (Medium), weekly pulse with mood ≤ 2 or workload 5 (High).
- **Effort and visits:** extra-mile items waiting for confirmation (Medium), visits to verify (Medium), overdue visit reports (Low).
- **Training:** final review due (High), other training actions (Medium), mentor blockers raised in the last 5 days (High), mentors behind on sessions (Low).
- **Reviews and quality:** open 360° reviews (Medium when 10 or fewer working days remain), high-severity bugs (Medium), disputed bug attribution (Medium), rubber-stamp PR approvals this sprint (Low).
- **Appraisal:** proposals still in draft (Low), proposals waiting for approval (HR, Medium).
- **Attendance:** a past month not uploaded (HR, Medium) or the upload due near month end (HR, Low). A report with an absence (Medium), or with 2+ days of short-notice leave, office policy under 70% or 3+ late check-ins over the allowance in the last uploaded month (Low).
- **Integrations:** a failing integration (Admin, High) or a token near expiry (Admin, Low).

**People signals (conversation prompts, never a rating).** Sustained overload (over 100% in 2 of the last 4 sprints) · under-used (below 60% in 2 of 3 sprints) · weekend work while overloaded · unplanned work ≥ 30% · Discipline below 3 · 1:1 overdue · low or falling pulse · falling performance index · no recognition this quarter · training behind (≥ 2 overdue modules). New joiners on onboarding are never flagged for utilization.

**Screen detail — Action center**

- **Header:** eyebrow "Manager view · \<date>", title "Action center", caption "Everything across Pulse that needs you, early warnings about people, and how the sprint and the next one look". Action: **Weekly digest** (opens a preview modal with Close).
- **KPI row (5):** Needs you (n high priority) · People to watch (names) · Sprint \<n> forecast (n issues likely to carry over) · Team pulse (n low this week) · Next sprint capacity (Sprint 44 · start date).
- **Do today** card: an "n items" badge; a list where each row shows a priority badge, a source badge, the person and the text, plus one button (Rebalance, Open, Plan 1:1, Review, Verify, Remind, Upload, Fix). Subtitle names the source modules. Empty state: "Nothing needs you right now."
- **People signals** table: Person (avatar + name) · Risk badge · Signals (badge + reason per line) · Suggested next step. Click a row → Progress for that person.
- **Sprint forecast** table: Person · Remaining · Capacity left · Fits · Likely to carry over. The header link is "Team utilization →".
- **Next sprint capacity** table: Person · Days · Leave · Visits · Training h · Mentoring h · Focus days · Velocity · Suggest SP, plus a team total row.
- **Skills coverage** table: Area · one column per person (initials) · Coverage badge · Suggestion.
- **Team pulse** (6-week mood and workload bars) and **Team wins** (list with an achievement dot and a value badge) side by side.

### 6.2 Progress (everyone; managers for their reports)

- **Next steps:** collected from every screen, each with an Open button. Includes stale issues, code-only days, PR titles to fix, reviews to give, training feedback to acknowledge, sign-off or check-ins due, visit reports, the weekly pulse and OKRs off track.
- **Achievements:** earned from data and tied to a culture value. They are Daily updates, PR standard, Great reviewer, Commitment keeper, Proactive fixer, Delighted customer, Training completed, Mentor and Key result achieved.
- **Trends:** performance index over 4 quarters, utilization by sprint with the overall average, culture values Q2 → Q3 sorted by biggest gain, OKRs this quarter, PR standard by month and training ratings.
- **Weekly pulse:** two questions every Friday (mood 1–5, workload 1–5, optional note). Only the person and their manager see answers. A low answer notifies the manager and adds a Do today item.
- **Team wins:** visible to the whole team.

**Screen detail — Progress**

- **Header:** identity header for the selected person. Managers get **Start 1:1**. The employee sees **Weekly pulse** when this week's pulse is open.
- **KPI row (5):** Performance index (with change vs last quarter) · Achievements (this quarter) · Next steps (count) · Training rating (plans) · Weekly pulse (mood or workload and date).
- **Row 1 (g2):** "Next steps for \<name>" list with an Open button per item (empty: "Nothing waiting — nice work.") · "Achievements" list with an achievement dot, title, detail and value badge.
- **Row 2 (g2):** "Performance index trend" line chart across 4 quarters with values printed · "Utilization by sprint" bars with a right badge such as "Overall 82% · Healthy".
- **Row 3 (g2):** "Culture values · Q2 → Q3" (biggest gains first, link "Details →") · "Goals, PRs and training" with three blocks: OKRs · Q3 (bar per objective), PR standard by month, Training.
- **Row 4:** "Weekly pulse" (private to the person, the manager and HR) and "Team wins".
- **Weekly pulse modal:** title "Weekly pulse · week of \<date>". "How was your week?" and "How heavy was your workload?" are each five 1–5 buttons. Optional text area "Anything blocking you or worth celebrating?". Cancel / Send. Both answers are required ("Answer both questions.").

### 6.3 Team utilization (managers, Admin)

- **Period:** sprint, month or release. A live sprint shows the live view; a closed period shows history.
- **KPIs:** team utilization, overloaded and under-used counts, stale share and days at risk.
- **Heatmap:** person × working day, with cells colored by utilization band. Leave, customer visits (Visit), pre-joining days (—) and onboarding (Training) have their own labels. Clicking a row opens that person.
- **People table:** committed vs done, utilization and band, stale issues, and a suggested action.
- **Rebalance:** suggests moving a named issue (with its SP) from an over-capacity person to an under-used person with matching skills. Trainees on onboarding are never suggested. A suggestion is only a suggestion, and changes are made in Jira.

**Screen detail — Team utilization**

- **Header:** eyebrow "Manager view", title "Platform team · \<period>", caption with the dates and people count ("8 people (2 onboarding)"). Controls: Sprint / Month / Release buttons and the period dropdown.
- **Live sprint:**
  - An alert strip with the rebalance suggestion, for example: "Move PLAT-2198 (24 h) from Priya Shah (171%) to Dev Patel (52%). New levels ≈ 104% and 96%." with the action **Reassign in Jira**.
  - KPI row (4): Team utilization (band) · Overloaded (names with %) · Under-utilized (names with %) · Stale issues (% of open work).
  - "Daily utilization" heatmap: before today = logged ÷ focus hours; today onward = remaining ÷ capacity. Click a person to open them.
  - "People" table sorted by utilization: Person · Open · Stale · Work · Capacity · Utilization (meter + %) · Daily updates · Discipline · Index.
- **Month, closed sprint or release:**
  - KPI row (5): Team utilization · Story points done (% of commitment) · Carried over · Over 100% · Under 60%.
  - "People · \<period>" table: Person · Issues done · SP done / committed · Commitment · Carried over · Hours logged · Utilization.
  - "Team utilization by sprint" chart: click a sprint to open it.
  - Release only: a release card (for example "NMS 8.3 · release 8.3" with a "Due 13 Nov" badge) listing its sprints and their share.

## 7. Feature specs — work data

These five screens turn Jira, Git, TFS and support data into workload, quality and extra-effort signals. Every one supports sprint and month periods; Overview and Work items also support release, and Pull requests also supports All.

### 7.1 Overview

- **Header:** identity (name, title, team, level, manager) and a period selector (sprint, month or release).
- **Live sprint KPIs:** tasks assigned, stale issues, days of work assigned vs capacity, and utilization with its band. An overload alert appears when utilization is over 100%, with the days over.
- **Needs attention:** stale, not started, overdue and no-estimate issues. Clicking one opens the issue drawer.
- **Utilization by sprint:** a bar per finished sprint plus the overall average.
- **Cards:** bugs and support summary, customer visits, training (stage, next action), culture radar against the team average, and the road map.
- **Closed periods** show history metrics: done vs committed, SP and commitment %, carry-over, median cycle time, estimate accuracy, hours logged and utilization (hours logged ÷ working days × 8 h).
- **New joiners** on an onboarding plan show an Onboarding badge and are not judged on utilization.

**Screen detail — Overview**

- **Header:** identity header with Sprint / Month / Release buttons, the period dropdown and **Start 1:1** (managers). The person selector sits in the top bar.
- **Live sprint (Sprint 43 active):**
  - Alert strip when overloaded, for example "Critical · Overloaded: 10.3 days of estimated work against 6 days of capacity left (leave on 30 Sept) (customer visit at …)".
  - KPI row (5): Tasks assigned (SP · done · open) · No update > 2 days (oldest n d) · Work assigned (h ÷ 6 h/day) · Utilization (band vs capacity) · Performance index (change vs last quarter).
  - "Needs attention" table (Jira · sprint · dates): Key · Summary · Status · SP · Remaining · Due · Last activity · Signals, sorted by signal. Header link "All work items →". Click a row → issue drawer.
  - Cards: Utilization by sprint (badge "Overall 82% · Healthy") · Bugs & support (Open bugs in my code, Others' bugs fixed proactively, Extra support tickets; "Details →") · Customer visits (Q3 vs team: visits · days on site, CSAT, visit points) · Training (trainees only: stage, progress, next action) · Culture values (radar against the team average; "Details →") · Road map ("Open →") · "Where the numbers come from" (integration tiles with sync time).
- **Month or closed sprint:**
  - KPI row (5): Issues (carried over) · Story points (% of commitment) · Hours logged (d · working days) · Utilization (band) · Performance index.
  - Cards: Utilization by sprint · "Delivery · \<period>" (Median cycle time, Estimate accuracy, Bugs worked, Issues that went stale; "All work items →") · "Sprints in \<period>" table (Sprint · Issues · Committed · Done · Commitment · Carried · Hours · Utilization; click a sprint to open it) · Bugs & support.
- **Release:** adds a release card "NMS 8.3 · release 8.3" (fix version, dates, badge "Due 13 Nov · 34 working days left") with Sprints, \<person> SP done, Team SP done and Share of release.
- **New joiner on onboarding:** an Onboarding badge in the header, and utilization shown as Training.

### 7.2 Work items (renamed from Sprint work)

- **Live view:** filters (all, stale, not started, overdue, in review, done), search by key or summary, and sortable columns (key, summary, status, estimate, remaining, idle days, due).
- **Issue drawer:** status timeline, the date and time checks (logged vs elapsed, estimate drift, longer than estimated), last meaningful activity by the assignee and a **Nudge** action (Slack or Teams message to the assignee).
- **Daily load:** remaining hours spread across planned days, against focus hours.
- **History** (past sprint, month or release): a sprint summary, the release card (sprints in the release, share of the release) and the full issue list with filters (Done, Carried over, Bugs, Open now). Clicking an issue opens its timeline.

**Screen detail — Work items**

- **Header:** identity header, period controls and **Open in Jira**.
- **Live sprint:**
  - KPI row (4): Committed (issues at sprint start) · Done (% of commitment) · Remaining (= days at 6 h/day) · Sprint elapsed (working days left).
  - Card "Sprint 43 · PLAT board · release 8.3" (synced from Jira n min ago): filter chips with counts (All, Open, Stale / not started, Overdue, Done), **Nudge on Slack**, search, and a sortable table: Key · Summary · Status · SP · Remaining · Due · Last activity · Signals.
  - "Daily load": logged vs planned per working day.
- **Closed sprint, month or release:**
  - KPI row (5): Committed · Done (% of commitment) · Carried over (SP to the next sprint) · Median cycle time (issues done) · Utilization (band · h logged).
  - Cards: release card (release only) · "Sprints in \<period>" · "Issues · \<period>" from Jira sprint history, with filter chips (All, Done, Carried over, Bugs, Open now) and the table Key · Summary · Type · Sprint · Release · Status · SP · Estimate → Logged · Cycle · Resolved. Click → issue timeline modal.
- **Issue drawer** (right side, scrim):
  - Key, status badge, type · SP, summary, assignee and due date, and signal badges (Stale, Low logged time…).
  - Original / Logged / Remaining tiles.
  - "Date & time verification": one row per check with a status badge and an explanation. Checks: idle working days vs threshold; logged vs elapsed at 75% of focus; estimate drift; days of work left.
  - "Activity (Jira + GitHub)": a timeline in which bot and planning events are greyed and marked "not counted as activity".
  - Actions: **Nudge on Slack**, **Open in Jira**.

### 7.3 Pull requests

- **Source:** Microsoft TFS / Azure DevOps Server and GitHub. Tabs are Person and Team; the period is sprint, month or All (grouped by month).
- **KPIs:** PRs, merged, PR standard %, titles to standard, median size, reviews given, comments to standard, rubber-stamps.
- **PR standard** (Admin configures each check):
  1. Title matches `WORKITEM type(scope): summary` (feat, fix, refactor, perf, test, docs, chore; 10–72 characters, no full stop).
  2. A linked work item.
  3. A description template with Summary, How it was tested and Risk / rollback.
  4. Size of 400 changed lines or fewer.
  5. 2 approvals.
  6. Build passed.
  7. Blocking threads resolved before merge.
- **Scoring:** a check that cannot be judged yet shows Pending and is left out. PR standard % = passed ÷ judged checks, averaged across PRs (target 85%). First-review time against 24 h is shown but not scored.
- **Review comment standard:** conventional-comment labels (praise, suggestion, issue, question, nitpick, todo, thought, optional blocking / non-blocking) plus a reason. Issue, suggestion and todo need 20+ characters. Comments are classed as Standard, No label, Needs a reason, or Too short. Non-standard titles and comments show a suggested rewrite.
- **Rubber-stamp:** an approval of a PR over 200 lines with no comment.
- **PR detail:** each check, reviewers and votes, threads and comments with their class.
- **Feeds:** Discipline g, Collaboration, appraisal evidence and achievements.

**Screen detail — Pull requests**

- **Header:**
  - Team view: eyebrow "Manager view", title "Pull requests · Platform team", caption "From Microsoft TFS (Azure DevOps Server) and GitHub · checked against the PR standard and the review comment standard · \<period>".
  - Person view: the identity header.
  - Controls: Team / Person tabs, Sprint / Month / All buttons, the period dropdown (hidden for All) and **PR standard**.
- **Team tab:**
  - KPI row (5): PRs raised (n merged) · PR standard (target 85%) · Title standard (n of m titles) · Review comments (n to standard) · Rubber-stamp approvals (big PRs, no comments).
  - "People · \<period>" table: Person · PRs · PR standard · Titles OK · Median size · 1st review · Reviews · Comments OK · Rubber-stamp. Click → Person tab. Subtitle: "signals for 1:1s, not a ranking".
  - "Sprint by sprint" or "Month by month" table: Period · PRs · Merged · PR standard · Titles OK · Median size · Reviews · Comments OK · Rubber-stamp.
  - "Checks across the team": a bar per check showing which parts of the standard are missed most.
  - "Review comments across the team": a stacked bar of Standard / No label / Needs a reason / Too short.
  - "Merged without meeting the standard" table (lowest first): PR · Title · Created · Lines · Approvals · Status · Standard checks.
- **Person tab:**
  - KPI row (5): PRs raised (merged · in review) · PR standard · Title standard (n to fix) · Median size (n over 400) · Review comments (n on m PRs, or rubber-stamps).
  - "Standard checks · \<period>": the share of the person's PRs passing each check; in-review checks are left out.
  - "Review comments \<name> wrote": the class mix, with "Rewrite to the standard" examples and a **Comment standard** link.
  - The period table.
  - "Pull requests · \<period>" table: PR · Title (failed titles show "Try: …" rewrites) · Created · Lines · Approvals · Status · Standard checks (a dot per check). With All, it is grouped by month.
  - "Reviews given · \<period>": PRs reviewed and comment quality (counts toward Collaboration).
- **PR standard modal:** "Checked automatically on every pull request from TFS and GitHub · set by your admin".
  - Part 1 · Title: rule, Good and Not to standard examples, and the regex.
  - Part 2 · The pull request: one row per check.
  - Part 3 · Review comments: labels with meanings and the rubber-stamp rule.
  - Admin also sees **Edit in Rules & thresholds**.
- **PR detail modal:**
  - Source badge (TFS / GitHub), PR number, repo, status, title, then author · created · merged · lines in files · first review after n h · work item.
  - "PR standard · n%" with Pass / Fail / Pending per check and the reason for each failure.
  - "Reviewers and comments": each reviewer with their vote, every comment with its class badge, its status and a "Try: …" rewrite, and rubber-stamp flagged.
  - A link to the PR in TFS or GitHub, and Close.

### 7.4 Effort and extra-mile points

- **Hours and days:** hours logged (Jira worklogs), days worked (÷ 8 h), working days, active days (worklog or Git activity), code-only days (Git activity without a worklog, which triggers a "Log time" prompt), long days and weekend work.
- **Daily log calendar:** every day shows its hours and markers for code-only, long day, weekend, leave, holiday and customer visit.
- **Unplanned and extra work** is detected automatically and shown as Awaiting manager. Points and the values credited:

| Type | Detection | Points | Values credited |
| --- | --- | --- | --- |
| Added mid-sprint | Sprint field set after the sprint started | 1 | Adaptability, Agility in Execution |
| Incident / hotfix | PagerDuty assignment or Incident type + linked fix | 2 | Resilience, Outcome Ownership |
| Took over | Assignee changed to this person mid-sprint | 1 | Collaboration, Outcome Ownership |
| Helped on another's issue | Commits or substantive reviews on someone else's issue | 1 | Collaboration |
| Untracked useful work | Merged PR with no work-item key | 1 | Resourcefulness |
| Reviews for others | Reviews on others' PRs | 0.5 per 5 | Collaboration |
| Extra support ticket | Resolved ticket beyond sprint work | 1 | Outcome Ownership, Collaboration |
| Proactive fix | Self-assigned bug in someone else's code, resolved | 1.5 | Problem Solving, Collaboration |
| Customer visit | See 8.1 | 1.5 + extras | By purpose |

- **Who gave it:** the changelog author, the PagerDuty escalation or "Self-initiated". The team view ranks requesters by unplanned hours.
- **Confirmation:** the manager confirms or rejects each done item from its evidence, and only confirmed items score. Each point adds +0.05, split across the credited values. Caps: +0.3 per value per quarter, and 6 points per person per month (visits have their own cap of 8).
- **Guardrails:** an unplanned share over 30% is a planning signal for the manager, not a performance issue for the employee. Points reward the work, not the hours.

**Screen detail — Effort**

- **Header:** team view "Effort · Platform team" ("Jira worklogs + GitHub activity · \<period>") or the identity header. Team / Person tabs, Sprint / Month buttons and the period dropdown.
- **Team tab:**
  - KPI row (4): Team hours logged (person-days) · Unplanned share (h unplanned or untracked) · Extra work items (n done) · Awaiting your review (confirm points).
  - "People · \<period>" table: Person · Hours · Days worked · Active / working days · Code-only days · Unplanned share · Extra items · Points. Click → that person's daily log.
  - "Sprint by sprint" or "Month by month" team totals: Period · Hours · Days worked · Work days · Unplanned · Weekend · Visits · Extra items · Points.
  - "Who gives unplanned work": requester → hours · items, as bars.
  - "Review extra-mile points": one row per item (kind badge, ref, title, person, points) with **✕ Reject** and **✓ Confirm**, and **Confirm all done items** in the header.
- **Person tab:**
  - Alert strip when the unplanned share is over 30%: "Planning · n% of logged time was unplanned. This is a planning signal for the manager, not a performance problem. Top source: …".
  - KPI row (5): Hours logged · Days worked (hours ÷ 8 h vs working days) · Active days · Unplanned share (h of h) · Extra-mile points (confirmed / pending, cap).
  - "Where the time went": a stacked bar with a legend: planned sprint work, unplanned (mid-sprint, incidents, takeovers), untracked (GitHub, no Jira key), reviews for others, and customer visits (on site + travel).
  - "Daily log · \<period>" calendar: each day shows hours and markers (code-only, long day, weekend, leave, holiday, visit). The subtitle says it is verified against worklog timestamps, commits, leave and holidays.
  - "Unplanned & extra work": kind badge, ref, title, given by, hours, points and state. Managers get Reject / Confirm. Click → the evidence modal.
  - The period table.
  - "Culture value updates from extra work" table: Value · 360 score · Extra-mile bonus · Shown score, with the link "Open culture values →".
- **Evidence modal:** kind badge, ref and title. Tiles for Hours, Points, Status and Given by (name · role · date). "How it was detected" (for example "Jira changelog: Sprint field set to Sprint 43 on 22 Sep 11:04 (sprint started 21 Sep)"). Culture values credited, and Close.

### 7.5 Bugs & support

- **Attribution:** a bug is traced to the code owner through a Jira "caused by" link, or through git blame on the fixing PR's diff. Its environment is Staging or Production.
- **Person view:**
  - Open bugs in own code (with the High count, age, and who is fixing each) and bugs from own code this quarter, including how many reached production.
  - **Dispute** an attribution with a reason; the manager keeps it or removes it.
  - **Take it** to own the fix when someone else is fixing a bug in your code.
- **Fixed for others:** bugs resolved in someone else's code. Proactive means self-assigned and earns 1.5 points after confirmation. "Bugs you could pick up" offers Pick up and Mark resolved.
- **Support tickets:** taken beyond sprint work, either self-picked or assigned, with hours and CSAT. Each earns 1 point after confirmation. The support queue lets anyone take an unassigned ticket.
- **Team tab and history:** the same numbers per person, plus a period table of bugs created, high severity, reached production, fixed for others, tickets and hours.
- Attribution is a learning signal, never blame and never an automatic score.

**Screen detail — Bugs & support**

- **Header:** team view "Bugs & support · Platform team" ("Bugs traced to each person's code, proactive fixes and extra support tickets") or the identity header. Team / Person tabs (employees see Person only), Sprint / Month buttons and the period dropdown.
- **Team tab:**
  - KPI row (4): Open bugs (team code; n unassigned) · Proactive fixes (Q3) · Extra support tickets (h this period) · Support queue (unassigned tickets).
  - "People" table: Person · Open bugs own code · High · Bugs from code Q3 · Reached production · Others' bugs fixed · Proactive · Support tickets · Support hours. Click → Person tab.
  - Disputes waiting for a decision show **Keep** attribution / Remove from their **count**.
- **Person tab:**
  - KPI row (5): Open bugs in my code (n high severity) · Bugs from code Q3 (n reached production) · Others' bugs fixed (n proactive) · Extra support tickets (h) · Support CSAT (across n rated tickets).
  - "Open bugs in my code": a row per bug with key, severity, environment, age and who is fixing it. Actions: **Mark resolved**, **Dispute**, **Take it**. Subtitle: "a learning signal, not blame".
  - "Bugs fixed for others": proactive badge and points.
  - "Open bugs you could pick up" with **Pick up**.
  - The "Sprint by sprint" or "Month by month" table: Period · Bugs in own code · High · In production · Fixed for others · Proactive · Tickets · Support h.
  - "Support tickets taken · \<period>": ticket, customer, how taken, hours, CSAT, with **Resolve**.
  - "Support queue" with **Take ticket**.
  - Managers also see "Culture credit".
- **Modals:**
  - Dispute attribution: "Why is this not from your code?" (required), Cancel / Send dispute.
  - Resolve \<ticket>: Hours spent and Resolution note, Cancel / Resolve ticket.

## 8. Feature specs — customer visits and attendance

Both modules record time away from the desk. Customer visits credit extra customer-facing effort. Attendance turns the HR system's monthly record into capacity and two culture signals, and sick or approved leave never counts against anyone.

### 8.1 Customer visits

**Lifecycle:** Planned → Report due → Feedback pending → Awaiting manager → Verified (or Not counted).

1. **Plan or log.** The manager plans a visit, or the engineer logs one, with these fields:
   - person, customer, city
   - purpose: deployment / installation, troubleshooting, escalation, training / workshop, demo / POC or health check
   - from and to dates (at most 14 days)
   - requested by, account owner and a reference (deal or ticket)

   Validation rejects overlap with another visit and overlap with leave. Visit days leave sprint capacity and show as "Visit" or "On duty" everywhere.
2. **Visit report.** After the visit the engineer submits what was done, hours on site, travel hours, the outcome, follow-ups and the customer contact. Until then the visit shows Report due, and the manager can send a reminder.
3. **Customer feedback.** The account owner gets an in-app notification and records CSAT 1–5, whether the customer would ask for this engineer again, a quote, the source and the date.
4. **Verify.** The manager verifies the points, on this screen or in Effort.

**Points (editable):** visit 1.5 · each extra day on site 0.5 · each weekend or holiday day 1 · escalation 0.5 · CSAT ≥ 4.5 adds 1 · monthly visit cap 8 (separate from the extra-mile cap).

**Values credited by purpose:**

- Deployment → Outcome Ownership, Adaptability
- Troubleshooting → Problem Solving, Outcome Ownership
- Escalation → Resilience, Problem Solving, Outcome Ownership
- Workshop → Collaboration, Adaptability
- Demo / POC → Collaboration, Risk Appetite
- Health check → Outcome Ownership, Collaboration
- Weekend days add Resilience.

Visit CSAT also feeds the Outcome Ownership data signal.

**Screens**

- **Team tab:** KPIs for visits completed, days on site, CSAT, needs action and upcoming. A table lists everyone, including people with no visits, compared with the team average (new joiners excluded). Needs action and upcoming lists sit below.
- **Person tab:** visits, days, weekend days, travel, points and CSAT, each as a bar against the team average with a badge such as "2.0× team avg".
- **Account-owner view:** visits waiting for their feedback.
- **Visit detail:** dates, report, feedback, the points breakdown, values credited and the status. Actions depend on role and state: edit, delete, report, feedback, verify, reject, remind.
- **Period:** Q3 or year to date. **Import:** a CSV of planned visits from the CRM (section 11.5).

**Screen detail — Customer visits**

- **Header:**
  - Managers: eyebrow "Manager view", title "Customer visits · Platform team", caption "Who goes on site, what customers experienced, and the extra effort compared with the team · \<period>".
  - Controls: Team / Person tabs; Q3 2026 / 2026 to date / Month / sprint chips; **Import CSV**; **Plan a visit** (managers) or **Log a visit** (employees).
- **Team tab:**
  - KPI row (5): Visits completed (by n of m people) · Days on customer site (n weekend / holiday) · Customer CSAT (n responses) · Needs action (verify, feedback or report) · Upcoming (days out of capacity).
  - "People · \<period>" table: Person · Visits · Days on site vs team avg · Weekend / holiday · Travel · CSAT · Points · Compared with team ("2.0× team avg" badge). It includes people with no visits; the subtitle gives the team average.
  - "Needs action" (full width): visit rows with status badge and points. Actions by state: ✓ Verify, ✕ Reject, Record feedback, Edit, Delete, Remind.
  - "Upcoming visits" with Edit / Delete; the subtitle notes visit days leave sprint capacity automatically.
  - "Completed visits · \<period>" (click a row for the detail).
  - "What customers said": quotes with the CSAT and who recorded them.
- **Person tab:**
  - Capacity alert when a visit falls in the active sprint.
  - KPI row (5): Customer visits (n planned) · Days on site · Travel (h, with h on site) · Customer CSAT (responses · would ask again) · Visit points (vs team avg).
  - Cards: "Compared with the team" (bars with an average mark) · "What customers said" · "Visits" · "Culture values credited by customer visits" ("Open culture values →").
- **Account-owner view** (for example sana@): title "Customer experience" (eyebrow "Customer visits · your accounts").
  - KPI row (4): To record · Recorded · Average CSAT · Upcoming.
  - Cards: "Feedback to record" and "Visits to your accounts", each with Record feedback.
- **Plan / Log / Edit visit modal:** Engineer \* · Purpose \* · Customer \* · City / site \* · From \* · To \* · Requested by \* · Account owner (records customer feedback) \* · Linked ticket or deal · Notes. Cancel / Save visit (or Save changes). Errors: "Fill in every field marked \*.", "To must be on or after From.", "A visit can be at most 14 days.", overlap with another visit, on leave.
- **Visit report modal:** "Visit report · \<customer>" with dates, days and purpose.
  - Fields: What did you do on site? \* · Hours on site \* · Travel hours \* · Outcome \* (Resolved, Partially resolved, Follow-up needed, Deal progressed) · Follow-ups · Customer contact.
  - A note says the account owner will be asked for feedback and the manager verifies the points. Cancel / Submit report.
- **Customer feedback modal:** "Customer feedback · \<customer>", showing the engineer, purpose, dates, contact and report summary.
  - Fields: "How satisfied was the customer?" \* (1–5 buttons, 1 very unhappy · 5 delighted) · "Would they ask for \<name> again?" (Yes / No) · Source (Customer call, Email, CSAT survey, Meeting) · "What the customer said" \*.
  - Cancel / Save feedback.
- **Visit detail modal:** "\<customer> · \<city>".
  - Tiles: Days on site, On site + travel, Customer CSAT.
  - Sections: Visit report, Customer experience, Extra-mile points (line items), Culture values credited.
  - Actions by role and state: ✓ Verify / ✕ Reject, Edit / Delete, Close.

### 8.2 Attendance and leave

**Record.** HR uploads one file a month from the HR system or biometric export: one row per person per working day.

| Status | Code | Counts as |
| --- | --- | --- |
| Office | O | Office day; check-in used for punctuality |
| WFH | W | WFH day; log-in used for punctuality |
| On duty | D | Office day for the hybrid policy. Planned customer visits show as D automatically |
| Leave | L / S | Casual, Sick, Earned, Comp off, Emergency. Sick and emergency are always excused (shown as S) |
| Half day | ½ | 0.5 leave day |
| Absent | A | No leave applied: unplanned, absent without leave |

**Rules (editable):**

- Leave applied 2+ days ahead (7+ for earned) is planned; anything later is short notice.
- Hybrid policy is 3 office days a week, reduced in weeks with leave or holidays. Customer on-site days count as office days.
- Late means checking in after 10:30, with 3 late days a month allowed (pro-rated for other periods).

**Attendance score** = the average of the parts that have data:

- leave with notice %
- office policy %
- punctuality % (after the allowance)

Good ≥ 85% · Watch 70–84% · Talk < 70%.

**Effects:**

- Leave and absent days come out of capacity, daily-update compliance and every other working-day denominator.
- Discipline rule h uses the Q3 attendance score: below 85% → −0.25, below 70% → −0.5, and each absence without leave → −0.25 more. The total is limited by the Discipline floor.
- Collaboration gets an "Office and on-site presence" data signal: 15% weight, bands 95 / 85 / 70 / 55%.
- HR can switch attendance off for culture values. Moral & Ethics never uses attendance.

**Screens:**

- **Period:** a dropdown of months (each flagged "not uploaded" until uploaded), sprints or quarters. It defaults to the last uploaded month. A banner shows when a month in the period has not been uploaded yet.
- **Team tab:**
  - KPIs: office policy, leave taken (planned / sick), unplanned leave (with absences), late check-ins, average day length.
  - People table: office · WFH · duty, leave (unplanned), late, office policy, notice and status, plus the number of days with no row.
  - Day-by-day grid: person × working day with codes, a late dot and a tooltip.
  - Monthly uploads table: status, file, uploader, rows, Upload / Replace.
- **Person tab:**
  - KPIs and a Mon–Sun calendar with codes and tooltips.
  - "How the score is built" with each part's numerator and denominator.
  - Leave list (date, type, applied, notice, counted as) and late check-ins.
  - Month-by-month table: click a month to open it.
  - "How attendance feeds culture values" (Discipline h effect, Collaboration band score, links to both).
- **Privacy:** employees see only their own record; check-in times are visible to the person and their manager chain.

**Screen detail — Attendance**

- **Header:** eyebrow "Attendance & leave", title "Team attendance", "\<name> · attendance" or "\<name> · my attendance". Caption: "Monthly record from the HR system · office, WFH, on duty and leave · feeds capacity, Discipline (rule h) and Collaboration".
- **Controls:** Team / Person tabs (managers, Admin); a period dropdown with Month, Sprint and Quarter groups (months not yet uploaded are labelled "not uploaded"); **Upload monthly CSV** (attendance.upload only).
- **Banner (accent)** for a month in the period that is not uploaded, with **Upload CSV** for uploaders.
- **Team tab:**
  - KPI row (5): Office policy (3 days a week · on-site visits count) · Leave taken (planned · sick / emergency) · Unplanned leave (absent-without-leave badge) · Late check-ins (after 10:30 · 3 a month allowed) · Avg day (check-in to check-out).
  - "People · \<period>" table: Person (with "n days with no row") · Office · WFH · duty · Leave (n unpl.) · Late · Office policy · Notice · Status (Good / Watch / Talk, or Not joined / No data). Click → Person tab.
  - "Day by day" grid (not for quarters): first names × working days, with day numbers on top.
    - Cell codes: O office, W WFH, D on duty, L planned, red L unplanned, A absent, S sick, ½ half day, ? no record.
    - A red dot marks a late check-in, and a tooltip gives the times and leave details.
    - A legend sits under the grid.
  - "Monthly uploads" table: Month · Status (Uploaded / Due after month end / Missing / Not started) · File · Uploaded (by · when) · Rows · Upload / Replace.
- **Person tab:**
  - KPI row (5): Office days (n on duty) · WFH days (% of working days) · Leave (planned · unplanned · sick) · Late check-ins (over the allowance badge) · Attendance score (Good / Watch / Talk).
  - Row g21:
    - "Calendar · \<period>": Mon–Sun grid with the day number and code in each cell, days outside the period faded, and the legend.
    - "How the score is built": Leave with notice, Office policy and Punctuality, each with numerator, denominator and %, plus the formula note.
  - Row g2: "Leave · \<period>" table (Date · Type · Applied · Notice · Counted as: Planned / Short notice / Excused / Absent) · "Late check-ins" (date, weekday, time, allowance note).
  - "Month by month" table: Month · Office · WFH · Duty · Leave · Unpl. · Late · Policy · Score. Click to open a month; un-uploaded months read "Not uploaded".
  - "How attendance feeds culture values" (Q3 badge) with two cards and the fairness note:
    - Discipline · rule h: the score, the explanation, "Effect now: −0.xx" and **Open Discipline**.
    - Collaboration · data signal: office presence %, band score and **Open Collaboration**.
- **Phone width:** grid cells shrink to colored blocks without letters; tables scroll inside their card.

## 9. Feature specs — goals and growth

OKRs set the quarter's goals, the road map sets the career path, and training plans with mentors build the skills between them. All three feed the Goals / OKRs and Growth pillars of the performance index.

### 9.1 OKRs

- **Objective:** one sentence, level Individual or Team, a **required quarter** (Q1 2026 – Q1 2027), an optional Jira epic and a culture value it grows.
- **Key results:** 1–5 per objective, each with start, target, current and a unit. Targets may go down (latency 900 → 400 ms).
- **Progress:** KR progress = (current − start) ÷ (target − start), clamped to 0–1. Objective progress = the average of its KRs. On track ≥ 70%, At risk 40–69%, Off track < 40%.
- **Pillar:** OKR pillar = 1 + progress ÷ 0.7 × 3, capped at 5. It uses the current quarter's individual OKRs and falls back to the manager's rating when there are none.
- **Screen:** Person / Team tabs, a quarter dropdown with a count per quarter, KPIs (objectives, average progress, KRs achieved, pillar), objective cards with a bar per KR, plus New, Edit, Delete (with confirmation) and **Check-in** per KR.
- **Validation:** objective text and quarter required, every KR described, target ≠ start.
- **Who can edit:** own (okr.own), or anyone visible and the team (okr.team).
- **Import / Export CSV:** one row per key result. Matching objectives are updated, so a new current value works as a bulk check-in (section 11.5).
- **Signals:** an OKR under 40% is a next step on Progress, and a KR achieved is an achievement.

**Screen detail — OKRs**

- **Header:** eyebrow "Objectives & key results", title "\<name> · OKRs", "My OKRs" or "Platform team OKRs". Caption: "Progress = average of key-result progress · 0.7 = on track · feeds the Goals / OKRs pillar". Controls: Person (My OKRs) / Team tabs, a Quarter dropdown with counts ("Q3 2026 (2)"), **Import CSV**, **Export CSV** and **New objective**.
- **KPI row (4):** Objectives (quarter) · Avg progress (On track / At risk / Off track) · Key results (n achieved) · OKR pillar ("Q3 · 0.7 progress ≈ 4.0").
- **Objective card** (one per objective):
  - Badges: level, status, culture value and the epic key; the objective as the H2; "Owner \<name> · \<quarter>"; the progress % in large mono; **Edit** / **Delete**.
  - One row per KR: text · progress meter colored by status · "current / target unit" · **Check-in**.
- **Empty state:** "No objectives for \<quarter> yet." with **Create the first one**.
- **New / Edit objective modal:**
  - Objective \* · Level (Individual; Team with okr.team) · Quarter \* ("Select quarter…") · Owner (individual) · Grows value · Linked Jira epic (optional).
  - "Key results": a row of Key result · Start · Target · Current · Unit with × to remove, and **+ Add key result** (up to 5).
  - Cancel / Create objective (or Save changes). Errors: "Write the objective.", "Select a quarter.", "Every key result needs a description.", "Target must differ from start."
- **Check-in modal:** KR text, "Start · target", Current value, Note (optional), Cancel / Save check-in. The toast shows the new progress.
- **Delete confirmation:** "Delete objective?" naming the objective and its n key results, "This cannot be undone." Cancel / Delete (danger).

### 9.2 Road map

- **Milestones:** a timeline of milestones agreed in 1:1s. The employee proposes and the manager confirms. "Add milestone" opens a form for title, target date and type.
- **Promotion readiness:** the target level and date with criteria marked Met or Open:
  - performance index ≥ 4.0 for 2 quarters
  - led a cross-team epic
  - mentored 2 engineers
  - no culture value below 3.0
- **Skills matrix:** skills rated by the manager against the level guide, showing the current level, the level needed for the next level and the gap.

**Screen detail — Road map**

- **Header:** identity header with **Add milestone**.
- **Layout:**
  - Row g21: "Road map" timeline (subtitle "Agreed in 1:1s · employee proposes, manager confirms"; track badge, for example "Senior Engineer track") · "Promotion readiness".
  - Promotion readiness shows the target ("Senior Engineer (L4) · Apr 2027") and a criteria list with Met / Open badges.
- **Skills matrix** table ("Rated by manager against the level guide"): Skill · Current (dots) · Needed for next level (dots) · Gap ("−1" accent or "Ready" ok). Empty: "No skills matrix yet for this role."
- **Add milestone modal:** Outcome · When · Grows value, with Cancel / Propose. A proposed milestone shows as Proposed until the manager confirms it.

### 9.3 Training plans and mentoring

Trainees are Employees on a plan, and one person can be on several plans at once.

**Plan library (training.manage)**

- Plan fields: name, audience, duration in weeks, description, values it builds, checkpoint days (14 / 30 / 60 / 90), check-in cadence (daily, weekly, monthly), assessment type and pass mark (default 70%).
- Modules: title, type (course, reading, shadowing, hands-on task, assessment, meeting), week, hours and a link.
- Actions: new, edit (existing assignments keep their version), duplicate and delete (assignments continue).
- The library shows each plan's pass rate, average outcome rating and the trainees' rating of the plan.
- Seed plans: SE onboarding (12 wk), QA onboarding (8 wk), Jira discipline refresher (2 wk), Ownership & decision making (4 wk), Code review & PR standard (2 wk).

**Assign**

- Choose one or more plans, one or more people (new joiners listed first), a mentor (with their current trainee count), meeting frequency (daily, twice a week, weekly), start date, end of the training period (default = plan length), assessment window (default 3 working days) and check-in cadence.
- People already on a plan are skipped. A warning appears when a person's training load goes over about 16 h a week.
- The trainee and mentor are notified. Checkpoints due within 7 days are requested immediately, and later ones show Scheduled.
- Manager actions: edit assignment, pause / resume, remove (feedback is kept), and request a checkpoint review.

**Lifecycle stepper:** Not started → In training → Assessment → Final review → Trainee sign-off → Completed / Not completed.

| Step | Who | What happens |
| --- | --- | --- |
| Training period | Trainee, mentor | Modules ticked; check-ins per cadence; checkpoint reviews; mentor sessions |
| Check-ins | Trainee submits, mentor or manager verifies | Fields: completed (required), hours (required), confidence 1–5, blockers, next plan. States: Due, Submitted, Verified, Needs work (comment required), Missed. Submit late and Update & resend are allowed |
| Checkpoint reviews | Self, manager, mentor | Same form, notifications and grouped results as 360° |
| Assessment | Mentor or manager | Date, type (written, practical, code review, presentation, panel), score 0–100, evidence. Pass at the pass mark |
| Final review | Mentor, then manager | Each gives a 1–5 rating, strengths and what to improve. The manager decides Completed, Extend (new end date and new assessment; history kept) or Not completed. A failed assessment cannot be Completed |
| Sign-off | Trainee | Agrees or disagrees (comment required), rates the training 1–5 and the mentor 1–5. A disagreement is flagged to the manager |

- **Feedback acknowledgement:** the trainee marks every feedback item (check-in comments, checkpoint reviews, assessment, final reviews) as Acknowledge or I disagree + comment. The mentor can reply. Unacknowledged items show in the menu badge.
- **Training rating (1–5):**
  - weights: assessment 40% (score ÷ 20), mentor review 20%, manager review 20%, check-ins verified 10% (closed periods only), checkpoints 10%
  - missing parts are re-weighted, and the rating stays Provisional until the assessment and both reviews exist
  - bands: Outstanding ≥ 4.5 · Strong ≥ 3.75 · Meets ≥ 3 · Needs support ≥ 2.25 · Not yet
  - the person's overall rating is a plan-length-weighted average of completed plans; it feeds Growth, Adaptability and appraisal evidence
- **Mentoring tab** (for anyone who mentors):
  - KPIs: trainees, check-ins to verify, assessments to take, ratings to give, sessions in the last 7 days vs plan.
  - **Pending for you**, oldest first, with one action each: check-in, assessment, mentor rating, checkpoint rating, requested session, disagreement, sessions behind.
  - **My trainees** cards and an **activity** timeline with filters.
  - **Log session:** date, duration, mode (call, video, in person, chat), topics, next steps and an optional blocker that notifies the manager. Trainees can **Ask for a session**.
- **Manager tabs:**
  - **Trainees & assignments:** needs your action, assignments grouped by person, ratings by person.
  - **Mentors table:** active trainees, pending items, sessions vs plan, last session, trainee rating of the mentor, Send reminder.
- **Suggestions:**
  - New joiners (joined under 3 months) without a plan are offered onboarding.
  - Others are offered plans that build their three lowest culture values, assigned in one click.
  - Employees see "Suggested from your reviews".
- **Onboarding effect:** a new joiner on an active onboarding plan shows Onboarding / Training in Overview and Team utilization and is excluded from utilization signals and rebalancing.

**Screen detail — Training plans**

- **Header:** eyebrow "Learning & onboarding", title "Training plans", caption "Week-by-week plans for new joiners and targeted development · checkpoint reviews use the same review form as 360° feedback". Tabs depend on the role: Trainees & assignments and Plan library (training.manage), Mentoring (anyone who mentors), My training (anyone on a plan).
- **Trainees & assignments (manager):**
  - KPI row (5): In training (n assignments · n people on 2+ plans) · Assessments due · Final reviews due · Awaiting trainee sign-off · Avg training rating (n completed · n/n passed).
  - "Needs your action": rows with a direct button: n check-ins to verify, Write manager review & decision, Record assessment.
  - "Assignments" table, grouped by person: Person · Plan · period · Progress · Check-ins · Stage · Assessment · Rating, with Open. **Assign training plans** sits in the header.
  - "Mentors" table: Mentor · Trainees · To verify · Assess. · Ratings · Requests · Sessions 7 d · Last session · Rated by trainees. Click → mentor modal.
  - "Overall training rating by person" and "Suggested from reviews and feedback" (with Assign).
- **Plan library:** one card per plan showing audience, duration, cadence and modules by week, with Pass rate, Avg outcome and Trainee rating. Actions: **Edit**, **Duplicate**, **Delete**, **Assign**, and **New plan** in the header.
- **Plan detail** (opened from a row; "← Back"):
  - Eyebrow "Training plan · \<name>" with the period, assessment date and other plans. Actions: **Edit assignment**, **Pause** / **Resume**, **Remove**.
  - Plan picker chips when the person has several plans.
  - Stepper: Assigned → Training period → Assessment → Mentor & manager review → Trainee sign-off → Completed. Each step shows its date or owner, and the current step is highlighted.
  - Tiles: Progress (n of m modules) · Overdue · Check-ins (verified / total, n to review) · Assessment (due date) · Training rating (Provisional).
  - "\<Cadence> progress check-ins": rows per period with Due / Submitted / Verified / Needs work / Missed, modules finished, the reviewer's comment and a **Review** button.
  - Also: Modules by week (ticks), Mentor sessions, Feedback, Checkpoint reviews (**Request review**), and Assessment & final review.
- **Mentoring tab (mentor):**
  - KPI row (5): Trainees · Check-ins to verify · Assessments to take · Ratings to give · Sessions in the last 7 days (behind plan badge).
  - "Pending for you" (n items, oldest first) with one button each: Reply, Verify, Log session, Record assessment, Give rating.
  - One card per trainee and plan: Progress, Check-ins, Sessions 7 d, Rating, the last session and its next steps, with **Open plan** and **Log session**.
  - "Mentoring activity" timeline with filter chips (All, Check-ins, Sessions, Assessments & ratings, Modules, Acknowledgements) and **Show more**.
- **My training tab (trainee):**
  - KPI row: My training plans · Overall training rating · Feedback to acknowledge · Sign-offs, then a plan row of Progress · Overdue · Check-ins · Assessment · Training rating.
  - The plan picker, plus a plan header card (mentor, assigned by, cadence, stage badge).
  - "\<Cadence> progress check-ins" with **Submit update** ("n of m verified").
  - "Feedback from mentor & manager" (n to acknowledge) with **Acknowledge**.
  - "Mentor sessions" (n of m in 7 days) with **Ask for a session**.
  - "Assessment & final review" (pass mark · type), "Training rating" (score and band), "Modules" (✓ to tick), "Checkpoint reviews".
  - "All my training" table: Plan · Period · Stage · Assessment · Rating · My rating of the plan.
  - Mentors also see "You are also a mentor" with **Open mentoring**. "Suggested from your reviews" is shown too.

**Training modals**

| Modal | Fields | Buttons |
| --- | --- | --- |
| New / Edit training plan | Plan name \* · For · Duration (weeks) \* · Progress check-in (Daily / Weekly / Monthly) · Checkpoint days · Assessment after the period (type) · Pass mark (%) · Description · Culture values it builds (chips) · Modules \*: Title · Type · Week · Hours · Resource / link (× per row, + Add module) | Cancel · Create plan / Save |
| Assign training plans | Plans \* (checklist) · People \* (checklist, new joiners first and tagged) · Mentor \* (with current trainee count) · Start date \* · End of training period · Assessment within (working days) · Progress check-in · Mentor meetings (Daily / Twice a week / Weekly); load warning over \~16 h a week | Cancel · Assign & notify |
| Edit assignment | Mentor · Start date · End of training period · Assessment within · Progress check-in · Mentor meetings | Cancel · Save |
| Progress update (trainee) | "\<period> · \<plan> · reviewed by \<mentor>" · What did you complete? \* · Hours spent \* · Confidence (1–5) · Blockers · Plan for next period | Cancel · Submit for review |
| Review check-in | The submitted update, hours, confidence, modules ticked · Feedback (required for Needs work) | Cancel · Needs work · ✓ Verify |
| Log mentor session | Date \* · Duration (15 / 30 / 45 / 60 min) · How (Call / Video / In person / Chat) · What you talked about \* · Next steps for the trainee · Raise a blocker to the manager | Cancel · Save session |
| Ask your mentor for a session | What do you want to talk about? \* | Cancel · Send request |
| Record assessment | Plan · period · pass mark · attempt n · Assessment date \* · Type (Written test / Practical task / Code review / Presentation / Panel interview) · Score (0–100) \* · What they showed and what is missing \* | Cancel · Save assessment |
| Mentor / Manager review & decision | Assessment and mentor rating summary · Overall rating (1–5) \* · Strengths \* · What to improve next \* · Decision \* (Completed / Extend / Not completed, manager only) · New end date (Extend) · Reason (required for Extend or Not completed) | Cancel · Save review |
| Review & sign off (trainee) | The assessment and both reviews · Your response \* (agree / disagree) · Comment (required if disagree) · How useful was this training? \* (1–5) · How well did your mentor support you? \* (1–5) | Cancel · Sign off |
| Acknowledge feedback | The feedback item (who, what, text) · I understand and agree / I disagree · Comment (required if disagree) | Cancel · Acknowledge |
| Reply to trainee (mentor) | The trainee's comment · Your reply \* | Cancel · Send reply |
| Mentor detail (manager) | "\<mentor> · mentoring": active trainees · pending · sessions vs plan, and the list of pending items with due dates | Send reminder · Close |

## 10. Feature specs — culture, reviews and appraisal

Culture values combine 360° ratings with traceable data. Reviews collect those ratings and run 1:1s. Appraisal turns the year into an increment proposal capped at 20%.

### 10.1 Culture values

| # | Value | Weight | Source |
| --- | --- | --- | --- |
| 1 | Outcome Ownership | 15% | 360° + data |
| 2 | Moral & Ethics | 10% | 360° only, never tool data |
| 3 | Decision Making | 10% | 360° + data |
| 4 | Problem Solving | 10% | 360° + data |
| 5 | Risk Appetite | 5% | 360° + data |
| 6 | Adaptability | 5% | 360° + data |
| 7 | Agility in Execution | 10% | 360° + data |
| 8 | Collaboration | 10% | 360° + data |
| 9 | Resilience | 5% | 360° + data |
| 10 | Resourcefulness | 10% | 360° + data |
| 11 | Discipline | 10% | Data only (rules a–h) |

- **Value score** = 360° rating × (100 − data share)% + data signals × data share% + extra-mile bonus, capped at 5. The data share defaults to 40%. The culture pillar is the weighted average of the 11 values, and weights must total 100%.
- **360° rating:** the last published cycle, weighted manager 40%, internal team 25%, cross-department 15%, self 20%. "Not observed" answers are left out.
- **Data signals:** each parameter is scored 1–5 by its bands and averaged by weight. A parameter with no data or no opportunity is left out, and the other weights re-normalized. Every parameter, source and band is listed in section 12.
- **Screen:**
  - Value list (weight, dots, score), radar against the team average and a period selector (Q3, Q2).
  - **"How \<value> is calculated"** card: the formula with real numbers; the rater table; a parameter table (source, how measured, value, bands, score, weight); and the extra-mile items with the bonus each adds.
  - Behaviour anchors, signals and the evidence log.
  - "Calculation rules for all 11 values" table: click a row to open that value.
- **Discipline:**
  - A base band from rule a, then adjustments from rules b–h, never more than 1 below the base.
  - A 4-week daily-update heatmap, a rules table (value, target, Good / Improve), "How the score was calculated" and a rating guide.
  - A manager with reviews.calibrate can override with a written reason that the employee sees, and can return to the auto score.
  - Discipline shows 3.0 until 4 weeks of data exist.

| Rule | Measure | Good | Adjustment |
| --- | --- | --- | --- |
| a | Daily update compliance: days with a meaningful update ÷ working days with active tickets | ≥ 85% | Base band: 5 ≥ 95 · 4 ≥ 85 · 3 ≥ 70 · 2 ≥ 50 · else 1 |
| b | Stale tickets (no update for more than 2 working days) | 0 | ≥ 3 → −0.5; any stale when base is 5 → −0.5 |
| c | Proactive re-planning: due-date changes before the due date ÷ all changes | ≥ 80% | < 50% → −0.5; < 80% → −0.25 |
| d | Status accuracy | ≥ 90% | < 90% → −0.25 |
| e | Time logging: same-day share, bulk entries | ≥ 70%, < 3 bulk | Else −0.25 |
| f | Early blockers raised within 1 working day | ≥ 70% | < 70% → −0.25 |
| g | Process adherence: definition of done, PR standard %, stand-up attendance | ≥ 85% | < 85% → −0.25 |
| h | Attendance & leave score (Q3 upload) | ≥ 85%, no absence | < 85% → −0.25; < 70% → −0.5; each absence → −0.25 |

**Screen detail — Culture values**

- **Header:** identity header with a Period dropdown (Q3 2026, Q2 2026).
- **Row g12:**
  - "DO — our 11 values" list ("Weight · score · Discipline is calculated from Jira"). Each row shows the value name (Discipline tagged "auto"), weight %, 5 dots and the score. The selected value is highlighted, and clicking a row selects it.
  - "Radar": the person filled, the team average dashed, and the caption "culture pillar \<score>".
- **Selected value (all except Discipline):**
  - "How \<value> is calculated" (badge "4.27 / 5"; subtitle "Weight in the culture pillar n% · every number below is traceable to its source").
    - Formula strip: 360° rating × % + Data signals × % + Extra-mile bonus = Score, then the same equation with real numbers.
    - 1 · 360° rating: table Rater group · Weight · Average · How.
    - 2 · Data signals: table Parameter (with how measured) · Source · Value · Scoring bands · Score · Weight; parameters left out are marked.
    - 3 · Extra-mile bonus: items with the bonus each adds.
    - Moral & Ethics shows "360° only" plus a context-only list.
  - "\<value> · weight" card: team average, 360°, data and bonus summary, plus "Behaviour anchors" 1–5 with the current band highlighted.
  - "Ratings, data and evidence": signal rows, and the evidence log (kudos, examples, confirmed items) with **Give kudos**.
- **Discipline selected:**
  - KPI row (4): Daily update compliance (n of m working days) · Base rating (from compliance band) · Adjustments (from b–h) · Discipline score (Auto from Jira, or a Manager override badge).
  - "Daily updates · last 4 weeks" heat cells (✓ / ✗ per working day; leave and holidays excluded).
  - Rules table a–h: code · Measure (with formula detail) · Value · Target · Good / Improve. Row h has **Open attendance**.
  - "How the score was calculated" note; **Override score** / **Use auto score** (reviews.calibrate, not for yourself).
  - "Discipline rating guide" (5 bands with the base band marked).
- **Bottom:** "Calculation rules for all 11 values" table: Value · Weight · 360° · Data · Data parameters (weight within data). Click a row to open that value.
- **Modals:**
  - Give kudos: To · Value · What did they do? Cancel / Send to Slack.
  - Override Discipline score: the auto score and guidance, Score (1–5), Reason (required, visible to the employee). Cancel / Save override.

### 10.2 Reviews

One menu item with three tabs, each following its own permission. The menu shows a count of 360° reviews waiting.

**Performance review**

- Performance index with its pillar breakdown (weight, score, what feeds it), the quarterly trend and a summary export (Copy summary).
- Managers see a calibration table for their team: each pillar, the index and a proposed rating of Exceeds ≥ 4.2, Meets ≥ 3.5 or Developing. Ratings are normalized within role level and agreed in calibration; the index is an input, not the rating.

**1:1s & feedback**

- An agenda generated from signals: stale work, overload, low pulse, training items, visits.
- Shared notes, action items (owner, due, done) and kudos. Kudos also come from Slack /kudos.
- The last 1:1 date drives the "1:1 overdue" signal (more than 10 working days).

**360° feedback** (tabs: To give, My feedback, Team, Cycles)

- **Cycle** (Admin, users.all + review360.manage): name, quarter (required), due date, participants, reviewer counts per group (internal 3–5, cross-department 2–4) and an anonymity threshold (default 3). Only one cycle runs at a time.
- **Assign** (manager): pick one or many people to review and any number of reviewers from a searchable list grouped by department. Self and manager reviews are included by default. The group is set automatically: same department = internal team, other department = cross-department, their manager = manager. Self-review is never assigned to others, and duplicates are skipped.
- **Notifications:** every reviewer gets an in-app notification that opens the form; there is no separate login or external link. The manager sees each reviewer's state (Not started, In progress, Submitted) and can Remind, Remove or Send reminders to everyone.
- **Form:** each value rated 1–5 or Not observed, with its anchor shown. An example (10+ characters) is required for 1, 2 and 5. Keep doing and Start doing are required (15+ characters each). Save draft and Submit; submitted reviews are read-only. Reviewers can decline peer requests.
- **Results:** responses per group; groups under the threshold are merged into Peers (combined) or hidden. Also shown: hidden strengths (others ≥ self + 0.75), blind spots (self ≥ others + 0.75), bars per group with the weighted score, and comments grouped by group and never attributed. Managers see preliminary results while the cycle runs; employees see theirs after publish.
- **Close & publish** (Admin) replaces the 360° part of the culture values.
- **Training checkpoints** use the same form and appear in To give, tagged "Training checkpoint · Day N".

**Screen detail — Reviews**

- **Tabs:** large tabs Performance review · 1:1s & feedback · 360° feedback (with a count of reviews to give).
- **Performance review:**
  - Header actions: **Review summary** and **Submit for calibration** (managers).
  - Cards: "Performance index" (score, band, trend) · "Breakdown · Q3 2026" (pillar, weight, score, what feeds it).
  - Managers also see "Calibration · Platform team" ("Visible to managers and HR only"): Person · Level · Delivery · Quality · Culture · OKR · Growth · Index · Proposed (Exceeds / Meets / Developing), with the calibration note under it.
- **Review summary modal:** "Review summary · \<name>" with quarter, title and level; a table of pillar · weight · score and the performance index. Close / Copy summary.
- **1:1s & feedback:**
  - Header: **Give kudos**.
  - "Next 1:1 · \<date, time>" ("Both people can edit · private to you two and HR") with a "Suggested agenda (from signals)" checklist, shared notes and **Save notes**.
  - "Past 1:1s": notes and action items with Open / Done states.
  - "Kudos feed" ("Slack /kudos @name #value · counts as evidence, not score").
- **360° feedback:**
  - Header: eyebrow "360° reviews", the cycle name, and a caption with status · due · groups · anonymity rule. Sub-tabs: To give · My feedback (employees) · Team (managers) · Cycles (Admin).
  - KPI row (4): To do (due date) · Submitted · Unread notifications · Cycle due (department).
  - **To give:** "Reviews to give" ("Rate what you have observed; choose Not observed…") with Start review / Continue draft, plus a "Submitted" list with View.
  - **My feedback:** "My 360° cycles" (n of m reviews in, View results / Results open when published) and "My reviewers" grouped by Internal team and Cross-department with states. Who responded stays private until publish.
  - **Team:** "Assigned reviewers" with **Assign 360° review**. One block per person lists each reviewer (name, title · department) with a group badge, a state badge (Not started / In progress / Submitted) and **Remind** / **Remove**.
  - **Cycles:** one card per cycle with **New cycle** in the header.
    - Each card shows a status badge and tiles: Participants (department) · Responses (n / m, % complete) · Due (quarter) · Anonymity (3+ per group), plus the groups and weights line.
    - The running cycle has **Send reminders** and **Close & publish results**. A published cycle reads "Results published to employees and applied to culture values."
- **Review form** (full page, "← Back"):
  - Eyebrow "\<cycle> · \<group>", title "Feedback for \<name>", with "your name is shown" for the manager or self (anonymous for groups).
  - Note: "Discipline is measured from Jira, so it is not part of the 360"; scale "1 rarely · 3 consistently · 5 role model".
  - Per value: name, description, buttons 1 2 3 4 5 and Not observed, the anchor for the chosen rating, and an example field (required for 1, 2 and 5).
  - Keep doing \* and Start or do differently \*. Save draft / Submit.
- **Results:** response counts per group (merged or hidden below 3), hidden strengths and blind spots, bars per group with the weighted score, and comments grouped by group.
- **Assign 360° review modal:** "\<cycle> · due \<date>. Every reviewer gets a notification in Pulse."
  - 1 · People to be reviewed: chips with All; "Also include" Self-review and Manager review.
  - 2 · Reviewers: a searchable list grouped by department (name, title).
  - Rule note: same department → Internal team, other → Cross-department, their manager → Manager; nobody reviews themselves.
  - Cancel / Assign & notify.
- **New 360° cycle modal:** Name \* · Quarter \* · Due date \* · Participants (department) · Internal team reviewers (min–max) · Cross-department reviewers (min–max) · Anonymity threshold. Cancel / Create cycle.

### 10.3 Appraisal & increments (managers and Admin only; salaries confidential)

- **Appraisal score (1–5):** performance index 50% (last 4 quarters), 360° 20% (internal + cross-department, latest cycle with ≥ 2 responses), manager review 20% and Discipline 10%. Missing parts are re-weighted.
- **Bands and increments:**

| Score | Band | Increment |
| --- | --- | --- |
| 4.5 – 5.0 | Outstanding | 15 – 20% |
| 4.0 – 4.49 | Exceeds | 10 – 15% |
| 3.5 – 3.99 | Strong | 7 – 10% |
| 3.0 – 3.49 | Meets | 4 – 7% |
| 2.5 – 2.99 | Developing | 0 – 3% |
| < 2.5 | Below expectations | 0% + improvement plan |

- **Recommended %:** placed inside the band in proportion to the score and rounded to 0.5%.
- **Limits:** a hard cap of 20%. Eligibility needs 6+ months; 6–11 months is pro-rated. The budget defaults to 12% of team payroll, and over-budget proposals cannot be submitted. Distribution guide: at most about 20% of a team in Outstanding.
- **Workflow:**
  1. Draft: Apply recommendations, then adjust. An exception outside the band needs a written reason. A promotion can be flagged.
  2. Submit to HR: blocked while an exception has no reason or spend is over budget.
  3. HR approves or sends back each proposal, or uses Approve all submitted.
  4. Release letters: effective 1 October.
- **Evidence panel:**
  - score breakdown, index trend and OKR pillar
  - culture values and confirmed extra-mile points
  - bugs in own code, proactive fixes and support
  - customer visits, PRs and the PR standard
  - training rating
  - the year's 360° comments
- **Export:** a CSV of proposals for payroll (Data imports).

**Screen detail — Appraisal & increments**

- **Header:**
  - Eyebrow "Manager view · your team · confidential" or "HR · all teams · confidential"; title "Appraisal & increments · 2026"; caption "Review period Oct 2025 – Sep 2026 · effective 1 Oct 2026 · visible to managers and HR only".
  - Actions: **Apply recommendations** and **Submit to HR** (manager); **Release letters** and **Approve all submitted** (HR).
- **KPI row (5):** Annual payroll (n people) · Increment budget (% of payroll) · Proposed spend (₹ left or over) · Average increment (cap 20% per person) · Status (n approved · n submitted · n draft).
- **Row g2:** "Rating distribution" (people per band against the guide) · "How the increment is decided" table: Score · Band · Increment, with a "Set by HR" badge (HR sees **Edit standards**).
- **People table** ("Proposed % can be changed while in Draft; outside the band needs a reason"):
  - Columns: Person (avatar, level · title) · Perf. · 360° · Manager · Discipline · Score · Band · Current CTC · Recommended · Proposed % (inline number input) · New CTC · Status.
  - Exceptions show a badge. HR rows have Approve / Send back.
- **Person evidence modal:**
  - Name, title · level · joined date, band badge.
  - Tiles: Appraisal score · Recommended · Proposed.
  - Score breakdown (part · weight · score).
  - "Evidence for the year": Quarterly index · OKR pillar (Q3) · Culture values · Extra-mile points · Open bugs in own code · Proactive fixes · support · Customer visits (year) · Pull requests · Training rating · 360° comments.
  - Fields: Proposed increment % (0–20, band shown) and Reason (required outside the band); a Promotion flag.
  - Close / Save.
- **Appraisal standards modal (HR):**
  - Score weights (must total 100%): Performance index % · 360° feedback % · Manager review % · Discipline %.
  - Limits: Max increment % · Budget % of payroll · Min months to be eligible.
  - Bands: one row per band with From score · Min % · Max %.
  - Cancel / Save standards.
- **Errors:** an over-budget dialog ("… against a budget of ₹…. Lower some proposals before submitting." with OK), the toast "Add a reason for exceptions first: \<names>", and the 20% cap enforced in the input. Success toasts: "n proposals submitted to HR", "Approved · \<name>", "Sent back to \<manager>".

## 11. Feature specs — administration

Administration covers who can sign in and what they see, how tools connect, every rule behind the numbers, and CSV imports for data with no live integration.

### 11.1 Users

- **Directory:** search by name, email or role. KPIs count Admins, Managers, Employees and Invited users. Each row shows name, title and department, email, role, manager, status and last login.
- **Add user:** full name, work email (valid and unique), role, reports to, job title, department, level L1–L5 and a temporary password (at least 6 characters). New users start as Invited and become Active at first sign-in. Employees get a performance profile automatically.
- **Edit:** name, role, manager, title, department, level and status (Active, Invited, Deactivated). The email cannot be changed.
- **Delete:** a confirmation names what is removed (login, profile, OKRs, extra-work records) and suggests Deactivate instead. Nobody can delete themselves, and reports are reassigned to the deleter.
- **Manager limits:** managers (users.team) see and manage only their Employees, cannot grant Manager or Admin, and their new users report to them.
- **Import CSV and Export CSV** (11.5).

**Screen detail — Users**

- **Header:** eyebrow "Administration", title "Users", caption "All users in the organisation · n shown" (managers: "Employees who report to you"). Controls: a search box (name, email, role), **Import CSV**, **Export CSV**, **Add user**.
- **KPI row (4):** Admins · Managers · Employees (each with its role description) · Invited (waiting for first login).
- **"Directory" table:**
  - Columns: Name (avatar, "(you)", title · department) · Email (mono) · Role badge (Admin danger, Manager brand, Employee neutral) · Manager · Status badge (Active ok, Invited accent, Deactivated neutral) · Last login · Actions (**Edit**, **Delete**, or "No permission").
  - Subtitle: "Managers can add employees to their own team · only Admin can assign Manager or Admin roles".
- **Add user modal:** Full name · Work email · Role · Reports to (disabled for managers) · Job title · Department · Level · Temporary password (prefilled). A note says the user signs in with it and is asked to change it, and that an invite is sent by email. Cancel / Add user. Errors: "Enter a name.", "Enter a valid work email.", "A user with this email already exists.", "Temporary password needs at least 6 characters."
- **Edit modal:** same fields, with the email disabled and Status instead of password. Cancel / Save.
- **Delete confirmation:** what is removed (and n open Jira issues no longer tracked), with a suggestion to deactivate instead. Cancel / Delete user.

### 11.2 Roles & permissions

- **Matrix:** permissions grouped by area against roles, with a user count per role. A toggle changes a role immediately for everyone with it. Admin rows are locked on.
- **New role:** created from an existing role. A custom role can be deleted only when no user holds it.
- **What each role sees:** the menu each role gets, generated from its permissions.

**Screen detail — Roles & permissions**

- **Header:** eyebrow "Administration", title "Roles & permissions", caption "The menu and every screen are shown or hidden from these permissions. Built-in roles can be edited; Admin always keeps full access." Action: **New role**.
- **"Permission matrix"** ("Changes apply immediately to everyone with that role"):
  - Group rows: My work, People, OKRs, Feedback, Reviews, Admin, 360 reviews, Appraisal, Training, Attendance.
  - Each permission row shows the key in mono and its description, then one switch per role. Admin switches are locked.
  - Column heads show the role and its user count. Custom roles have **Delete**.
- **"What each role sees"**: one card per role with a badge, the description and the menu it produces.
- **New role modal:** Role name · Start from (existing role) · Description. Cancel / Create role.

### 11.3 Integrations

Tiles, Connect, Settings, Change credentials, Fix, Disconnect, the credentials table and the credential activity log are specified in section 5.

**Screen detail — Integrations**

- **Header:** eyebrow "Settings", title "Integrations", caption "Pulse reads from these tools. It only writes when you nudge, give kudos or reassign."
- **"Connected (n)"** tiles: monogram (JI, GH, TF, CF, SL, GC, HR) · name · what it reads · "Feeds: …" · status line (Connected · synced n min ago; Token expires in 3 days; Sync failed · 401 Unauthorized) · **Settings** or **Fix**.
- **"Jira configuration":**
  - The method · service account and the board picker (PLAT, NET, UI).
  - The story points field.
  - The sync mode: Webhooks + hourly, Hourly poll only, or Nightly only.
  - The switch "Ignore edits by bots and non-assignees when computing last activity".
  - The endpoints used and **Save**.
- **"Sync log" (today)** with **Sync now**.
- **"Credentials" table** ("Only Admins see this page · secrets are encrypted and shown as the last 4 characters"): Tool · Method · Saved · Rotation · Last test, with View / Change.
- **"Available (n)"** tiles with **Connect**.
- **"Credential activity"** audit list.
- **Settings modal (\<tool>):** tabs Credentials · Data & scopes · Sync; actions Change credentials · Test connection · Disconnect · Close.
- **Connect / Change credentials modal:** Authentication method (when there is more than one), then the tool's fields marked \* (for example Jira site URL, Service account email, API token; Slack: Workspace, Bot token, Signing secret; PagerDuty: subdomain, Read-only API key). Secret fields are password inputs, and blank keeps the current value.
  - The test result appears inline.
  - Buttons: Test connection · Cancel · Save & connect (or Save new credentials), enabled after a pass.

### 11.4 Rules & thresholds (Admin)

Every number behind a score is editable here. Each card shows its defaults (section 12), and **Reset to defaults** restores all of them.

- Staleness & capacity: stale days, focus hours, day hours, not-started %, exclude bots.
- Utilization bands (under, healthy, over).
- Performance index weights (must total 100%; a warning shows otherwise, and the index is normalized meanwhile).
- Culture value weights (must total 100%) and the data-share slider.
- Discipline rating bands (d5, d4, d3, d2).
- Monthly effort & extra-mile points: points per type, monthly cap, bonus per point, bonus cap and the unplanned alert.
- Customer visits: visit points and the visit cap.
- Pull request standard: each check on or off, title pattern (validated regex), size limit and approvals.
- Training rating & sign-off: weights, pass mark, assessment window and sign-off window.
- Attendance & leave: office days a week, notice days (casual, earned), late-after time, late allowance a month, and a switch to use attendance in culture values.
- Appraisal standards (set in Appraisal by appraisal.approve holders): weights, bands, cap, budget, eligibility.

**Screen detail — Rules & thresholds**

- **Header:** eyebrow "Settings", title "Rules & thresholds", caption "Changes apply instantly across every screen." Action: **Reset defaults**.
- **Control pattern:** every rule is a row with its name, a help line, a range slider and the live value. On/off rules use a switch, the title pattern uses a text field (validated regex), and "Late after" is a time field.

| Card | Controls |
| --- | --- |
| Staleness & capacity | Stale after more than (d) · "Not started" after (% of sprint) · Focus hours per day · Slack nudges (switch) |
| Utilization bands | Under below · Healthy up to · Over up to |
| Monthly effort & extra-mile points | Hours per day · Unplanned alert at · points for Added mid-sprint, Incident / hotfix, Took over / helped, Untracked useful work, Reviews for others, Extra support ticket, Proactive fix · Monthly cap · Culture bonus per point · Max bonus per value |
| Customer visits | Customer visit · Each extra day on site · Weekend or holiday on site · Customer escalation · Customer rated 4.5 or higher · Monthly cap for visit points |
| Attendance & leave | Office days a week · Notice for casual leave / comp off · Notice for earned leave · Late check-ins allowed a month · Late after · Use attendance in culture values (switch) |
| Pull request standard (TFS & GitHub) | Title pattern · Require a linked work item · Require the description template · Blocking threads resolved before merge · Max lines changed · Min approvals · Min reason length · First review within · Compliance target · **Preview standard** |
| Training rating & sign-off | Weights for Assessment, Mentor final review, Manager final review, Check-ins verified, Checkpoint reviews · Default pass mark · Assessment due · Trainee sign-off due |
| Culture value weights | Data signals share · one slider per value; badge "Total 100%" (danger when it is not) |
| Discipline rating bands | Score 5 at · Score 4 at · Score 3 at · Score 2 at |
| Performance index weights | Delivery · Quality · Culture values · Goals / OKRs · Growth; "Total 100%" badge and a warning when it is not |

### 11.5 Data imports (CSV)

Every upload follows the same three steps. Nothing is saved before Import, every upload is logged, and imports from the current session can be undone.

1. **Choose file.** Drop or choose a .csv file, paste CSV text, or load a sample file. A table lists every column (required or optional, format, example). A template can be downloaded or shown as text.
2. **Check.** Shows rows read, rows ready (new and update), errors, warnings and skipped rows, plus a summary (for example, months replaced). Where there are modes, a mode choice is offered. Errors are listed by line, column and problem; warnings by line; and a preview shows the first 12 rows with the action for each. Rows with errors are skipped.
3. **Done.** Shows the result, **Undo this import** and Open the target page.

**Parsing:**

- UTF-8 with or without BOM; comma, semicolon or tab separators; quoted fields.
- Case-insensitive headers with aliases (for example email, work\_email, reports\_to).
- Dates as YYYY-MM-DD or DD-MM-YYYY.
- Limits: 5,000 rows and 5 MB per file.
- Missing required columns stop the import with a clear message. Unknown columns are ignored and listed. Non-CSV files are rejected with a "Save as CSV UTF-8" tip.

| Import | Who | Columns (required first) | Key checks | Modes |
| --- | --- | --- | --- | --- |
| OKRs | okr.own (own rows), okr.team | quarter, owner\_email, objective, key\_result, start, target · level, current, unit, value, epic | Quarter exists; owner visible and editable; target ≠ start; numbers; same quarter + owner + objective = one objective; duplicate KR in an objective; unknown value → warning | Add new and update matching · Add new only |
| Attendance | attendance.upload | employee\_email, date, status · leave\_type, applied\_on, check\_in, check\_out | Known user with a profile; no future or pre-joining date; month in range; one row per person per day; leave needs a type; HH:MM; check-out after check-in; leave on a visit day = error; weekend work, holiday rows, missing days, no check-in = warnings | Replace months for people in the file · Merge days |
| Users | users.all, users.team | name, email · role, manager\_email, title, department, level, status | Valid unique email; role Admin / Manager / Employee only (intern or trainee → Employee hint); manager can be in the same file; department and level from the lists; managers add only Employees to their own team; no edit of own account; trainee title → warning to assign a plan | Create and update · Create only |
| Customer visits | Managers, Admin | employee\_email, customer, city, purpose, from, to, requested\_by, owner\_email · reference | Person in your team; purpose from the list; max 14 days; no overlap with visits, file rows or leave; past start → warning | Add as planned visits |
| Holiday calendar | attendance.upload, rules.manage | date, name | Valid unique date; weekend or past date → warning; same name → skipped | Add and rename |

**After import:**

- Attendance notifies managers and recalculates capacity, Discipline and Collaboration.
- Users receive invites with temporary passwords.
- Planned visits notify the engineer.
- OKRs open the imported quarter.

**Data imports screen:**

- A card per import the user may run: what it feeds, required columns, the last upload, and Upload CSV / Template / Export current. Exports use the same column layout, so a file round-trips.
- Appraisal proposals export (for payroll).
- "Where each module gets its data": live integration, form plus CSV, CSV only, workflow only, calculated, or export only.
- Upload history, newest first: when, data, file, by, rows and result, with Undo for session imports.
- In the hosted prototype, downloads ask the viewer to confirm.

**Screen detail — Data imports**

- **Header:** eyebrow "Administration", title "Data imports", caption "Bring in data that has no live integration with a CSV file · every file is checked before anything is saved, logged here, and can be undone".
- **Import cards (g2)**, one per import the user may run: title, "Feeds …", a badge ("Last \<date>" or "No uploads yet"), the required columns, and **Upload CSV** · **Template** · **Export current**.
- **"Exports":** Appraisal proposals (CSV for payroll), for appraisal roles.
- **"Where each module gets its data"** table: Module · Source · Upload (a CSV badge where upload is offered).
- **"Upload history"** table: When · Data · File · By · Rows · Result (n imported · n errors, or Undone) · **Undo** for session imports.
- **Upload modal (900 px)** with a step indicator: 1 · Choose file → 2 · Check → 3 · Done.
  - **Step 1:**
    - Eyebrow "Import from CSV", the data name and "Feeds …".
    - Dashed drop zone "Choose a CSV file / or drop it here" with format notes; it highlights on drag-over.
    - **Load sample file** and **Download template**, with "Nothing is saved until you confirm on the next step".
    - Collapsible "Paste CSV text instead" with **Check pasted text**.
    - "Columns" table: Column · Required · Format · Example.
    - Collapsible "Show the template as text" and Cancel.
    - A file error shows a danger alert "Can't read file".
  - **Step 2:**
    - The file name and "n data rows read · nothing has been saved yet".
    - KPI row (4): Ready to import (new · update) · Errors (rows skipped) · Warnings · Skipped.
    - Summary note; mode cards (radio).
    - "Errors — these rows will be skipped" table: Line · Column · Problem.
    - Collapsible warnings; "Preview · first 12 of n" table with Line · Action badge (New / Update / Skip) and the data columns.
    - Buttons: Choose another file · Cancel · **Import n rows** (disabled at 0).
  - **Step 3:** an ok alert with the result and errors skipped, a module note (attendance recalculation, user invites), then **Undo this import** · Close · **Open \<page>**.
- **Entry points elsewhere:** Import / Export CSV on OKRs and Users; Import CSV on Customer visits; Upload monthly CSV on Attendance; Upload / Replace per month in Monthly uploads.

### 11.6 Notifications

- **Bell:** an unread count in the top bar. Each item opens the right screen, form or tab and is marked read; there is Mark all read.
- **Triggers:**
  - 360° reviews assigned or reminded
  - training assigned; check-in submitted, verified or needs work; checkpoint requested; assessment and final reviews; sign-off due; disagreement
  - mentor session logged, requested or blocked
  - visit planned or report due; feedback requested from the account owner
  - low weekly pulse to the manager
  - attendance uploaded, to managers
  - nudges for stale work
- **Copies:** email, Slack or Teams copies are optional through the integrations. The weekly digest goes out on Monday.

**Screen detail — Notifications and feedback**

- **Bell** in the top bar with an unread count badge. Clicking it opens a "Notifications" panel with **Mark all read**.
  - Each item shows the text (for example "Share 360° feedback on Rohit Kulkarni · Software Engineer onboarding · Day 14 · due 28 Sept"), the date and its state ("open to review").
  - Clicking an item opens the target form or screen and marks it read.
- **Menu badges** repeat the counts that need action (Work items stale, Effort pending, Visits, Training, Reviews to give, Action center).
- **Toasts** confirm every action in plain words, for example "Objective created", "Milestone proposed — manager will confirm", "Update submitted · your mentor was notified", "Review submitted — thank you", "Import undone · \<file>".
- **Theme toggle** (◐) switches light and dark; the choice is kept per browser.

## 12. Calculations reference

This section is the single source for every formula and default. All defaults are editable in Rules & thresholds unless marked fixed.

**Performance index (1–5)**

```latex
\text{Index} = \frac{35\,D + 15\,Q + 25\,C + 15\,O + 10\,G}{100}
```

D = Delivery (commitment met, cycle time, carry-over, predictability) · Q = Quality (reopen rate, escaped bugs, review findings) · C = culture pillar · O = OKR pillar · G = Growth (road map progress, skills, mentoring, averaged with the training rating). Calibration labels: Exceeds ≥ 4.2 · Meets ≥ 3.5 · else Developing.

**Workload**

```latex
\text{Utilization} = \frac{\sum \text{remaining hours} \div \text{focus hours/day}}{\text{working days left} - \text{leave} - \text{holidays} - \text{visit days}} \times 100
```

- Remaining hours: the Jira remaining estimate, else original − spent, else SP × hours per point (calibrated over the last 3 sprints).
- Closed periods: utilization = hours logged ÷ (working days × 8 h).
- Stale: In Progress or In Review with more than 2 working days idle. Last activity is a transition, worklog, comment or linked commit by the assignee; Jira's updated field is never used alone.
- Not started: To Do when the sprint is more than 40% elapsed. Overdue: due date passed and not Done. No estimate: neither an estimate nor SP.
- Checks: low logged time when spent ÷ (working hours in progress × 0.75) < 0.3 · estimate grew 50%+ when (spent + remaining) ÷ original > 1.5 · longer than estimated when in progress for more than 2× the estimate.
- Sprint metrics: commitment met = SP done ÷ SP committed at start · carry-over · median cycle time (In Progress → Done, working days) · reopen rate (Done → not Done within 14 days).

**Culture value score**

```latex
V = \min\left(5,\; R_{360}\,(1 - s) + S_{data}\,s + B\right), \quad s = 0.40
```

- R360 = manager 40%, internal 25%, cross-department 15%, self 20% (groups re-normalized).
- S\_data = the weighted average of parameter band scores, leaving out parameters with no data or no opportunity.
- B = Σ confirmed points × 0.05 ÷ values credited, capped at 0.3 per value per quarter.
- Moral & Ethics: s = 0. Discipline: rules a–h only.

| Value | Data parameters, weight within data · bands for 5 / 4 / 3 / 2 |
| --- | --- |
| Outcome Ownership | Commitment met (last 3 sprints) 35% ≥ 95/85/70/55% · Issues carried over 20% ≤ 0/1/2/4 · Open bugs in own code 20% ≤ 0/1/2/3 · Manager follow-ups 10% ≤ 0/1/2/3 · Visit CSAT 15% ≥ 4.8/4.3/3.8/3.0 |
| Decision Making | ADRs and design docs 40% ≥ 5/4/2/1 · Median time to decide 35% ≤ 1/2/3/4 d · Decisions reversed 25% ≤ 0/1/1/2 |
| Problem Solving | Bugs fixed without reopen 35% ≥ 98/92/85/75% · Others' bugs fixed proactively 30% ≥ 3/2/1 · RCAs 20% ≥ 3/2/1 · Support tickets resolved 15% ≥ 4/3/2/1 |
| Risk Appetite | Spikes and experiments 40% ≥ 3/2/1 · Ideas proposed → piloted 35% ≥ 3/2/1 · Risks raised in planning 25% ≥ 4/3/2/1 |
| Adaptability | Mid-sprint work absorbed 35% ≥ 4/3/2/1 · Areas worked in 25% ≥ 4/3/2/1 · Training rating 20% ≥ 4.5/3.75/3/2.25 · Time to productivity on a new tool 20% ≤ 4/7/10/13 d |
| Agility in Execution | Median cycle time 35% ≤ 2/3/4.5/6 d · Stale share 25% ≤ 0/15/30/50% · Median PR size 20% ≤ 200/300/400/600 lines · Logged ÷ estimate 20% ≤ 110/125/140/160% |
| Collaboration | PR reviews given 25% ≥ 15/10/6/3 · Review comments to standard 20% ≥ 85/70/55/40% · Kudos 20% ≥ 3/2/1 · Helped others 20% ≥ 3/2/1 · Cross-team issues 15% ≥ 4/3/2/1 · Office and on-site presence 15% ≥ 95/85/70/55% |
| Resilience | Incidents and escalations 35% ≥ 3/2/1 · Delivery in overloaded sprints 40% ≥ 90/80/65/50% · Mood in heavy weeks 25% ≥ 4/3.5/3/2.5 |
| Resourcefulness | Tools and scripts 35% ≥ 3/2/1 · Docs, templates, prompts shared 30% ≥ 6/4/2/1 · Hours saved 35% ≥ 30/20/10/3 h |

**Other formulas**

| Number | Formula |
| --- | --- |
| OKR pillar | 1 + progress ÷ 0.7 × 3, max 5 |
| PR standard % | passed ÷ judged checks, averaged across PRs; target 85% |
| Training rating | 0.4 × assessment ÷ 20 + 0.2 × mentor + 0.2 × manager + 0.1 × check-ins (as 1–5) + 0.1 × checkpoints; missing parts re-weighted |
| Attendance score | average of notice %, office policy % and punctuality % (with data) |
| Office policy % | Σ min(office + duty days, required) ÷ Σ required, required = min(3, available days) per week |
| Punctuality % | (check-ins − max(0, late − allowance)) ÷ check-ins; allowance = 3 × working days ÷ 21 |
| Visit points | 1.5 + 0.5 per extra day + 1 per weekend or holiday day + 0.5 escalation + 1 when CSAT ≥ 4.5 |
| Appraisal score | 0.5 index + 0.2 360° + 0.2 manager + 0.1 Discipline |
| Recommended increment | band low + (band high − low) × position of the score in the band, rounded to 0.5%, max 20%, pro-rated for 6–11 months of service |

**Defaults**

| Setting | Default |
| --- | --- |
| Stale after / focus hours / day hours | 2 working days / 6 h / 8 h |
| Not started alert / stale ratio alert | 40% of sprint elapsed / 30% |
| Utilization bands | Under < 60 · Healthy 60–85 · At capacity 85–100 · Over 100–120 · Critical > 120 |
| Index weights | Delivery 35 · Quality 15 · Culture 25 · OKR 15 · Growth 10 |
| Data share | 40% |
| Discipline bands | 95 / 85 / 70 / 50% |
| Extra-mile | 0.05 per point · cap 0.3 per value per quarter · 6 points per month · unplanned alert 30% |
| Visits | cap 8 points per month |
| Training | pass mark 70% · assessment window 3 working days · weights 40/20/20/10/10 |
| Attendance | 3 office days a week · notice 2 d (casual), 7 d (earned) · late after 10:30 · 3 late a month · used in culture: on |
| PR standard | 7 checks on · 400 lines · 2 approvals · rubber-stamp over 200 lines |
| Appraisal | weights 50/20/20/10 · cap 20% · budget 12% of payroll · eligibility 6 months |
| 360° | internal 3–5 · cross-department 2–4 · anonymity 3 · example required for 1, 2, 5 |
| Signals | 1:1 overdue after 10 working days · pulse low at mood ≤ 2 or workload 5 |

## 13. Non-functional requirements

Pulse holds performance, attendance and salary data, so access control, privacy and auditability matter as much as the features.

| Area | Requirement |
| --- | --- |
| Authentication | SSO (Google, Microsoft Entra, Okta) via OIDC/SAML; password sign-in for the pilot with lockout after 5 failures; session timeout 12 h; temporary passwords must be changed at first sign-in |
| Authorization | Every API call checks permission and visibility on the server; the UI hiding a button is never the only check. Salary and appraisal endpoints require appraisal.manage or appraisal.approve |
| Privacy | Employees see only their own data; 360° peer answers are stored with the reviewer id but never returned for internal or cross-department groups; groups under the anonymity threshold are merged or hidden server-side; attendance check-in times visible only to the person and their manager chain; Moral & Ethics never fed by tool data |
| Secrets | Integration secrets encrypted at rest with KMS, write-only from the browser, masked to the last 4 characters, rotation tracked |
| Audit log | Append-only log of: role and permission changes, user create / edit / delete, credential changes, overrides (Discipline, bug attribution, appraisal exceptions), confirmations and rejections of points, imports and undos, publish of 360° results, appraisal approvals and releases |
| Data retention | Raw tool events 13 months; aggregated scores kept with each review record; deleted users' personal data removed within 30 days, aggregates anonymized |
| Accessibility | WCAG 2.2 AA: 2 px focus ring on every control; text contrast ≥ 4.5:1 (status ink tokens ≥ 5.5:1); status always as a word, color second; keyboard operable modals with focus trap; charts print their values; screen-reader labels on icons and charts |
| Responsive | Desktop (1440), laptop (1024) and phone (390) with no horizontal page scroll; tables scroll inside their card; KPI tiles wrap two per row on phones |
| Theming | Light and dark from design tokens; no hard-coded colors |
| Performance | First screen under 2 s on broadband; period switch under 500 ms from cached aggregates; imports of 5,000 rows validated in under 3 s |
| Sync | Webhooks near real time; hourly poll fallback; nightly reconcile; sync failures surface on the tile and the Action center |
| Consistency | One calculation service; a person's utilization, stale count and Discipline must match on Overview, Work items, Team utilization and Culture values |
| Empty states | Every list has an empty state; no NaN, undefined, null or blank cards for people without data; days before joining show "—" |
| Localization | Dates as "28 Sep" in en-GB; 24-hour times; working days per company calendar; currency for salary per company setting |

## 14. API outline and data contracts

The build splits into connectors that ingest tool data, one calculation service that owns every formula in section 12, and a REST API (JSON, versioned under /api/v1) that the web app uses. Every endpoint enforces permission and visibility on the server and accepts the period key from section 4.

| Area | Endpoints |
| --- | --- |
| Auth | POST /auth/login · POST /auth/logout · GET /me (user, role, permissions, menu) |
| People | GET /people (visible) · GET /people/{id}/overview?period= · GET /people/{id}/progress · GET /people/{id}/roadmap · POST /people/{id}/milestones |
| Work | GET /people/{id}/work-items?period=&filter= · GET /issues/{key} · POST /issues/{key}/nudge · GET /team/utilization?period= |
| Pull requests | GET /prs?person=&period= · GET /prs/{id} · GET/PUT /rules/pr-standard |
| Effort | GET /effort?person=&period= · GET /extra-items?state= · POST /extra-items/{id}/confirm · POST /extra-items/{id}/reject |
| Bugs & support | GET /bugs?person=&period= · POST /bugs/{key}/dispute · POST /bugs/{key}/resolve-dispute · POST /bugs/{key}/take · POST /bugs/{key}/pick-up · GET /support-tickets · POST /support-tickets/{id}/take |
| Visits | GET/POST /visits · PUT/DELETE /visits/{id} · POST /visits/{id}/report · POST /visits/{id}/feedback · POST /visits/{id}/verify · POST /visits/{id}/remind |
| Attendance | GET /attendance?person=&period= · GET /attendance/team?period= · GET /attendance/uploads |
| OKRs | GET /okrs?owner=&quarter= · POST /okrs · PUT/DELETE /okrs/{id} · POST /okrs/{id}/krs/{i}/check-in |
| Training | GET/POST /training/plans · PUT/DELETE /training/plans/{id} · POST /training/assignments · PUT /training/assignments/{id} · POST …/check-ins · POST …/check-ins/{id}/verify · POST …/sessions · POST …/assessment · POST …/final-review · POST …/sign-off · POST …/feedback/{id}/ack · GET /training/mentoring |
| Culture | GET /people/{id}/culture?quarter= (values, per-value calculation, Discipline rules) · POST /people/{id}/discipline/override · DELETE …/override |
| Reviews | GET /reviews/performance/{id} · GET /reviews/calibration · GET/POST /one-on-ones · POST /kudos · POST /cycles · POST /cycles/{id}/assign · GET /review-requests?mine · PUT /review-requests/{id} (draft, submit, decline) · POST /cycles/{id}/publish · GET /people/{id}/360-results |
| Pulse | POST /pulse (mood, workload, note) · GET /pulse/team |
| Appraisal | GET /appraisal?team= · PUT /appraisal/{personId} · POST /appraisal/submit · POST /appraisal/{personId}/approve · POST /appraisal/{personId}/send-back · POST /appraisal/release · GET/PUT /appraisal/standards |
| Action center | GET /actions (do today) · GET /signals · GET /forecast · GET /capacity/next-sprint · GET /coverage · GET /digest |
| Admin | CRUD /users · CRUD /roles · GET/PUT /rules · POST /rules/reset · GET /integrations · POST /integrations/{tool}/test · POST /integrations/{tool}/connect · PUT /integrations/{tool}/credentials · DELETE /integrations/{tool} · POST /integrations/{tool}/sync · GET /credentials/activity |
| Imports | POST /imports/{type}/check (multipart CSV → check result) · POST /imports/{checkId}/commit?mode= · POST /imports/{logId}/undo · GET /imports/log · GET /imports/{type}/template · GET /exports/{type}?period= |
| Notifications | GET /notifications · POST /notifications/{id}/read · POST /notifications/read-all |

**Contracts**

- **Check result** (imports): { rows, ready, new, update, skipped, errors: \[{line, column, message}\], warnings: \[...\], summary: \[{label, value}\], preview: \[{line, action, cells}\], modes, checkId }. A commit is accepted only with a checkId that has not expired (30 min) and is idempotent.
- **Undo** restores the exact previous state recorded at commit. It is allowed for 24 h, by the importer or Admin, and is logged.
- **Calculation response** (culture value): { value, score, formula: {r360, dataScore, dataShare, bonus}, raters: \[{group, weight, avg}\], params: \[{name, source, how, raw, display, bands, score, weight, na}\], bonusItems: \[...\] }. The UI renders the detail view from this payload only.
- **Errors:** 400 validation (field + message), 403 permission (no data leak about existence), 404, 409 conflict (stale version), 422 business rule (for example over budget or a failed assessment marked Completed).
- **Webhooks in:** Jira, GitHub, TFS service hooks and Slack, with signatures verified and processing idempotent by event id.
- **Jobs:** hourly poll, nightly reconcile, daily stale-signal refresh, Friday pulse prompt, Monday digest, month-end attendance reminder, and expiring-token checks.

## 15. Acceptance, testing and delivery

A feature is done when its acceptance criteria pass for all three roles at all three widths, its numbers match the calculation reference, and the full audit finds no blank or broken values.

**Acceptance criteria (per module)**

| Module | Must pass |
| --- | --- |
| Access | Each role sees exactly its menu; a direct URL without permission shows "You don't have access"; managers never see non-reports; employees never see the Appraisal or Team screens |
| Period | Sprint, month, release and quarter give identical numbers for the same dates on every screen; the shared choice carries across screens; history rows open their period |
| Work items | Stale, not started, overdue and no-estimate follow section 12 exactly; leave, holidays and visits are excluded from idle days and capacity |
| Effort | Code-only days detected; confirm and reject change culture bonuses at once; monthly and visit caps enforced |
| PRs | Each of the 7 checks is judged or Pending; titles and comments get suggested rewrites; rubber-stamps counted; PR standard feeds Discipline g |
| Bugs | Dispute and resolution flow; proactive fix awards points only after confirmation |
| Visits | Overlap with visits or leave rejected; lifecycle states in order; account owner notified; points and values per purpose |
| Attendance | Replace and merge modes; sick and emergency never penalized; office requirement reduced in leave weeks; rule h and the Collaboration signal update after an upload and after undo |
| OKRs | Quarter required; progress and pillar formulas; import updates matching objectives as check-ins |
| Training | Stepper order; failed assessment cannot be Completed; Extend keeps history; every feedback item can be acknowledged; rating provisional until complete; multi-plan picker; mentor pending list |
| Culture | Detail view numbers reproduce the score; parameters with no opportunity are left out; Moral & Ethics never uses data; Discipline override shows the reason to the employee |
| 360° | Anonymity threshold merges or hides groups server-side; required examples and open answers; reviewers notified in app |
| Appraisal | Hard cap of 20%; exception reason required; over-budget submit blocked; pro-rating; approve, send back and release |
| Imports | Every check in 11.5 produces the stated error or warning; nothing saved before Import; undo restores the previous state exactly |
| Admin | Credentials replaced only after a passing test; secrets never returned; every change in the audit log |

**Test plan**

1. **Unit tests** for every formula in section 12, with fixtures for leave, holidays, joiners, no-opportunity parameters and caps.
2. **Contract tests** per endpoint for each role (allowed, forbidden, and visibility filtering).
3. **End-to-end flows** with Playwright, signing in as all demo accounts: Jira → workload → Discipline → index → appraisal; extra work → confirm → culture bonus; 360° assign → notify → submit → publish; training assign → check-in → assessment → final review → sign-off; visit plan → report → feedback → verify; attendance upload → rule h → undo; each CSV import with its sample file.
4. **Full UI audit** of every account, route, tab and detail view, scanning for NaN, undefined, null, Infinity, "-0.0", empty cards and horizontal overflow at 390, 1024 and 1440 px. The prototype currently passes with 0 issues.
5. **Accessibility:** axe scan plus keyboard-only runs of every modal and form.
6. **Data tests:** imports of 5,000 rows, malformed CSV (quotes, semicolons, BOM, bad dates) and duplicate rows.

**Delivery phases.** The order is a proposal for the team to confirm; no dates are set yet.

| Phase | Scope | Gate to the next phase |
| --- | --- | --- |
| 1. Foundation | SSO and sign-in, RBAC, users and roles, Jira + GitHub + TFS connectors, period model, Overview, Work items, Team utilization, Rules & thresholds, notifications | Numbers match Jira for one team over 2 sprints |
| 2. Work signals | Effort and extra-mile points, Bugs & support, Pull requests and the PR standard, Action center, Progress, weekly pulse | Managers use the Action center daily for 2 weeks |
| 3. People cycle | OKRs, Culture values with Discipline, Reviews (performance, 1:1s, 360°), Training plans and mentoring, Customer visits, Road map | One 360° cycle published end to end |
| 4. HR and data | Attendance, Data imports and exports, Appraisal & increments, audit log, HRMS and support-desk connectors | HR approves one appraisal run in a pilot team |

**Open questions**

- [ ] SSO provider and whether password sign-in stays after the pilot.
- [ ] Which HR system exports attendance, and its exact columns (Keka, Darwinbox, other).
- [ ] Is the 3-day office policy the same for every team and location?
- [ ] Should managers be able to upload attendance corrections for their own team, or HR only?
- [ ] Appraisal effective date and budget per department (defaults are 1 Oct and 12%).
- [ ] Retention period for 360° comments after an employee leaves.
