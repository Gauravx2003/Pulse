# UI audit

`frontend/e2e/ui-audit.spec.js` checks every screen the way a reviewer would on three devices. Run it
before you open a pull request that adds or changes a screen. It must pass.

```bash
mvn -f backend package -DskipTests      # the e2e backend runs the fat jar
cd frontend && npm run audit:ui         # also part of npm run test:e2e
```

It uses the same backend as the other e2e tests: port 8089, database `pulse_e2e`, demo seed on, today
pinned to 28 Sep 2026 (see docs/frontend-guide.md, "Tests").

## What it does

For each role (Admin `hr@`, Manager `rahul@`, Employee `priya@`) and each width (390, 1024 and 1440 px):

1. Signs in through the API with `signInViaApi` (`e2e/helpers.js`).
2. Opens every route in that user's menu from `GET /me`, moving inside the app the way a click does. It
   doesn't reload the page for each route, so the run stays under the per-user rate limit.
3. Waits until the network is quiet and no loading state is left.
4. Scans the page, then clicks each tab of the page and scans again.

The scan reports:

| Problem | What counts |
| --- | --- |
| `bad-value` | The literal word `NaN`, `undefined`, `null` or `Infinity` in visible text. Format missing values as "—". |
| `empty-card` | A card with a title and no body. Every card needs content or an empty-state line (`PlCard` `empty`, `PlEmpty`). |
| `overflow` | The page scrolls sideways. The report names the widest elements. Wide tables scroll inside `PlTable`. |
| `page-error` | An uncaught JavaScript error while the screen was open. |

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
