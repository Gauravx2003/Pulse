import { expect, test } from '@playwright/test';
import { DEMO, distinctIp, expectNoHorizontalScroll, signIn } from './helpers.js';

/**
 * Task 10.3: the Pull requests screens against the real backend (demo seed, today pinned to 28 Sep
 * 2026), following reference 7.3 "Screen detail — Pull requests": Rahul's Team tab with the PR detail
 * and PR standard modals, Priya's Person tab with All grouped by month, and the Admin's link to Rules.
 */
const h1 = (page, name) => page.getByRole('heading', { level: 1, name });
const card = (page, name) => page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test('Rahul reviews the team and opens a PR merged below the standard', async ({ page }) => {
  await signIn(page, DEMO.manager);
  await page.goto('/pull-requests');

  await expect(h1(page, 'Pull requests · Platform team')).toBeVisible();
  await expect(page.locator('.pl-hdr .pl-eyebrow')).toHaveText('Manager view');
  await expect(page.locator('.pl-hdr .pl-caption')).toContainText('checked against the PR standard and the review comment standard');

  const kpis = page.getByRole('group', { name: 'Team pull requests' });

  await expect(kpis.locator('.pl-stat')).toHaveCount(5);
  await expect(kpis).toContainText('Target 85%');

  await expect(page.locator('section.pl-card').filter({ hasText: 'Signals for 1:1s, not a ranking' })).toBeVisible();
  await expect(card(page, 'Checks across the team').locator('li')).not.toHaveCount(0);
  await expect(card(page, 'Review comments across the team')).toContainText('Too short');

  const below = card(page, 'Merged without meeting the standard');

  await expect(below.locator('tbody tr')).not.toHaveCount(0);
  await below.locator('tbody tr').first().click();

  const detail = page.getByRole('dialog');

  await expect(detail).toContainText(/PR standard · \d+%/);
  await expect(detail).toContainText('Reviewers and comments');
  await expect(detail.getByText('Fail', { exact: true }).first()).toBeVisible();
  await detail.getByRole('button', { name: 'Close' }).last().click();

  await page.locator('.pl-hdr').getByRole('button', { name: 'PR standard' }).click();

  const standard = page.getByRole('dialog', { name: 'PR standard' });

  await expect(standard).toContainText('Part 1 · Title');
  await expect(standard).toContainText('Part 2 · The pull request');
  await expect(standard).toContainText('Part 3 · Review comments');
  await expect(standard.getByRole('link', { name: 'Edit in Rules & thresholds' })).toHaveCount(0);
  await standard.getByRole('button', { name: 'Close' }).last().click();

  await expectNoHorizontalScroll(page);
});

test('Priya sees her PRs with a dot per check, and All groups them by month', async ({ page }) => {
  await signIn(page, DEMO.employee);
  await page.goto('/pull-requests');

  await expect(h1(page, 'Priya Shah')).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Team' })).toHaveCount(0);
  await expect(page.getByRole('group', { name: 'Pull requests of the person' }).locator('.pl-stat')).toHaveCount(5);

  await page.locator('.pl-hdr').getByRole('button', { name: 'All', exact: true }).click();

  await expect(page.getByRole('combobox', { name: 'Period' })).toHaveCount(0);

  const prs = card(page, 'Pull requests · All');

  await expect(prs.locator('.pl-eyebrow').first()).toHaveText(/^\d{4}-\d{2}$/);
  await expect(prs.getByRole('listitem').first()).toHaveAttribute('aria-label', /: (Pass|Fail|Pending)$/);

  await prs.locator('tbody tr').first().click();
  await expect(page.getByRole('dialog')).toContainText('Reviewers and comments');
  await page.getByRole('dialog').getByRole('button', { name: 'Close' }).last().click();

  await page.locator('.pl-hdr').getByRole('button', { name: 'Back to periods' }).click();
  await expect(page.getByRole('combobox', { name: 'Period' })).toBeVisible();
  await expectNoHorizontalScroll(page);
});

test('an Admin can open Rules from the PR standard', async ({ page }) => {
  await signIn(page, DEMO.admin);
  await page.goto('/pull-requests');

  await page.locator('.pl-hdr').getByRole('button', { name: 'PR standard' }).click();
  await expect(page.getByRole('dialog', { name: 'PR standard' }).getByRole('link', { name: 'Edit in Rules & thresholds' })).toBeVisible();
});
