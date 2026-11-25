import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { sql } from './db.js';
import { DEMO, distinctIp, expectNoHorizontalScroll, signIn } from './helpers.js';

/**
 * Task 25.2 (change pulse-v2-live-connectors): Work items with a live Jira row beside the seed rows.
 * The row is written as the Jira connector's batch writes it (source 'jira', external id, synced_at,
 * html_url and the assignee's Jira username, linked to Priya in external_accounts). It is a Done issue
 * resolved in June 2026, outside any sprint, so the live sprint and the months the other suites read
 * are unchanged; the insert is idempotent across runs.
 *
 * Checks the source badge, the source filter, "Open in Jira" in the list and the drawer (new tab,
 * rel="noopener noreferrer"), axe WCAG 2.1 A/AA on the screen and the drawer, and that every request
 * that reaches Jira is a GET: no action of the screen writes to Jira.
 */
const JIRA = 'https://jira.e2e.test';
const KEY = 'JIRA-E2E-1';

const card = (page) => page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name: /^Issues · / }) });
const drawer = (page) => page.getByRole('dialog');

async function axe(page, include) {
  const result = await new AxeBuilder({ page }).include(include).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();

  return result.violations.map((v) => `${v.id}: ${v.help} (${v.nodes.map((n) => n.target.join(' ')).join(' | ')})`);
}

test.beforeAll(() => {
  sql(`INSERT INTO external_accounts (tool, tool_account_id, login, display_name, person_id)
       SELECT 'jira', 'priya.e2e', 'priya.e2e', 'Priya Shah', id FROM people WHERE source = 'seed' AND external_id = 'ps'
       ON CONFLICT (tool, tool_account_id) DO NOTHING`);

  sql(`INSERT INTO issues (key, summary, status, story_points, spent_hours, started_on, resolved_on, source, external_id,
                           synced_at, html_url, assignee_account_id)
       VALUES ('${KEY}', 'Live trap collector', 'Done', 2, 5, '2026-06-08', '2026-06-15', 'jira', '${KEY}', now(),
               '${JIRA}/browse/${KEY}', 'priya.e2e')
       ON CONFLICT (source, external_id) DO NOTHING`);
});

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test('a live Jira issue shows its source, filters by source and opens in Jira, with no write to Jira', async ({ page, context }) => {
  const toJira = [];

  context.on('request', (request) => {
    if (request.url().startsWith(JIRA)) {
      toJira.push(request.method());
    }
  });

  await context.route(`${JIRA}/**`, (route) => route.fulfill({ status: 200, contentType: 'text/html', body: '<title>Jira</title>' }));

  await signIn(page, DEMO.employee);
  await page.goto('/work-items');

  await page.getByRole('group', { name: 'Period type' }).getByRole('button', { name: 'Month' }).click();
  await page.getByRole('combobox', { name: 'Period' }).selectOption('m:2026-06');

  const list = card(page);
  const row = list.locator('tbody tr').filter({ hasText: KEY });

  await expect(row).toHaveCount(1);
  await expect(row.locator('.pl-badge').filter({ hasText: /^Jira$/ })).toBeVisible();

  const link = row.getByRole('link', { name: new RegExp(`^Open in Jira ${KEY}`) });

  await expect(link).toHaveAttribute('href', `${JIRA}/browse/${KEY}`);
  await expect(link).toHaveAttribute('target', '_blank');
  await expect(link).toHaveAttribute('rel', 'noopener noreferrer');

  // the source filter: Jira keeps the live row only, Seed leaves it out
  const source = list.getByRole('combobox', { name: 'Source' });

  await source.selectOption('jira');
  await expect(list.locator('tbody tr')).toHaveCount(1);
  await expect(list.locator('tbody tr')).toContainText(KEY);

  await source.selectOption('seed');
  await expect(list.locator('tbody tr').filter({ hasText: KEY })).toHaveCount(0);

  await source.selectOption('all');
  await expect(row).toHaveCount(1);

  expect(await axe(page, '#pl-content')).toEqual([]);
  await expectNoHorizontalScroll(page);

  // the drawer: source badge and Open in Jira
  await row.locator('td').nth(1).click();
  await expect(drawer(page)).toContainText('Live trap collector');
  await expect(drawer(page).locator('.pl-badge').filter({ hasText: /^Jira$/ })).toBeVisible();

  const open = drawer(page).getByRole('link', { name: /^Open in Jira/ });

  await expect(open).toHaveAttribute('href', `${JIRA}/browse/${KEY}`);
  await expect(open).toHaveAttribute('rel', 'noopener noreferrer');

  expect(await axe(page, '[role="dialog"]')).toEqual([]);

  const [tab] = await Promise.all([context.waitForEvent('page'), open.click()]);

  await tab.waitForLoadState();
  await expect(tab).toHaveURL(`${JIRA}/browse/${KEY}`);
  await tab.close();

  expect(toJira.length).toBeGreaterThan(0);
  expect(toJira.every((method) => method === 'GET'), `requests to Jira: ${toJira.join(', ')}`).toBe(true);
});

test('a seed-only list has no source filter and no badges, as before', async ({ page }) => {
  await signIn(page, DEMO.employee);
  await page.goto('/work-items');

  const list = page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name: 'Sprint 43', exact: true }) });

  await expect(list.locator('tbody tr').first()).toBeVisible();
  await expect(list.getByRole('combobox', { name: 'Source' })).toHaveCount(0);
  await expect(list.locator('tbody .pl-badge').filter({ hasText: /^(Seed|Jira)$/ })).toHaveCount(0);
  await expect(list.getByRole('link', { name: /^Open in Jira/ })).toHaveCount(0);
});
