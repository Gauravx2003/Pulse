import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { sql } from './db.js';
import { DEMO, expectNoHorizontalScroll, signIn } from './helpers.js';

/**
 * Task 24.7 (change pulse-v2-live-connectors): the generic Integrations frontend for live connectors.
 *
 * The form, tiles and Sync now are checked with a stub connector the frontend has never heard of:
 * Playwright adds three stub tools to the real tiles and answers their live routes with a schema that
 * uses every supported field type (text, secret, URL, list, date, boolean, and a choice that shows or
 * hides a dependent field). If the form renders and submits for it, no frontend change is needed for
 * a new connector. The Unmatched accounts panel runs against the real backend (Jira registers a live
 * connector), with an account inserted for this run.
 */

const card = (page, name) => page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });
const toast = (page, text) => page.locator('.pl-toast').filter({ hasText: text });
const unique = () => `${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}`;

const SCHEMA = {
  title: 'E2E Stub',
  fields: [
    { name: 'baseUrl', label: 'Base URL', type: 'url', required: true, help: 'The server address.' },
    {
      name: 'authMode',
      label: 'Authentication',
      type: 'choice',
      required: true,
      default: 'basic',
      options: [
        { value: 'basic', label: 'Username and password' },
        { value: 'bearer', label: 'Personal access token' },
      ],
    },
    { name: 'username', label: 'Username', type: 'text', required: false, dependsOn: { field: 'authMode', value: 'basic' } },
    { name: 'projectKeys', label: 'Project keys', type: 'list', required: true, pattern: '[A-Z][A-Z0-9]+' },
    { name: 'storyPointsField', label: 'Story points field', type: 'text', required: false, pattern: 'customfield_[0-9]+' },
    { name: 'since', label: 'Read changes since', type: 'date', required: false },
    { name: 'ignoreBots', label: 'Ignore bot edits', type: 'boolean', required: false, default: true },
  ],
};

const SECRET_SPEC = { name: 'secret', label: 'Token', help: 'A read-only token.' };

const STORED = {
  config: { baseUrl: 'https://stub.e2e.test', authMode: 'bearer', projectKeys: ['PUL'], ignoreBots: true },
  last4: '9f3a',
  hasSecret: true,
  expiresAt: null,
  state: 'connected',
  rotatedAt: '2026-09-20T10:00:00Z',
};

const base = { reads: 'Stub items', feeds: 'Nothing (e2e)', methods: [], live: true, liveConnector: true };

const TILES = {
  e2estub: { ...base, id: 'e2estub', mark: 'ES', name: 'E2E Stub', connected: false, state: 'not_connected', status: 'Not connected', action: 'connect', connectEnabled: true, note: null },
  e2elive: { ...base, id: 'e2elive', mark: 'EL', name: 'E2E Live', connected: true, syncing: false, state: 'connected', status: 'Connected · synced just now', action: 'settings' },
  e2efail: { ...base, id: 'e2efail', mark: 'EF', name: 'E2E Fail', connected: true, syncing: false, state: 'sync_failed', status: 'Sync failed · 500', action: 'fix' },
};

