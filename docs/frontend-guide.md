# Frontend guide (shell and shared UI)

Track 1 owns the shell (`src/shell/`), the API client (`src/api/client.js`), the shared primitives (`src/ui/`) and the design-system wrappers (`src/kit/`). Tracks 2 and 3 build screens in their own `src/modules/<module>/` from these pieces and do not edit them.

## Registering a screen

`src/modules/<module>/index.js` default-exports `{ id, routes }`. The router finds it by glob, so you never edit `router.js`.

```js
export default {
  id: 'effort',
  routes: [
    // `screen` is the menu key from GET /me (platform/nav/Navigation.SCREENS)
    { path: '/effort', name: 'effort.home', component: () => import('./EffortView.vue'), meta: { screen: 'effort' } },
  ],
};
```

Route meta:

| Key | Meaning |
| --- | --- |
| `screen: '<menu key>'` | Allowed only when GET /me put that screen in the user's menu. |
| `permissions: ['a', 'b']` | Allowed when the user holds any of these permissions. |
| neither | Open to every signed-in user. |
| `public: true` | No session needed (sign-in only). |
| `bare: true` | Rendered without the side menu and top bar. |

A route the user may not open renders the "You don't have access" card in place of the screen. The screen never mounts, so it makes no request. The server still checks every call.

A screen-map path that no module serves yet shows a placeholder, with the shared period control where the screen has one. Your route replaces it as soon as it is registered.

## Calling the API

```js
import { api, ApiError } from '@/api/client.js';

const rows = await api.get('/effort/items', { query: { period: key.value } });
await api.put(`/okrs/items/${id}`, { ...body, version });
```

- The client adds the CSRF header on writes and sends the session cookie.
- On 401 it sends the user to sign in and returns them to the page afterwards.
- On 403, 409 and 429 it shows a toast. The call still rejects with an `ApiError` (`status`, `code`, `message`, `field`, `currentVersion`, `retryAfter`), so the screen can show inline errors too. Pass `{ quiet: true }` to handle the error yourself without the toast.

## Shared state

```js
import { useSharedPeriod, useAttendancePeriod, openPeriod } from '@/shell/period.js';
import { personState } from '@/shell/person.js';

const { key, periods, kinds } = useSharedPeriod(['sprint', 'month']);
// <PlPeriodControl v-model="key" :kinds="kinds" :periods="periods" />
// history row: openPeriod(router, '/bugs-support', 's:S42')
```

- **Period.** The sprint, month or release chosen on one screen carries to every screen that offers that kind. Attendance uses `useAttendancePeriod` and keeps its own choice. The choices come from `GET /api/v1/periods`, with the server's labels ("(active)", "(to date)").
- **Person.** `personState.id` is the person picked in the top-bar selector, or `null` for the signed-in user. The selector lists `GET /api/v1/people`, which is the server-side policy scope.
- Both reset at sign-in and sign-out. To register your own per-session state, call `onViewReset(fn)` from `src/shell/viewState.js`.

## Notifications and menu badges

The bell and the menu badges come from `GET /api/v1/notifications` and `GET /api/v1/notifications/badges`
(`src/shell/notifications.js`). The shell refreshes them when it mounts, on navigation (at most every
15 s) and every minute. After an action that clears a badge (you verify effort or submit a review),
call `refreshNotifications()` so the count updates at once. Badge counts are served by the backend's
`BadgeProvider`s (docs/backend-guide.md §7a). Never count them in the UI.

A notification's `link` is an in-app path such as `/reviews?tab=360&request=<id>`. Your screen should
read its query and open that tab or form.

## Dates and times

`src/ui/format.js`: `formatDate` ("28 Sep"), `formatDateWithYear` ("28 Sep 2026") and `formatDateTime`
("28 Sep, 09:05"). They always use Asia/Kolkata and the 24-hour clock, and show "—" for a missing value.
Don't use `toLocaleString`, which follows the browser's zone and its 12-hour locale.

## Primitives (`@/ui`)

`PlPageHeader`, `PlKpiRow`, `PlCard` (with `loading` and `empty`), `PlTable` (scrolls inside its card and shows "—" for missing values), `PlTabs`, `PlPeriodControl`, `PlModal`, `PlDrawer`, `PlConfirm` (for destructive actions), `PlAlert`, `PlEmpty`, `PlLoading`, `PlField`, `PlFormError` and `showToast(text)`.

- Modals and drawers trap focus, close on Esc and return focus to the opener.
- `/kit/primitives` renders every primitive and `/kit` renders the design-system components.
- Style only with tokens. Hex colors fail lint.

## Tests

```bash
mvn -f backend package -DskipTests   # the e2e backend runs the fat jar
cd frontend && npm run test:e2e
```

- The run starts its own Vite server on port 5174 and its own backend on port 8089, against the database `pulse_e2e`, which it creates on first use.
- The demo seed is on and today is pinned to 28 Sep 2026.
- Sign in as any demo account with `signIn(page, 'priya@pulse.demo')` from `e2e/helpers.js`, or use `signInViaApi` for tests that visit many pages.
- Run the UI audit (`npm run audit:ui`, docs/ui-audit.md) before you open a pull request that touches a screen.
- `e2e/db.js` runs SQL against the e2e database, to reset state an earlier run changed. Use it only for set-up.
