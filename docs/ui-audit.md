# UI audit

`frontend/e2e/ui-audit.spec.js` checks every screen the way a reviewer would on three devices. Run it
before you open a pull request that adds or changes a screen. It must pass.

```bash
mvn -f backend package -DskipTests      # the e2e backend runs the fat jar
cd frontend && npm run audit:ui         # three roles; also part of npm run test:e2e
cd frontend && npm run audit:full       # release check (task 23.4): every demo account, production build
```

It uses the same backend as the other e2e tests: port 8089, database `pulse_e2e`, demo seed on, today
pinned to 28 Sep 2026 (see docs/frontend-guide.md, "Tests").

## What it does

For each account (by default Admin `hr@`, Manager `rahul@`, Employee `priya@`; `audit:full` runs all 15
demo accounts) and each width (390, 1024 and 1440 px):

1. Signs in through the API with `signInViaApi` (`e2e/helpers.js`).
2. Opens every route in that user's menu from `GET /me`, moving inside the app the way a click does. It
   doesn't reload the page for each route, so the run stays under the per-user rate limit.
3. Waits until the network is quiet and no loading state is left.
4. Scans the page, then moves to each tab of the page with the arrow keys and scans again.

The scan reports:

| Problem | What counts |
| --- | --- |
| `bad-value` | The literal word `NaN`, `undefined`, `null` or `Infinity` in visible text. Format missing values as "—". |
| `empty-card` | A card with a title and no body. Every card needs content or an empty-state line (`PlCard` `empty`, `PlEmpty`). |
| `overflow` | The page scrolls sideways. The report names the widest elements. Wide tables scroll inside `PlTable`. |
| `page-error` | An uncaught JavaScript error while the screen was open, or an API call that answered 429 or 5xx. |
| `axe` | An axe-core violation of WCAG 2.1 A or AA on the screen, on each tab, and on each dialog it opens. |
| `keyboard` | See below. |

### Keyboard pass

On every screen and tab the audit also works the page with the keyboard only:

- Tab from the top of the document reaches every visible control of the content, and each focused
  control shows a focus ring. Members of a tab list are reached with the arrow keys instead (roving
  tabindex), so a `role="tab"` group must handle ArrowLeft, ArrowRight, Home and End like `PlTabs`.
- No element is clickable without being focusable (a pointer cursor on something that is not a control).
- Each distinct button is pressed with Enter (the buttons of table rows once per verb, e.g. one "Edit").
  When it opens a dialog, focus must move into it, Tab and Shift+Tab must stay inside it and reach every
  control, a text field must take typing, Esc must close it, and focus must return to the button. The
  dialog also gets its own axe scan.

While the keyboard pass runs, every API request that is not a GET is aborted, so pressing a button can
never change data. Buttons named "Sign out" are never pressed.

### First screen after sign-in

One test per account signs in through the sign-in screen with the keyboard only (type, Tab, type, Enter)
and fails when the first screen (shell visible, no loading state, page heading shown) takes 2 s or more.
It warms the web server first in a throwaway context, then times a fresh browser. Time it on the
production build (`E2E_PREVIEW_DIR`, which `audit:full` sets): the dev server compiles modules on demand
and measures itself, not the app.

A screen that still shows the shell placeholder ("This screen is being built.") is skipped and listed in
the test's annotations, so the audit covers each screen as soon as its module lands. Each run also
attaches `ui-audit.json` (audited routes, skipped routes, issues) to the test report.

## Options

Set these environment variables to narrow or widen a run:

| Variable | Default | Example |
| --- | --- | --- |
| `AUDIT_ACCOUNTS` | `admin,manager,employee` | `AUDIT_ACCOUNTS=rohit@pulse.demo,sana@pulse.demo` (any demo account) |
| `AUDIT_ROUTES` | every menu route | `AUDIT_ROUTES=/users,/rules` (path prefixes) |
| `AUDIT_EXTRA` | none | `AUDIT_EXTRA=/kit,/kit/primitives` (routes outside the menu) |
| `AUDIT_WIDTHS` | `390,1024,1440` | `AUDIT_WIDTHS=390` |
| `AUDIT_AXE` | on | `AUDIT_AXE=0` skips the axe scans |
| `AUDIT_KEYBOARD` | on | `AUDIT_KEYBOARD=0` skips the keyboard pass |

`AUDIT_ACCOUNTS=all` audits every demo account. These variables of `playwright.config.js` and
`e2e/start-backend.sh` apply to every e2e run:

| Variable | Default | Use |
| --- | --- | --- |
| `E2E_WEB_PORT`, `E2E_API_PORT` | 5174, 8089 | Run a second suite beside one already running, on its own servers |
| `PULSE_E2E_DB` | `pulse_e2e` | Its own database (created on first use, seeded on start) |
| `PULSE_E2E_JAR` | `backend/target/…-fat.jar` | A copy of the jar, so a rebuild elsewhere can't stop this backend |
| `E2E_PREVIEW_DIR` | unset (dev server) | Build into this directory and serve it with `vite preview` |

```bash
# the release audit beside an e2e run already using 5174/8089
cp ../backend/target/pulse-backend-1.0.0-SNAPSHOT-fat.jar /tmp/audit.jar
E2E_WEB_PORT=5175 E2E_API_PORT=8090 PULSE_E2E_DB=pulse_audit PULSE_E2E_JAR=/tmp/audit.jar \
  npm run audit:full -- --output=/tmp/audit-results
```

Pass a separate `--output` when another run is going: both would write `test-results/` otherwise.

```bash
AUDIT_ROUTES=/effort AUDIT_WIDTHS=390 npm run audit:ui
```

## Signing in as any demo account in your own tests

```js
import { DEMO, DEMO_ACCOUNTS, signIn, signInViaApi } from './helpers.js';

await signIn(page, DEMO.manager);          // through the sign-in screen
await signIn(page, 'rohit@pulse.demo');    // any of DEMO_ACCOUNTS, password demo123
await signInViaApi(page, 'employee');      // faster: API sign-in, returns the GET /me body
```

Use `signIn` when the test is about signing in or the shell. Use `signInViaApi` when the test visits many
pages. Each test context gets its own client IP (`distinctIp`), so the sign-in throttle doesn't trip.

## Track 1 status

The audit passes on the shell and the Track 1 screens (Users, Roles & permissions, Rules & thresholds,
Integrations, the notification panel and the sample module) for all three roles at all three widths. Its
first run found two problems, both now fixed:

- The Integrations tiles overflowed by 4 px at 390 px.
- The sample module left a request open, so the page never went quiet.

## Release audit (task 23.4)

`npm run audit:full` passes: 62 tests, 462 screen visits (every menu route and tab of the 15 demo
accounts at 390, 1024 and 1440 px) with no bad value, empty card, overflow, page error, axe violation or
keyboard problem. The first screen after sign-in renders in 380–641 ms on the production build for every
account (slowest: the Action center for Admin and Manager). The run found and fixed:

- Roles & permissions: the "What each role sees" card had no loading or empty state, so it was blank
  while the roles loaded or after a failed load.
- Effort, Daily log: `role="grid"` without rows or cells (axe `aria-required-children`); now a labelled
  list with a readable label per day. The day numbers were dimmed with `opacity` below 4.5:1 contrast.
- Attendance calendar: the same dimmed day numbers, and the padding days outside the period at 25 %
  opacity; those now show an empty cell.
- Training, My training: the plan picker used `role="tab"` without roving tabindex or arrow keys.
