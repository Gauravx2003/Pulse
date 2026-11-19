Pulse is the UI for employee performance, growth road maps and live workload. It pulls sprint work from Jira (and other tools), turns it into utilization and freshness signals, and puts them next to the ten DO culture values so a manager and an employee see one honest picture.

## Principles

- **Signals, not verdicts.** Every number is a prompt for a conversation in a 1:1, never an automatic rating. Copy says "4 issues have no update in 2+ working days", not "Priya is slow".
- **Always show the reference.** A value is never shown alone: utilization shows capacity, stale count shows open count, a score shows the rater mix.
- **Words carry meaning, color supports.** Every band and status is written as a word in a `Badge`; color is the second cue.
- **Transparent by default.** The employee sees exactly what their manager sees about them, including which tools feed each number.

## Content fundamentals

- Voice: calm, factual, specific. Second person to the employee ("You have 3 stale issues"), third person in manager views ("Priya has 3 stale issues").
- Sentence case everywhere except `eyebrow` labels, which are uppercase.
- Time is relative and in **working days** ("3 working days ago"); absolute timestamps in tooltips as `26 Sep 2026, 14:05 IST`.
- Numbers: one decimal for days and scores (`11.5 d`, `3.9 / 5`), whole numbers for percents (`128%`). Units always shown.
- Suggestions are concrete and reversible: "Suggest moving PLAT-2198 (8 SP) to Sprint 43", never "Reduce workload".
- No emoji, no exclamation marks, no rankings of people against each other on employee-facing screens.

## Visual foundations

**Color.** Neutral-first. Page on `surface`; cards on `surface-raised` with a 1px `border`; tracks, table heads and inactive tabs on `surface-sunken`. Text in `ink`, secondary text in `ink-muted`. `brand` (Pulse teal) is the single action and selection color: primary buttons (`on-brand` text), the selected nav item (`brand-soft` ground + `brand-ink` text), the employee's chart series. Status colors are reserved for bands and signals:

| Meaning | Fill / mark | Ground | Text |
|---|---|---|---|
| Under-utilized (< 60%), In Progress | `info` | `info-soft` | `info-ink` |
| Healthy (60–85%), Done, Connected | `ok` | `ok-soft` | `ok-ink` |
| At capacity (85–100%), Stale | `accent` | `accent-soft` | `accent-ink` |
| Over (100–120%) | `warn` | `warn-soft` | `warn-ink` |
| Critical (> 120%), Overdue, Sync failed | `danger` | `danger-soft` | `danger-ink` |

Never use `accent`, `warn`, `ok`, `info` or `danger` fills for text; use their `-ink` token. All `-ink` tokens hold ≥ 5.5:1 on their `-soft` ground and on `surface-raised` in both themes.

**Type.** IBM Plex Sans for UI (`body` 14/20 default), IBM Plex Mono for every number, issue key and date in a table (`metric`, `metric-sm`, `tabular`) so columns align. Headings `h1`–`h3` are 600 weight. `eyebrow` (11px, 600, +0.06em, uppercase) labels every metric.

**Spacing & layout.** 4px base. Card padding `space-5`, gutters `space-4`, page padding `space-6`, sections `space-8` apart. Desktop: 220px side nav + fluid main, KPI row of 4–5 `StatTile`s. At < 900px the nav collapses to a top bar and KPI tiles wrap two per row.

**Radii.** `radius-sm` for meters, heatmap cells and chips; `radius-md` for buttons, inputs, tabs, integration tiles; `radius-lg` for cards; `radius-pill` for badges and avatars.

**Borders & shadows.** Cards are separated by `border`, not shadow. `shadow-pop` only on popovers, menus and the task drawer.

**States & focus.** Hover darkens a fill to `brand-hover` or a neutral to `surface-sunken`. Focus is a solid 2px `focus` ring with 2px offset on every control (≥ 3:1 on every surface). Disabled controls drop to `ink-muted` text and keep their border.

**Data display.** Utilization uses `UtilizationMeter` (bar + 100% mark) or `TeamHeatmap`; culture values use the radar in `CultureValues` with at most two series; trends use a single `brand` line with the comparison in dashed `ink-muted`. Every chart prints its values.

**Motion.** 150ms ease-out on hover/press and drawer open; meters animate width once on load. No looping motion.

## Iconography