/** Serves the stub tools; `calls` records the live requests the page made. */
async function stubTools(page, { testAnswers = [], syncDelayMs = 0 } = {}) {
  const calls = { test: [], save: [], sync: [] };
  const answers = [...testAnswers];

  await page.route(/\/api\/v1\/integrations\/tools$/, async (route) => {
    const res = await route.fetch();
    const json = await res.json();

    json.tiles = [...(json.tiles ?? []), ...Object.values(TILES)];
    await route.fulfill({ response: res, json });
  });

  await page.route(/\/api\/v1\/integrations\/tools\/e2e(stub|live|fail)$/, (route) => {
    const id = route.request().url().split('/').pop();

    return route.fulfill({
      json: { tile: TILES[id], methods: [], credentials: {}, scopes: [], syncMode: 'hourly', syncModes: ['hourly'], syncLog: { items: [] } },
    });
  });

  await page.route(/\/api\/v1\/integrations\/e2e(stub|live|fail)\/(connection|test|sync)$/, async (route) => {
    const req = route.request();
    const [, tool, action] = new URL(req.url()).pathname.match(/integrations\/(e2e\w+)\/(\w+)$/);

    if (action === 'connection' && req.method() === 'GET') {
      return route.fulfill({
        json: { tool, source: tool, configSchema: SCHEMA, secretSpec: SECRET_SPEC, requiredScopes: ['read'], connection: tool === 'e2estub' ? null : STORED },
      });
    }

    if (action === 'test') {
      calls.test.push(req.postDataJSON());

      return route.fulfill({ json: answers.shift() ?? { ok: false, status: 500, hint: 'No answer queued.' } });
    }

    if (action === 'connection' && req.method() === 'PUT') {
      calls.save.push(req.postDataJSON());

      return route.fulfill({ json: { tool, configSchema: SCHEMA, secretSpec: SECRET_SPEC, connection: STORED } });
    }

    if (action === 'sync') {
      calls.sync.push(tool);
      await new Promise((r) => setTimeout(r, syncDelayMs));

      return route.fulfill({ json: { sync: { outcome: 'success', message: '4 items' }, tile: TILES[tool] } });
    }

    return route.fulfill({ status: 404, json: { error: { code: 'not_found', message: 'Not found' } } });
  });

  return calls;
}

const PASS = (proof) => ({
  ok: true,
  status: 200,
  code: null,
  account: { username: 'svc.pulse', displayName: 'Pulse Service' },
  scopes: [],
  scopeNote: 'This tool reports no scopes: use a read-only user.',
  fields: { storyPointsField: [{ id: 'customfield_10106', name: 'Story Points' }] },
  expiresAt: '2026-12-31',
  hint: null,
  warnings: [{ field: 'baseUrl', code: 'insecure_http', message: '`http` sends credentials unencrypted' }],
  proof,
});

const FAIL_401 = { ok: false, status: 401, code: '401', account: null, scopes: [], fields: {}, hint: 'Check the token.', warnings: [] };

