/**
 * A signed-in session served by Playwright instead of the backend, for UI tests that only need the
 * shell (the primitives gallery). Shell tests against the real backend live in shell.spec.js.
 */
const ME = {
  user: { id: 'u1', name: 'Priya Shah', email: 'priya@pulse.demo', personId: 'p1' },
  role: 'Employee',
  permissions: ['self.view'],
  menu: [
    {
      group: 'My work',
      items: [
        { key: 'overview', label: 'Overview', path: '/overview', group: 'My work', order: 10 },
        { key: 'effort', label: 'Effort', path: '/effort', group: 'My work', order: 50 },
      ],
    },
  ],
  csrfToken: 'csrf-test',
  landing: '/overview',
};

const PERIODS = {
  today: '2026-09-28',
  activeSprint: 's:S43',
  sprints: [{ key: 's:S43', label: 'Sprint 43 (active)', live: true }],
  months: [{ key: 'm:2026-09', label: 'September 2026 (to date)', live: true }],
  releases: [],
};

export async function mockSignedIn(page) {
  await page.route('**/api/v1/**', (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '');
    const bodies = {
      '/me': ME,
      '/periods': PERIODS,
      '/people': [{ id: 'p1', name: 'Priya Shah' }],
      '/sync-status': { tool: 'jira', state: 'synced', lastSyncedAt: new Date().toISOString() },
    };

    if (path in bodies) {
      return route.fulfill({ json: bodies[path] });
    }

    return route.fulfill({ status: 404, json: { error: { code: 'not_found', message: 'Not found' } } });
  });
}