Pulse ships no icon set yet. Use **Lucide** at 16px / 1.5px stroke in `ink-muted` (20px in nav) — a substitute until a brand icon set exists. Integration tiles use a two-letter monogram in `tabular`, not vendor logos; use official logos only from each vendor's brand kit.

## Screens

- **Action center** (managers and Admin, their landing page): one prioritized *Do today* list from every module, people signals (early warnings with a suggested next step), current-sprint forecast, next-sprint capacity planner, skills coverage (single points of failure), team pulse, team wins and a weekly digest.
- **Progress** (everyone): next steps from every screen, achievements, index and utilization trends, culture change, OKRs / PRs / training, weekly pulse check-in, team wins.
- Overview, Work items and Team utilization can be viewed by **sprint, month or release**, with sprint history of issues and utilization by sprint.
- **Employee overview** (`EmployeeDashboard`): KPIs → overload alert → current sprint work → culture values → road map + performance index → integrations.
- **Manager team view** (`TeamView`): team KPIs → `TeamHeatmap` → stale-work and culture tabs.
- **Effort**: hours and days worked per sprint or per month from Jira worklogs and GitHub, a daily log calendar, unplanned and extra work with who gave it, and extra-mile points that credit culture values after manager confirmation.
- **Pull requests**: PRs from Microsoft TFS (Azure DevOps Server) and GitHub, checked against the PR standard (title pattern, work item, description template, size, approvals, build, blocking threads) and the review comment standard (conventional comments with a reason); person and team views, suggested rewrites, rubber-stamp approvals.
- **Attendance & leave**: HR uploads a monthly CSV (office, WFH, on duty, leave, check-in times). Pulse shows a team and person view by month, sprint or quarter, takes leave out of capacity, and feeds Discipline rule h and Collaboration (office presence). Sick and approved leave never count against anyone.
- **Data imports**: CSV upload for OKRs, attendance, users, customer visits and holidays. Every file goes through choose → check → import, then can be undone. The screen also has exports and a map of where each module gets its data.
- **Customer visits**: engineers who go on site to customers — plan or log a visit (days leave sprint capacity), visit report, customer feedback (CSAT and quote) recorded by the account owner, manager verification, extra-mile points and culture credit, and a comparison with the team average.
- **Bugs & support** (per sprint or month): open bugs traced to each person's own code, bugs fixed for others (proactive or assigned), extra support tickets with hours and CSAT, and a team view.
- **Reviews** — one menu item with three tabs: **Performance review** (index, breakdown, trend, calibration, submit), **1:1s & feedback** (agenda from signals, shared notes, action items, kudos) and **360° feedback** (cycles where the manager assigns reviewers (self, manager, internal team, other departments) for many people at once and each reviewer gets an in-app notification; a rating form per value; anonymous grouped results with hidden strengths and blind spots; publishing feeds culture values). The Reviews item shows a count of 360° reviews waiting for you.
- **Training plans**: plan library (create, edit, duplicate, delete, pass mark and assessment type), assign one or several plans to one or many people with a mentor (and how often they meet) and a set training period, a Mentoring view for mentors (day-to-day sessions, check-ins to verify, assessments and ratings pending, activity) and a Mentors workload table for managers, week-by-week modules, daily / weekly / monthly check-ins verified by the mentor or manager, checkpoint reviews, an assessment after the period, mentor and manager final reviews with a decision (completed, extend, not completed), trainee acknowledgement of every feedback item and a final sign-off where the trainee also rates the training, and a 1–5 training rating per plan and per person.
- **Road map**, **Culture values**.
- **Appraisal & increments** (managers and HR only): yearly appraisal score from performance index, 360° feedback, manager review and Discipline; score bands map to increments up to a 20% cap within a payroll budget; draft → submit → HR approval → release letters.
- **OKRs**: create, edit, check in and delete objectives and key results, per person or team, with a required quarter dropdown (Q1–Q4).
- **Sign in**, **Users** (add / edit / deactivate / delete), **Roles & permissions** (permission matrix, custom roles), **Integrations**, **Rules & thresholds**. The side menu and every screen are shown from the signed-in user's role.

Further sections define the metrics and formulas, other performance approaches, how to measure the 11 culture values (including Discipline), the integrations, monthly effort and extra-mile points, OKRs, access control, bugs & support, 360° reviews, pull requests and the PR standard, customer visits, attendance and leave, and data imports.
