import { expect, test } from '@playwright/test';
import { DEMO, distinctIp, expectNoHorizontalScroll, signIn } from './helpers.js';

/**
 * Task 13.4: the Action center against the real backend (demo seed, today pinned to 28 Sep 2026),
 * following reference 6.1 "Screen detail — Action center": Rahul's landing page with the five KPIs, Do
 * today with one button per item, people signals opening Progress, the sprint forecast, next-sprint
 * capacity with the team total, skills coverage, team pulse, team wins and the Weekly digest preview; an
 * Employee gets the no-access card.
 */
const card = (page, name) => page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test('Rahul lands on the Action center with the KPIs and Do today', async ({ page }) => {
  await signIn(page, DEMO.manager);
  await expect(page).toHaveURL(/\/actions$/);

  await expect(page.getByRole('heading', { level: 1, name: 'Action center' })).toBeVisible();
  await expect(page.locator('.pl-hdr .pl-eyebrow')).toHaveText('Manager view · 28 Sep');
  await expect(page.getByRole('group', { name: 'Action center summary' }).locator('.pl-stat')).toHaveCount(5);

  const today = card(page, 'Do today');

  const pulse = today.locator('li').filter({ hasText: 'Weekly pulse: mood 2/5, workload 5/5' }).filter({ hasText: 'Priya Shah' });

  await expect(pulse).toContainText('High');
  await expect(pulse).toContainText('Pulse');

  // Rebalance on the overload item opens Team utilization
  await today.locator('li').filter({ hasText: 'of capacity in Sprint 43' }).getByRole('button', { name: /^Rebalance/ }).first().click();
  await expect(page).toHaveURL(/\/team-utilization/);
});

test('people signals open Progress for the person', async ({ page }) => {
  await signIn(page, DEMO.manager);

  const signals = card(page, 'People signals');

  const priya = signals.locator('tbody tr').filter({ hasText: 'Priya Shah' });

  await expect(priya).toContainText('High');
  await expect(priya).toContainText('Pulse check-in low');

  await priya.click();
  await expect(page).toHaveURL(/\/progress$/);
  await expect(page.getByRole('heading', { level: 1, name: 'Priya Shah' })).toBeVisible();
});

test('forecast, next-sprint capacity and skills coverage', async ({ page }) => {
  await signIn(page, DEMO.manager);

  const forecast = card(page, 'Sprint 43 forecast');

  await expect(forecast.locator('thead th')).toHaveText(['Person', 'Remaining', 'Capacity left', 'Fits', 'Likely to carry over']);
  await expect(forecast.locator('tbody tr').filter({ hasText: 'Priya Shah' })).toContainText('PLAT-');
  await expect(forecast.getByRole('link', { name: 'Team utilization →' })).toBeVisible();

  const capacity = page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name: /^Next sprint capacity · Sprint 44/ }) });

  await expect(capacity.locator('thead th')).toHaveText(['Person', 'Days', 'Leave', 'Visits', 'Training h', 'Mentoring h', 'Focus days', 'Velocity', 'Suggest SP']);
  await expect(capacity.locator('tbody tr').last()).toContainText('Team');

  const coverage = card(page, 'Skills coverage');

  await expect(coverage.locator('tbody tr').filter({ hasText: 'quota' })).toContainText('Single point of failure');

  await expect(card(page, 'Team pulse · last 6 weeks').locator('li')).toHaveCount(6);
  await expect(card(page, 'Team wins')).toContainText('without being asked');
  await expectNoHorizontalScroll(page);
});

test('the Weekly digest preview opens and closes', async ({ page }) => {
  await signIn(page, DEMO.manager);

  await page.getByRole('button', { name: 'Weekly digest' }).click();

  const modal = page.getByRole('dialog', { name: /^Weekly digest · Monday 28 Sep/ });

  await expect(modal).toContainText('high-priority action');
  await expect(modal).toContainText('People to watch:');
  await expect(modal).toContainText('Priya Shah');

  await modal.getByRole('button', { name: 'Close' }).last().click();
  await expect(modal).toHaveCount(0);
});

test('an Employee gets the no-access card', async ({ page }) => {
  await signIn(page, DEMO.employee);
  await page.goto('/actions');

  await expect(page.getByRole('heading', { name: "You don't have access" })).toBeVisible();
});

test('phone width: no horizontal scroll', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await signIn(page, DEMO.manager);

  await expect(card(page, 'People signals')).toBeVisible();
  await expectNoHorizontalScroll(page);
});
