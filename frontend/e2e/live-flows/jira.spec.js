import { expect, test } from '@playwright/test';
import { sql } from '../db.js';
import { DEMO, distinctIp, signInViaApi } from '../helpers.js';
import { startMockTool } from './mockTool.js';

/**
 * Task 26.3 (change pulse-v2-live-connectors): the Jira Server flow end to end, against the real backend
 * and a mock Jira that serves live-fixtures/jira-e2e/ (run by playwright.live.config.js: a fresh database
 * with the demo seed, today 26 Nov 2026, inside Jira sprint 147).
 *
 *  1. Admin enters the base URL, username and password; the `http` warning shows.
 *  2. Test passes and proposes the field ids; Admin confirms them and saves.
 *  3. Sync now runs, and the issue appears with a Jira badge.
 *  4. Workload, Discipline, index and the appraisal draft score move, and every screen reads one number.
 *  5. An unmatched username is linked, and the person's figures update.
 *  7. A concurrent Sync now gets 409 (run before 6, while the connection is healthy).
 *  6. A 401 gives "Sync failed · 401", and Fix opens Change credentials.
 *  8. Disconnect keeps the rows.
 *
 * In the fixtures, priya.shah's Jira email is Priya@Pulse.Demo (matched to Priya in another letter case),
 * arjun.m has no email (unmatched until linked to Arjun), and a bot's label edit is the only activity on
 * PUL-205. One test, because each step builds on the state the previous one left.
 *
 * The demo seed holds the seeded index inputs (pillars Delivery, Quality, OKR, Growth, and the culture
 * ratings) up to 2026-Q3 only, and without them no performance index exists for 2026-Q4. Set-up copies
 * each person's Q3 inputs to Q4, so the index of 26 Nov is defined and moves with the Discipline that
 * Jira activity feeds.
 */
test.describe.configure({ mode: 'serial' });

const PASSWORD = 'e2e-Jira-pass-7Q';
const TOOL = 'Jira Software';
const PRIYA = 'Priya Shah';
const ARJUN = 'Arjun Mehta';

const h1 = (page, name) => page.getByRole('heading', { level: 1, name });
const card = (page, name) =>
  page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });
const toast = (page, text) => page.locator('.pl-toast').filter({ hasText: text });
const tile = (page) => page.locator('.pl-int').filter({ has: page.locator('.pl-int-name', { hasText: TOOL }) });
const stat = (group, eyebrow) =>
  group.locator('.pl-stat').filter({ has: group.page().locator('.pl-eyebrow', { hasText: eyebrow }) }).locator('.pl-stat-value');

/** The first number in a text, e.g. "3.62 / 5" → 3.62; null when there is none. */
function num(text) {
  const m = /-?\d+(?:\.\d+)?/.exec(text ?? '');

  return m ? Number(m[0]) : null;
}

let mock;
let csrf = '';

/** Signs in as `who` on a clean cookie jar and keeps the CSRF token for direct API calls. */
async function as(page, who) {
  if (page.url().startsWith('http')) {
    await page.evaluate(() => sessionStorage.clear());
  }

  await page.context().clearCookies();
  await distinctIp(page.context());

  csrf = (await signInViaApi(page, who)).csrfToken;
}

/** The cells of a person's row in Team utilization (Manager): stale, work days, utilization, Discipline, index. */
async function teamRow(page, name) {
  await page.goto('/team-utilization');

  const row = card(page, 'People').locator('tbody tr').filter({ hasText: name });

  await expect(row).toBeVisible();

  const [, , stale, work, , util, , discipline, index] = await row.locator('td').allInnerTexts();

  return { stale: num(stale), work: num(work), util: num(util), discipline: num(discipline), index: num(index) };
}

/** Priya's Discipline score on Culture values (as Priya). */
async function disciplineScore(page) {
  await page.goto('/culture?value=Discipline');

  const group = page
    .locator('section.pl-card')
    .filter({ has: page.getByRole('heading', { level: 2, name: /^Discipline · / }) })
    .getByRole('group', { name: 'Discipline' });

  return num(await stat(group, 'Discipline score').innerText());
}

/** Priya's live-quarter performance index on Reviews (as Priya); null when there is none. */
async function reviewsIndex(page) {
  await page.goto('/reviews?tab=performance');

  const indexCard = card(page, 'Performance index');

  await expect(indexCard.locator('.rv-index, .pl-empty').first()).toBeVisible();

  return (await indexCard.locator('.rv-index').count()) ? num(await indexCard.locator('.rv-index').innerText()) : null;
}

