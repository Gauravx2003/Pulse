import { expect, test } from '@playwright/test';
import { DEMO, distinctIp, expectNoHorizontalScroll, signIn } from './helpers.js';

/**
 * Task 9.2: Team utilization as rahul@ (Manager) against the real backend (demo seed, today pinned to
 * 28 Sep 2026, Sprint 43 active), following reference 6.3 "Screen detail": the rebalance suggestion,
 * KPIs, the daily heatmap and people table, closed periods with the chart by sprint and the release
 * card; and the no-access card for priya@ (Employee).
 */
const h1 = (page, name) => page.getByRole('heading', { level: 1, name });
const card = (page, name) => page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });
const period = (page) => page.getByRole('combobox', { name: 'Period' });

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test('Rahul sees the live sprint: suggestion, KPIs, heatmap and people', async ({ page }) => {
  await signIn(page, DEMO.manager);
  await page.goto('/team-utilization');

  await expect(h1(page, 'Platform team · Sprint 43')).toBeVisible();
  await expect(page.locator('.pl-hdr .pl-eyebrow')).toHaveText('Manager view');
  await expect(page.locator('.pl-hdr .pl-caption')).toContainText('8 people');

  const alert = page.locator('.pl-alert').filter({ hasText: 'Suggestion' });

  await expect(alert).toContainText(/^Suggestion\s*Move PLAT-2198 \(24 h\) from Priya Shah \(\d+%\) to Dev Patel \(52%\)\. New levels ≈ \d+% and 96%\./);
  await expect(alert.getByRole('button', { name: 'Reassign in Jira' })).toBeVisible();

  const kpis = page.getByRole('group', { name: 'Team this sprint' });

  await expect(kpis.locator('.pl-stat')).toHaveCount(4);
  await expect(kpis).toContainText('Overloaded');
  await expect(kpis).toContainText('Priya');

  const heat = page.getByRole('region', { name: 'Daily utilization' });

  await expect(heat.getByRole('button', { name: /^Open .*'s sprint work$/ })).toHaveCount(8);

  const people = card(page, 'People');

  await expect(people.locator('thead th')).toHaveText(['Person', 'Open', 'Stale', 'Work', 'Capacity', 'Utilization', 'Daily updates', 'Discipline', 'Index']);
  await expect(people.locator('tbody tr').first()).toContainText('Priya Shah');

  await expectNoHorizontalScroll(page);

  await people.locator('tbody tr').first().click();
  await expect(page).toHaveURL(/\/overview$/);
  await expect(h1(page, 'Priya Shah')).toBeVisible();
});

test('closed periods show history, the chart by sprint and the release card', async ({ page }) => {
  await signIn(page, DEMO.manager);
  await page.goto('/team-utilization');

  await period(page).selectOption('s:S42');

  await expect(h1(page, 'Platform team · Sprint 42')).toBeVisible();
  await expect(page.getByRole('group', { name: 'Sprint 42' }).locator('.pl-stat')).toHaveCount(5);
  await expect(card(page, 'People · Sprint 42').locator('tbody tr')).not.toHaveCount(0);

  await page.getByRole('group', { name: 'Period type' }).getByRole('button', { name: 'Month' }).click();
  await period(page).selectOption('m:2026-09');

  const chart = card(page, 'Team utilization by sprint');

  await expect(chart.getByRole('button')).toHaveCount(3);
  await chart.getByRole('button', { name: /Sprint 41/ }).click();
  await expect(h1(page, 'Platform team · Sprint 41')).toBeVisible();

  await page.getByRole('group', { name: 'Period type' }).getByRole('button', { name: 'Release' }).click();
  await period(page).selectOption('r:8.2');

  await expect(card(page, 'NMS 8.2 · release 8.2')).toContainText('team delivered');
  await expectNoHorizontalScroll(page);
});

test('an Employee gets the no-access card', async ({ page }) => {
  await signIn(page, DEMO.employee);
  await page.goto('/team-utilization');

  await expect(page.getByRole('heading', { name: "You don't have access" })).toBeVisible();
});
