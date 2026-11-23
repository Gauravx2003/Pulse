import { expect, test } from '@playwright/test';
import { sql } from './db.js';
import { DEMO, distinctIp, expectNoHorizontalScroll, signIn } from './helpers.js';

/**
 * Task 8.4: Overview and Work items as priya@ (Employee) and rahul@ (Manager) against the real backend
 * (demo seed, today pinned to 28 Sep 2026, Sprint 43 active), following reference 7.1 and 7.2 "Screen
 * detail": KPIs, the overload alert, needs attention, period controls, filters and search, the issue
 * drawer and Nudge on Slack. Nudges of an earlier run are cleared first, since the server allows one per
 * issue and sender a day.
 */
const h1 = (page, name) => page.getByRole('heading', { level: 1, name });
const card = (page, name) => page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });
const drawer = (page) => page.getByRole('dialog');
const toast = (page, text) => page.getByRole('status').filter({ hasText: text });

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test.describe('Overview', () => {
  test('Priya sees the live sprint: alert, KPIs and the issues that need attention', async ({ page }) => {
    await signIn(page, DEMO.employee);
    await page.goto('/overview');

    await expect(h1(page, 'Priya Shah')).toBeVisible();
    await expect(page.locator('.pl-hdr .pl-caption')).toHaveText('Software Engineer II · Platform · L3 · Manager Rahul Nair');

    await expect(page.locator('.pl-alert').first()).toContainText('Overloaded:');

    const kpis = page.getByRole('group', { name: 'Live sprint' });

    await expect(kpis.locator('.pl-stat')).toHaveCount(5);

    for (const name of ['Tasks assigned', 'No update > 2 days', 'Work assigned', 'Utilization', 'Performance index']) {
      await expect(kpis).toContainText(name);
    }

    const attention = card(page, 'Needs attention');

    await expect(attention.locator('thead th')).toHaveText(['Key', 'Summary', 'Status', 'SP', 'Remaining', 'Due', 'Last activity', 'Signals']);
    await expect(attention.locator('tbody tr').filter({ hasText: 'PLAT-2271' })).toContainText('Low logged time');

    await attention.locator('tbody tr').filter({ hasText: 'PLAT-2271' }).click();

    await expect(drawer(page)).toContainText('Date & time verification');
    await expect(drawer(page)).toContainText('Not counted as activity');
    await expect(drawer(page).getByRole('button', { name: /^Nudge on/ })).toHaveCount(0);

    await page.keyboard.press('Escape');
    await expect(drawer(page)).toHaveCount(0);

    await expect(card(page, 'Utilization by sprint')).toContainText('Overall');
    await expect(card(page, 'Bugs & support')).toContainText('Open bugs in my code');
    await expectNoHorizontalScroll(page);
  });

  test('a closed sprint shows history and a release shows its card', async ({ page }) => {
    await signIn(page, DEMO.employee);
    await page.goto('/overview');

    await page.getByRole('combobox', { name: 'Period' }).selectOption('s:S42');

    await expect(card(page, 'Delivery · Sprint 42')).toContainText('Median cycle time');
    await expect(card(page, 'Sprints in Sprint 42')).toContainText('Sprint 42');

    await page.getByRole('group', { name: 'Period type' }).getByRole('button', { name: 'Release' }).click();
    await page.getByRole('combobox', { name: 'Period' }).selectOption('r:8.3');

    await expect(card(page, 'NMS 8.3 · release 8.3')).toContainText('Share of release');
  });
});

test.describe('Work items', () => {
  test('Priya filters the stale issues, searches and opens one', async ({ page }) => {
    await signIn(page, DEMO.employee);
    await page.goto('/work-items');

    await expect(h1(page, 'Priya Shah')).toBeVisible();
    await expect(page.getByRole('group', { name: 'Live sprint' }).locator('.pl-stat')).toHaveCount(4);

    const list = card(page, 'Sprint 43');

    await expect(list.getByRole('tab', { name: /^All/ })).toContainText('8');

    await list.getByRole('tab', { name: /^Stale \/ not started/ }).click();
    await expect(list.locator('tbody tr')).toHaveCount(2);

    await list.getByRole('searchbox', { name: 'Search key or summary' }).fill('snmp');
    await expect(list.locator('tbody tr')).toHaveCount(1);
    await expect(list.locator('tbody tr')).toContainText('PLAT-2217');

    await list.locator('tbody tr').click();
    await expect(drawer(page)).toContainText('SNMP trap parser');
    await page.keyboard.press('Escape');

    await expect(card(page, 'Daily load').locator('li')).not.toHaveCount(0);
    await expectNoHorizontalScroll(page);
  });

  test('Rahul picks Sara and nudges her about a stale issue', async ({ page }) => {
    // Sara, not Priya: other tests count Priya's unread notifications, and a nudge adds one
    sql("DELETE FROM nudges WHERE issue_id IN (SELECT id FROM issues WHERE key = 'PLAT-2247')");
    sql("DELETE FROM notifications WHERE kind = 'work.stale_nudge' AND user_id = (SELECT id FROM users WHERE email = 'sara@pulse.demo')");

    await signIn(page, DEMO.manager);
    await page.goto('/work-items');

    await page.getByRole('combobox', { name: 'Person' }).selectOption({ label: 'Sara Khan' });
    await expect(h1(page, 'Sara Khan')).toBeVisible();

    const list = card(page, 'Sprint 43');

    await list.getByRole('searchbox', { name: 'Search key or summary' }).fill('PLAT-2247');
    await expect(list.locator('tbody tr')).toHaveCount(1);
    await list.locator('tbody tr').click();

    const nudge = drawer(page).getByRole('button', { name: 'Nudge on Slack' });

    await expect(nudge).toBeEnabled();
    await nudge.click();

    await expect(toast(page, 'Slack DM sent to Sara Khan about PLAT-2247')).toBeVisible();
    await expect(drawer(page).getByRole('button', { name: 'Nudge on Slack' })).toBeDisabled();
    await expect(drawer(page)).toContainText('You nudged this issue recently.');
  });
});
