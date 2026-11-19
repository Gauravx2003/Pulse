# Pulse — development handoff

Pulse is an employee performance and growth system for engineering teams. This package contains everything needed to build it: the product and development specification, the clickable prototype (HTML, CSS, JS) and the design system.

Handoff date: 1 Oct 2026 · Owner: Ranjit Sindhav · Prototype v11 · Design system v30

## What is in this package

| Folder | Contents | Use it for |
|---|---|---|
| `1-spec/` | Product & Development Specification as `.docx`, `.pdf` and `.md` | The build reference: roles and permissions, screen map, data model, integrations, every feature with its screen detail (fields, columns, buttons, modals, messages), all formulas and defaults, non-functional requirements, API outline, acceptance criteria, test plan, delivery phases, open questions |
| `2-prototype/Pulse-prototype.html` | The whole prototype in one file (works offline, fonts load from Google Fonts) | Clicking through every flow; demoing to stakeholders |
| `2-prototype/src/` | The same prototype split into `index.html`, `tokens.css`, `components.css`, `app.css`, `app.js` | Reading the UI code, lifting markup and styles |
| `3-design-system/` | `tokens.json`, `components/` (bundle.css plus a previews and READMEs per component), `guidelines/` (19 module guides), `README.md` | Building the real UI with the same tokens, components and rules |

## Running the prototype

Open `2-prototype/Pulse-prototype.html` (or `2-prototype/src/index.html`) in a browser. No server or build step is needed.

Demo accounts (password `demo123`):

| Account | Role | Shows |
|---|---|---|
| `hr@pulse.demo` | Admin (HR) | Everything, including Integrations, Rules, Data imports, appraisal approval, attendance upload |
| `rahul@pulse.demo` | Manager | Action center, team screens, reviews, training, appraisal proposals |
| `priya@pulse.demo` | Employee | Own data on every screen |
| `arjun@`, `neha@`, `kiran@`, `dev@`, `sara@` | Employees | Varied data; Sara is a mentor, Neha and Kiran have training actions, Dev has a sign-off due |
| `rohit@`, `anjali@` | Employees (new joiners) | Trainees on training plans; there is no trainee role |
| `sana@` | Employee (account manager) | Customer-visit feedback to record |
| `meera@` | Employee (Product) | Cross-department 360° reviewer |

Data is sample data held in memory; a reload resets it. "Today" in the prototype is 28 Sep 2026.

## How the prototype code is organized

- `app.js` is plain JavaScript with no framework. One state object `S`, a `render()` that redraws the page, `go(route)` for navigation, and event delegation on `data-act` / `data-*` attributes.
- `PAGES` maps each route to its title and render function (for example `attendance: ['Attendance', pAttendance]`). `menuFor(perms)` builds the menu from role permissions; `PERMS` and `ROLES` define access.
- Module code blocks inside `app.js`: users / roles / OKRs, effort, quality (bugs & support), 360° reviews, appraisal, training and mentoring, customer visits, pull requests (TFS / GitHub), period model (sprint / month / release), Action center and Progress, culture value calculation, attendance, data imports.
- Every formula in spec section 12 has a matching function (for example `index()`, `cult()`, `valData()`, `discMetrics()`, `attStats()`, `trRating()`, `aprRecommend()`). Use them as the reference implementation for the calculation service's unit tests.
- Treat the prototype as a behavioural reference, not production code: there is no backend, no persistence and no real authentication.

## Build notes for the team

1. **One calculation service.** Every number must come from one service so it matches on every screen (spec sections 4 and 12).
2. **Server-side permissions.** Hiding a button is not security. Check permission and visibility on every API call, especially salary, 360° anonymity and attendance (spec sections 2 and 13).
3. **Period model first.** Sprint, month, release and quarter keys, plus the working-day calendar (holidays, leave, visits, joining date), are used by almost every screen.
4. **Imports go check → commit → undo,** with a check id; nothing is saved before commit (spec section 11.5).
5. **Design tokens only.** Use the token names in `tokens.json` for color, spacing, radius and type; light and dark themes come for free.
6. **Acceptance.** Each module's acceptance criteria and the full UI audit (no NaN, undefined, empty cards or horizontal overflow at 390 / 1024 / 1440 px) are in spec section 15.

## Open decisions (also in spec section 15)

- SSO provider, and whether password sign-in stays after the pilot.
- Which HR system exports attendance, and its exact columns.
- Whether the 3-day office policy is the same for every team and location.
- Whether managers may upload attendance corrections, or HR only.
- Appraisal effective date and budget per department (defaults: 1 Oct and 12%).
- Retention of 360° comments after an employee leaves.
- Whether attendance should move earlier than delivery phase 4, since Discipline rule h depends on it.

## Live versions

The specification document, the hosted prototype and the design system are also kept as living artifacts in Claude. If anything changes there after this date, the live versions are newer than this package.