test.beforeAll(async () => {
  sql(`INSERT INTO pillars (person_id, quarter, delivery, quality, okr, growth)
       SELECT person_id, '2026-Q4', delivery, quality, okr, growth FROM pillars WHERE quarter = '2026-Q3'
       ON CONFLICT (person_id, quarter) DO NOTHING`);
  sql(`INSERT INTO culture_ratings (person_id, quarter, value_name, rater_group, rating)
       SELECT person_id, '2026-Q4', value_name, rater_group, rating FROM culture_ratings WHERE quarter = '2026-Q3'
       ON CONFLICT (person_id, quarter, value_name, rater_group) DO NOTHING`);

  mock = await startMockTool('jira-e2e');
});

test.afterAll(async () => {
  await mock?.close();
});

test('Jira Server: connect, sync, figures, link, 409, 401 and disconnect', async ({ page }) => {
  // Before any sync no sprint holds today: Team utilization falls back to the last closed seed sprint,
  // with no live People table.
  await as(page, DEMO.manager);
  await page.goto('/team-utilization');
  await expect(page.getByRole('heading', { level: 2, name: 'People · Sprint 43' })).toBeVisible();
  await expect(card(page, 'People')).toHaveCount(0);

  await as(page, DEMO.employee);

  const before = { discipline: await disciplineScore(page), index: await reviewsIndex(page) };

  await test.step('1 · Admin enters the base URL, username and password; the http warning shows', async () => {
    await as(page, DEMO.admin);
    await page.goto('/integrations');
    await tile(page).getByRole('button', { name: `Settings ${TOOL}` }).click();
    await page.getByRole('dialog', { name: TOOL }).getByRole('button', { name: 'Change credentials' }).click();

    const form = page.getByRole('dialog', { name: `Change credentials · ${TOOL}` });

    await expect(form.getByLabel('Authentication')).toHaveValue('basic');
    await form.getByLabel('Base URL').fill(mock.baseUrl);
    await form.getByLabel('Username').fill('pulse.readonly');
    await form.getByLabel('Project keys').fill('PUL');
    await form.getByLabel('Password / Token').fill(PASSWORD);
    await expect(form.getByLabel('Password / Token')).toHaveAttribute('type', 'password');

    await form.getByRole('button', { name: 'Test connection' }).click();

    const result = form.locator('[data-test="live-test-result"]');

    await expect(result).toContainText('Passed');
    await expect(form.getByRole('list', { name: 'Warnings' })).toContainText('http sends credentials and data unencrypted');
  });

  await test.step('2 · Test passes and proposes the field ids; Admin confirms them and saves', async () => {
    const form = page.getByRole('dialog', { name: `Change credentials · ${TOOL}` });
    const result = form.locator('[data-test="live-test-result"]');
    const save = form.getByRole('button', { name: /Save/ });

    await expect(result).toContainText('Pulse Read-only · pulse.readonly');

    await form.getByRole('button', { name: 'Use Story Points (customfield_10111) for Story points field' }).click();
    await form.getByRole('button', { name: 'Use Sprint (customfield_10108) for Sprint field' }).click();
    await expect(form.getByLabel('Story points field', { exact: true })).toHaveValue('customfield_10111');
    await expect(form.getByLabel('Sprint field', { exact: true })).toHaveValue('customfield_10108');

    // the values changed after the test: Save needs a new pass
    await expect(save).toBeDisabled();
    await form.getByRole('button', { name: 'Test connection' }).click();
    await expect(result).toContainText('Passed');
    await expect(save).toBeEnabled();
    await save.click();

    await expect(form).toBeHidden();
    await expect(tile(page).locator('.pl-int-name')).toContainText('Live');

    // Basic auth with the password the Admin typed, GET only, to the mock origin only
    const expected = `Basic ${Buffer.from(`pulse.readonly:${PASSWORD}`).toString('base64')}`;

    expect(mock.requests.map((r) => `${r.method} ${r.path}`)).toEqual([
      'GET /rest/api/2/myself',
      'GET /rest/api/2/field',
      'GET /rest/api/2/myself',
      'GET /rest/api/2/field',
    ]);
    expect(mock.requests.every((r) => r.authorization === expected)).toBe(true);
    mock.reset();
  });

  await test.step('3 · Sync now runs, and the issue appears with a Jira badge', async () => {
    await tile(page).getByRole('button', { name: `Sync ${TOOL} now` }).click();
    await expect(toast(page, `${TOOL} · synced`)).toBeVisible();
    await expect(tile(page)).toContainText('Connected');

    const search = mock.requests.find((r) => r.path === '/rest/api/2/search');

    expect(search.query.jql).toBe('project in ("PUL") ORDER BY updated ASC');
    expect(search.query.expand).toBe('changelog');

    // the bot and the hidden-email user are unmatched; Priya matched by email
    const panel = card(page, 'Unmatched accounts');

    await expect(panel.locator('tbody tr').filter({ hasText: 'arjun.m' })).toHaveCount(1);
    await expect(panel.locator('tbody tr').filter({ hasText: 'jira.automation' })).toHaveCount(1);
    await expect(panel.locator('tbody tr').filter({ hasText: 'priya.shah' })).toHaveCount(0);

    await as(page, DEMO.employee);
    await page.goto('/work-items');

    const row = page.locator('section.pl-card tbody tr').filter({ hasText: 'PUL-201' });

    await expect(row).toHaveCount(1);
    await expect(row.locator('.pl-badge').filter({ hasText: /^Jira$/ })).toBeVisible();
    await expect(row.getByRole('link', { name: /^Open in Jira PUL-201/ })).toHaveAttribute('href', `${mock.baseUrl}/browse/PUL-201`);
  });

  await test.step('4 · Workload, Discipline, index and the appraisal draft score move consistently', async () => {
    // Overview (Priya): the Jira sprint is the live sprint; 16 h + 6 h remaining over 6 focus hours a day,
    // and PUL-207 (no update since 23 Nov) is stale
    await page.goto('/overview');
    await expect(h1(page, PRIYA)).toBeVisible();

    const live = page.getByRole('group', { name: 'Live sprint' });
    const stale = num(await stat(live, 'No update > 2 days').innerText());
    const workDays = num(await stat(live, 'Work assigned').innerText());
    const utilization = num(await stat(live, 'Utilization').innerText());
    const index = num(await stat(live, 'Performance index').innerText());

    expect([stale, workDays, utilization, index]).not.toContain(null);
    expect(workDays).toBeCloseTo(22 / 6, 1);

    await page.goto('/work-items');
    await expect(
      page.getByRole('group', { name: 'Live sprint' }).locator('.pl-stat').filter({ hasText: 'Remaining' }).locator('.pl-stat-foot'),
    ).toContainText(`= ${workDays} days`);

    const discipline = await disciplineScore(page);

    expect(discipline).not.toBeNull();
    expect(await reviewsIndex(page), 'Reviews reads the Overview index').toBeCloseTo(index, 1);
    expect(
      discipline !== before.discipline || index !== before.index,
      `Discipline ${before.discipline} → ${discipline}, index ${before.index} → ${index}`,
    ).toBe(true);

    // Team utilization (Manager): Priya's row moved, and reads the numbers above
    await as(page, DEMO.manager);

    const after = await teamRow(page, PRIYA);

    expect(after.stale).toBe(stale);
    expect(after.work).toBeCloseTo(workDays, 1);
    expect(after.util).toBe(utilization);
    expect(after.discipline).toBeCloseTo(discipline, 1);
    expect(after.index).toBeCloseTo(index, 1);

    // Appraisal: Discipline is the same number, and the draft score is the weighted mix of its parts
    await page.goto('/appraisal');
    await expect(h1(page, /^Appraisal & increments/)).toBeVisible();

    const rules = await card(page, 'How the increment is decided').locator('p').first().innerText();
    const weight = (label) => Number(new RegExp(`${label} (\\d+)%`).exec(rules)[1]);
    const w = [weight('Performance index'), weight('360° feedback'), weight('Manager review'), weight('Discipline')];
    const apr = card(page, 'People').locator('tbody tr').filter({ hasText: PRIYA });

    await expect(apr).toBeVisible();

    const [, perf, r360, mgr, disc, total] = (await apr.locator('td').allInnerTexts()).map(num);

    expect(disc, 'Discipline').toBeCloseTo(discipline, 1);

    const parts = [perf, r360, mgr, disc].map((v, i) => [v, w[i]]).filter(([v]) => v !== null);
    const mix = parts.reduce((s, [v, k]) => s + v * k, 0) / parts.reduce((s, [, k]) => s + k, 0);

    expect(total, 'score = weighted parts').toBeCloseTo(mix, 1);
  });

  await test.step('5 · An unmatched username is linked, and the person\'s figures update', async () => {
    const unlinked = await teamRow(page, ARJUN);

    expect(unlinked.work, 'arjun.m counts for no one yet').toBe(0);

    await as(page, DEMO.admin);
    await page.goto('/integrations');

    const panel = card(page, 'Unmatched accounts');
    const row = panel.locator('tbody tr').filter({ hasText: 'arjun.m' });

    await row.getByRole('button', { name: 'Link arjun.m to a person' }).click();

    const dialog = page.getByRole('dialog', { name: 'Link arjun.m to a person' });

    await dialog.getByLabel('Search people').fill(ARJUN);
    await dialog.getByRole('radio', { name: new RegExp(ARJUN) }).first().check();
    await dialog.getByRole('button', { name: 'Link', exact: true }).click();
    await expect(toast(page, `Linked · arjun.m → ${ARJUN}`)).toBeVisible();

    // no new sync: the next read counts PUL-204 and PUL-205 for Arjun (6 h + 8 h remaining)
    await as(page, DEMO.manager);

    const linked = await teamRow(page, ARJUN);

    expect(linked.work).toBeCloseTo(14 / 6, 1);
    expect(mock.requests.filter((r) => r.path === '/rest/api/2/search')).toHaveLength(1);
  });

  await test.step('7 · A concurrent Sync now gets 409', async () => {
    await as(page, DEMO.admin);
    await page.goto('/integrations');
    mock.reset();

    const release = mock.hold('/rest/api/2/search');
    const syncBtn = tile(page).getByRole('button', { name: `Sync ${TOOL} now` });

    await syncBtn.click();
    await expect(syncBtn).toHaveText('Syncing…');
    await expect.poll(() => mock.requests.filter((r) => r.path === '/rest/api/2/search').length).toBe(1);

    const second = await page.request.post('/api/v1/integrations/jira/sync', { headers: { 'X-CSRF-Token': csrf } });

    expect(second.status()).toBe(409);

    release();
    await expect(toast(page, `${TOOL} · synced`)).toBeVisible();
    await expect(syncBtn).toHaveText('Sync now');
    expect(mock.requests.filter((r) => r.path === '/rest/api/2/search'), 'one sync reached Jira').toHaveLength(1);

    // an incremental run: the JQL asks from one minute before the stored cursor
    expect(mock.requests.find((r) => r.path === '/rest/api/2/search').query.jql).toBe(
      'project in ("PUL") AND updated >= "2026-11-25 17:59" ORDER BY updated ASC',
    );
  });

  await test.step('6 · A 401 gives "Sync failed · 401", and Fix opens Change credentials', async () => {
    mock.reset();
    mock.fail(401);

    await tile(page).getByRole('button', { name: `Sync ${TOOL} now` }).click();
    await expect(toast(page, `${TOOL} · Sync failed · 401`)).toBeVisible();
    await expect(tile(page)).toContainText('Sync failed · 401');

    await tile(page).getByRole('button', { name: `Fix ${TOOL}` }).click();

    const form = page.getByRole('dialog', { name: `Change credentials · ${TOOL}` });

    await expect(form.getByLabel('Base URL')).toHaveValue(mock.baseUrl);
    await expect(form.getByLabel('Password / Token')).toHaveValue('');
    await form.getByRole('button', { name: 'Cancel' }).click();
    mock.reset();
  });

  await test.step('8 · Disconnect keeps the rows', async () => {
    // the tile action is Fix after the failure; Settings is on the credentials table
    await page.getByRole('button', { name: `View ${TOOL}` }).click();

    const settings = page.getByRole('dialog', { name: TOOL });

    await settings.getByRole('button', { name: 'Disconnect' }).click();
    await page.getByRole('dialog', { name: `Disconnect ${TOOL}?` }).getByRole('button', { name: 'Disconnect' }).click();
    await expect(toast(page, `Disconnected · ${TOOL}`)).toBeVisible();
    await expect(tile(page).getByRole('button', { name: `Sync ${TOOL} now` })).toHaveCount(0);

    await as(page, DEMO.employee);
    await page.goto('/work-items');

    const row = page.locator('section.pl-card tbody tr').filter({ hasText: 'PUL-201' });

    await expect(row).toHaveCount(1);
    await expect(row.locator('.pl-badge').filter({ hasText: /^Jira$/ })).toBeVisible();
    expect(mock.requests, 'no request after Disconnect').toHaveLength(0);
  });
});