async function axe(page, include) {
  const result = await new AxeBuilder({ page }).include(include).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();

  return result.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.target.join(' ')).join(' | ')})`);
}

/** Presses Tab until the focused element has `name` as its accessible text; fails after 40 presses. */
async function tabTo(page, name) {
  for (let i = 0; i < 40; i += 1) {
    await page.keyboard.press('Tab');

    const label = await page.evaluate(() => {
      const el = document.activeElement;
      const label = el?.labels?.[0]?.textContent?.replace(/\*\s*$/, '');

      return (el?.getAttribute('aria-label') || label || el?.textContent || '').trim();
    });

    if (label === name) {
      return;
    }
  }

  throw new Error(`Tab never reached "${name}"`);
}


// the page reloads its tiles after a save or sync; a reload still in flight when a test ends is fine
test.afterEach(async ({ page }) => {
  await page.unrouteAll({ behavior: 'ignoreErrors' });
});

test.describe('Live connectors · generic form and tiles', () => {
  test('a stub connector form renders from its schema, tests and saves with no frontend change', async ({ page }) => {
    const calls = await stubTools(page, { testAnswers: [FAIL_401, PASS('proof-1'), PASS('proof-2')] });

    await signIn(page, DEMO.admin);
    await page.goto('/integrations');
    await page.getByRole('button', { name: 'Connect E2E Stub' }).click();

    const form = page.getByRole('dialog', { name: 'Connect · E2E Stub' });
    const save = form.getByRole('button', { name: 'Save & connect' });
    const testBtn = form.getByRole('button', { name: 'Test connection' });

    // every declared field, by its own label; the dependent field follows the choice
    await expect(form.getByLabel('Base URL')).toHaveAttribute('type', 'url');
    await expect(form.getByLabel('Authentication')).toHaveValue('basic');
    await expect(form.getByLabel('Username')).toBeVisible();
    await expect(form.getByLabel('Project keys')).toBeVisible();
    await expect(form.getByLabel('Read changes since')).toHaveAttribute('type', 'date');
    await expect(form.getByRole('switch', { name: /Ignore bot edits/ })).toBeChecked();
    await expect(form.getByLabel('Token')).toHaveAttribute('type', 'password');
    await expect(form.getByLabel('Token')).toHaveValue('');
    await expect(save).toBeDisabled();

    await form.getByLabel('Authentication').selectOption('bearer');
    await expect(form.getByLabel('Username')).toHaveCount(0);

    // required fields are checked before any request
    await testBtn.click();
    await expect(form).toContainText('Base URL is required.');
    await expect(form).toContainText('Enter the token.');
    expect(calls.test).toHaveLength(0);

    await form.getByLabel('Base URL').fill('http://stub.e2e.test');
    await form.getByLabel('Project keys').fill('PUL, OPS, PUL');
    await form.getByLabel('Token').fill('tok-e2e-secret');

    // a failed test shows the status and the connector's hint; Save stays off
    await testBtn.click();

    const result = form.locator('[data-test="live-test-result"]');

    await expect(result).toContainText('Failed · 401');
    await expect(result).toContainText('Check the token.');
    await expect(save).toBeDisabled();

    // a pass shows the account, the scope note, the expiry and the warnings
    await testBtn.click();
    await expect(result).toContainText('Passed');
    await expect(result).toContainText('Pulse Service · svc.pulse');
    await expect(result).toContainText('This tool reports no scopes');
    await expect(result).toContainText(/Expires on .*2026/);
    await expect(result).toContainText('`http` sends credentials unencrypted');
    await expect(save).toBeEnabled();

    expect(calls.test[1]).toEqual({
      config: { baseUrl: 'http://stub.e2e.test', authMode: 'bearer', projectKeys: ['PUL', 'OPS'], ignoreBots: true },
      secret: 'tok-e2e-secret',
    });

    // confirming a proposed field changes the values: the earlier pass no longer allows Save
    await form.getByRole('button', { name: 'Use Story Points (customfield_10106) for Story points field' }).click();
    await expect(form.getByLabel('Story points field', { exact: true })).toHaveValue('customfield_10106');
    await expect(save).toBeDisabled();
    await expect(result).toContainText('Values changed since this test');

    await testBtn.click();
    await expect(save).toBeEnabled();
    await save.click();

    await expect(toast(page, 'Connected · E2E Stub')).toBeVisible();
    expect(calls.save).toHaveLength(1);
    expect(calls.save[0]).toEqual({
      config: {
        baseUrl: 'http://stub.e2e.test',
        authMode: 'bearer',
        projectKeys: ['PUL', 'OPS'],
        storyPointsField: 'customfield_10106',
        ignoreBots: true,
      },
      secret: 'tok-e2e-secret',
      proof: 'proof-2',
    });
  });

  test('live tiles show Live, Sync now disabled while it runs, and Fix opens the live form', async ({ page }) => {
    const calls = await stubTools(page, { syncDelayMs: 1200 });

    await signIn(page, DEMO.admin);
    await page.goto('/integrations');

    const live = page.locator('.pl-int').filter({ hasText: 'E2E Live' });

    await expect(live.locator('.pl-int-name')).toContainText('Live');
    await expect(live).toContainText('Connected · synced just now');

    const syncBtn = live.getByRole('button', { name: 'Sync E2E Live now' });

    await syncBtn.click();
    await expect(syncBtn).toBeDisabled();
    await expect(syncBtn).toHaveText('Syncing…');
    await expect(toast(page, 'E2E Live · synced')).toBeVisible();
    await expect(syncBtn).toBeEnabled();
    expect(calls.sync).toEqual(['e2elive']);

    const failed = page.locator('.pl-int').filter({ hasText: 'E2E Fail' });

    await expect(failed).toContainText('Sync failed · 500');
    await failed.getByRole('button', { name: 'Fix E2E Fail' }).click();

    const form = page.getByRole('dialog', { name: 'Change credentials · E2E Fail' });

    await expect(form.getByLabel('Base URL')).toHaveValue('https://stub.e2e.test');
    await expect(form.getByLabel('Authentication')).toHaveValue('bearer');
    await expect(form.getByLabel('Project keys')).toHaveValue('PUL');
    await expect(form.getByLabel('Token')).toHaveValue('');
    await expect(form).toContainText('Stored: ••••9f3a');
    await expect(form.getByRole('button', { name: 'Save new credentials' })).toBeDisabled();
    await form.getByRole('button', { name: 'Cancel' }).click();

    await live.getByRole('button', { name: 'Settings E2E Live' }).click();

    const settings = page.getByRole('dialog', { name: 'E2E Live' });

    await expect(settings).toContainText('https://stub.e2e.test');
    await expect(settings).toContainText('Personal access token');
    await expect(settings).toContainText('••••9f3a');

    await expectNoHorizontalScroll(page);
  });

  test('keyboard only: connect a stub tool through the modal', async ({ page }) => {
    const calls = await stubTools(page, { testAnswers: [PASS('proof-k')] });

    await signIn(page, DEMO.admin);
    await page.goto('/integrations');

    await page.getByRole('button', { name: 'Connect E2E Stub' }).focus();
    await page.keyboard.press('Enter');

    const form = page.getByRole('dialog', { name: 'Connect · E2E Stub' });

    await expect(form).toBeVisible();
    await form.getByLabel('Base URL').focus();
    await page.keyboard.type('https://stub.e2e.test');
    await page.keyboard.press('Tab');
    await page.keyboard.press('ArrowDown');
    await expect(form.getByLabel('Authentication')).toHaveValue('bearer');
    await page.keyboard.press('Tab');
    await expect(form.getByLabel('Project keys')).toBeFocused();
    await page.keyboard.type('PUL');
    await tabTo(page, 'Token');
    await page.keyboard.type('tok-keyboard');
    await tabTo(page, 'Test connection');
    await page.keyboard.press('Enter');
    await expect(form.locator('[data-test="live-test-result"]')).toContainText('Passed');
    await tabTo(page, 'Save & connect');
    await page.keyboard.press('Enter');
    await expect(toast(page, 'Connected · E2E Stub')).toBeVisible();
    expect(calls.save[0].proof).toBe('proof-k');
  });
});

test.describe('Live connectors · unmatched accounts', () => {
  // a fresh account per test (and per repeat), so the tests can run in parallel and again
  let login = '';
  let keyLogin = '';

  test.beforeEach(() => {
    const run = unique();

    login = `e2e.unmatched.${run}`;
    keyLogin = `e2e.keyboard.${run}`;
    sql(`INSERT INTO external_accounts (tool, tool_account_id, login, display_name)
         VALUES ('jira', '${login}', '${login}', 'E2E Unmatched'), ('jira', '${keyLogin}', '${keyLogin}', 'E2E Keyboard')`);
  });

  test.afterEach(() => {
    sql(`DELETE FROM external_accounts WHERE tool = 'jira' AND tool_account_id IN ('${login}', '${keyLogin}')`);
  });

  test('link to a person, unlink and mark as a bot, filtered by tool', async ({ page }) => {
    await signIn(page, DEMO.admin);
    await page.goto('/integrations');

    const panel = card(page, 'Unmatched accounts');

    await panel.getByRole('combobox', { name: 'Tool', exact: true }).selectOption({ label: 'Jira Software' });

    const row = panel.locator('tbody tr').filter({ hasText: login });

    await expect(row).toContainText('E2E Unmatched');
    await row.getByRole('button', { name: `Link ${login} to a person` }).click();

    const dialog = page.getByRole('dialog', { name: `Link ${login} to a person` });

    await dialog.getByLabel('Search people').fill('Priya Shah');
    await dialog.getByRole('radio', { name: /Priya Shah/ }).first().check();
    await dialog.getByRole('button', { name: 'Link', exact: true }).click();
    await expect(toast(page, `Linked · ${login} → Priya Shah`)).toBeVisible();
    await expect(row).toHaveCount(0);

    await panel.getByRole('combobox', { name: 'Show', exact: true }).selectOption('linked');
    await expect(row).toContainText('Priya Shah');
    await row.getByRole('button', { name: `Unlink ${login}` }).click();
    await expect(toast(page, `Unlinked · ${login}`)).toBeVisible();
    await expect(row).toHaveCount(0);

    await panel.getByRole('combobox', { name: 'Show', exact: true }).selectOption('unmatched');
    await expect(row).toHaveCount(1);
    await row.getByRole('switch', { name: `Bot: ${login}` }).check();
    await expect(toast(page, `${login} · marked as a bot`)).toBeVisible();
    await expect(row).toHaveCount(0);

    await panel.getByRole('combobox', { name: 'Show', exact: true }).selectOption('bot');
    await expect(row).toHaveCount(1);
    await row.getByRole('switch', { name: `Bot: ${login}` }).uncheck();
    await expect(toast(page, `${login} · no longer a bot`)).toBeVisible();
  });

  test('keyboard only: link an account from the panel', async ({ page }) => {
    await signIn(page, DEMO.admin);
    await page.goto('/integrations');

    const panel = card(page, 'Unmatched accounts');
    const row = panel.locator('tbody tr').filter({ hasText: keyLogin });

    await expect(row).toHaveCount(1);

    await row.getByRole('button', { name: `Link ${keyLogin} to a person` }).focus();
    await page.keyboard.press('Enter');

    const dialog = page.getByRole('dialog', { name: `Link ${keyLogin} to a person` });

    await expect(dialog).toBeVisible();
    await dialog.getByLabel('Search people').focus();
    await page.keyboard.type('Priya Shah');
    // wait for the search to answer: the first radio is then the person searched for
    await expect(dialog.getByRole('radio').first()).toHaveAccessibleName(/Priya Shah/);
    await page.keyboard.press('Tab');
    await page.keyboard.press('Space');
    await expect(dialog.getByRole('radio').first()).toBeChecked();
    await tabTo(page, 'Link');
    await page.keyboard.press('Enter');
    await expect(toast(page, `Linked · ${keyLogin} → Priya Shah`)).toBeVisible();
    await expect(row).toHaveCount(0);
  });
});

test.describe('Live connectors · accessibility', () => {
  // one sign-in for the three widths: every test here signs in as the same Admin, and the backend
  // limits requests per user per minute
  test('axe is clean on the page, the live form and the panel at 390, 1024 and 1440 px', async ({ page }) => {
    await stubTools(page, { testAnswers: [PASS('proof-a')] });
    await signIn(page, DEMO.admin);
    await page.goto('/integrations');
    await expect(card(page, 'Unmatched accounts')).toBeVisible();

    await page.getByRole('button', { name: 'Connect E2E Stub' }).click();

    const form = page.getByRole('dialog', { name: 'Connect · E2E Stub' });

    await form.getByLabel('Base URL').fill('http://stub.e2e.test');
    await form.getByLabel('Project keys').fill('PUL');
    await form.getByLabel('Token').fill('tok');
    await form.getByRole('button', { name: 'Test connection' }).click();
    await expect(form.locator('[data-test="live-test-result"]')).toContainText('Passed');

    for (const width of [390, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      expect(await axe(page, '[role="dialog"]'), `dialog at ${width} px`).toEqual([]);
      await expectNoHorizontalScroll(page);
    }

    await form.getByRole('button', { name: 'Cancel' }).click();
    await expect(form).toBeHidden();

    for (const width of [390, 1024, 1440]) {
      await page.setViewportSize({ width, height: 900 });
      expect(await axe(page, 'main'), `page at ${width} px`).toEqual([]);
      await expectNoHorizontalScroll(page);
    }
  });

  test('a Manager sees the no-access card on Integrations', async ({ page }) => {
    await signIn(page, DEMO.manager);
    await page.goto('/integrations');
    await expect(page.getByRole('heading', { name: "You don't have access" })).toBeVisible();
  });
});
