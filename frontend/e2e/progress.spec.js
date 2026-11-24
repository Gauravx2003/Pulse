import { expect, test } from '@playwright/test';
import { sql } from './db.js';
import { DEMO, distinctIp, expectNoHorizontalScroll, signIn } from './helpers.js';

/**
 * Task 13.4: Progress and the weekly pulse against the real backend (demo seed, today pinned to Monday
 * 28 Sep 2026), following reference 6.2 "Screen detail — Progress": Priya's own page (KPIs, next steps,
 * achievements, trends, her private pulse, team wins); Rahul opening Priya's page (Start 1:1, no pulse
 * form, a self-only step without Open); Arjun answering this week's pulse with both answers required. His
 * answer is removed afterwards, so repeated runs (and Priya's Action center items) stay as they were.
 */
const h1 = (page, name) => page.getByRole('heading', { level: 1, name });
const card = (page, name) => page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });
const toast = (page, text) => page.getByRole('status').filter({ hasText: text });

const ARJUN_WEEK = "DELETE FROM weekly_pulse WHERE week_start = '2026-09-28' AND person_id = (SELECT id FROM people WHERE source = 'seed' AND external_id = 'am')";

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test('Priya sees her progress, her pulse and team wins', async ({ page }) => {
  await signIn(page, DEMO.employee);
  await page.goto('/progress');

  await expect(h1(page, 'Priya Shah')).toBeVisible();
  await expect(page.locator('.pl-hdr .pl-eyebrow')).toHaveText('Progress');
  await expect(page.getByRole('group', { name: 'Progress summary' }).locator('.pl-stat')).toHaveCount(5);
  await expect(page.getByRole('link', { name: 'Start 1:1' })).toHaveCount(0);

  const steps = card(page, 'Your next steps');

  await expect(steps).toContainText('issues without an update');
  await expect(steps.locator('li').filter({ hasText: 'Weekly pulse check-in for this week' }).getByRole('button', { name: /^Open/ })).toBeVisible();

  await expect(card(page, 'Achievements')).toContainText('Proactive fixer');
  await expect(card(page, 'Performance index trend').locator('svg')).toBeVisible();
  await expect(card(page, 'Utilization by sprint')).toContainText('Overall');
  await expect(card(page, 'Goals, PRs and training')).toContainText('PR standard by month');

  const pulse = card(page, 'Weekly pulse');

  await expect(pulse).toContainText('Mood 2/5');
  await expect(pulse).toContainText('Sprint feels overloaded');

  await expect(card(page, 'Team wins')).toContainText('without being asked');
  await expectNoHorizontalScroll(page);
});

test('Rahul opens Priya’s progress with Start 1:1 and the same numbers', async ({ page }) => {
  await signIn(page, DEMO.manager);
  await page.goto('/progress');
  await page.getByRole('combobox', { name: 'Person' }).selectOption({ label: 'Priya Shah' });

  await expect(h1(page, 'Priya Shah')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Start 1:1' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Weekly pulse' })).toHaveCount(0);

  const steps = card(page, 'Next steps for Priya');

  await expect(steps.locator('li').filter({ hasText: 'Weekly pulse check-in for this week' }).getByRole('button')).toHaveCount(0);

  await expect(card(page, 'Weekly pulse')).toContainText('Private to Priya, you and HR');
});

test('Arjun answers this week’s pulse; both answers are required', async ({ page }) => {
  sql(ARJUN_WEEK);

  try {
    await signIn(page, 'arjun@pulse.demo');
    await page.goto('/progress?pulse=open');

    const modal = page.getByRole('dialog', { name: 'Weekly pulse · week of 28 Sep' });

    await expect(modal).toBeVisible();
    await modal.getByRole('button', { name: 'How was your week? 4' }).click();
    await modal.getByRole('button', { name: 'Send' }).click();
    await expect(modal).toContainText('Answer both questions.');

    await modal.getByRole('button', { name: 'How heavy was your workload? 3' }).click();
    await modal.getByRole('textbox', { name: /Anything blocking you/ }).fill('Good week on the quota service');
    await modal.getByRole('button', { name: 'Send' }).click();

    await expect(toast(page, 'Thanks — your pulse is in')).toBeVisible();
    await expect(modal).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Weekly pulse' })).toHaveCount(0);
    await expect(card(page, 'Weekly pulse')).toContainText('Good week on the quota service');
  } finally {
    sql(ARJUN_WEEK);
  }
});
