import { expect, test } from '@playwright/test';
import { DEMO, distinctIp, expectNoHorizontalScroll, signIn } from './helpers.js';

/**
 * Task 14.4: the OKR screens against the real backend (demo seed, today pinned to 28 Sep 2026),
 * following reference 9.1 "Screen detail — OKRs": Priya's OKRs with the quarter dropdown, a check-in,
 * and a new objective created, edited and deleted (so repeated runs leave the data as it was); the Team
 * tab; Rahul opening Priya's OKRs with okr.team.
 */
const h1 = (page, name) => page.getByRole('heading', { level: 1, name });
const card = (page, name) => page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });
const toast = (page, text) => page.getByRole('status').filter({ hasText: text });
const unique = () => `${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}`;

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test('Priya sees her quarter and checks in on a key result', async ({ page }) => {
  await signIn(page, DEMO.employee);
  await page.goto('/okrs');

  await expect(h1(page, 'My OKRs')).toBeVisible();
  await expect(page.locator('.pl-hdr .pl-eyebrow')).toHaveText('Objectives & key results');
  await expect(page.getByRole('combobox', { name: 'Quarter' }).locator('option:checked')).toHaveText('Q3 2026 (2)');
  await expect(page.getByRole('group', { name: 'OKRs this quarter' }).locator('.pl-stat')).toHaveCount(4);

  const objective = card(page, 'Make ingestion safe for noisy tenants');

  await expect(objective).toContainText('Owner Priya Shah · Q3 2026');
  await expect(page.locator('.pl-hdr').getByRole('link', { name: 'Import CSV' })).toHaveAttribute('href', '/imports?type=okrs');

  await objective.getByRole('button', { name: 'Check-in: Per-tenant rate limiting live for all tenants' }).click();

  const modal = page.getByRole('dialog', { name: 'Check-in' });

  await expect(modal).toContainText('Start 0 · target 100 %');
  await modal.getByLabel('Current value').fill('70');
  await modal.getByRole('button', { name: 'Save check-in' }).click();

  await expect(toast(page, /Check-in saved · objective at \d+%/)).toBeVisible();
  await expectNoHorizontalScroll(page);
});

test('Priya creates, edits and deletes an objective', async ({ page }) => {
  const name = `E2E objective ${unique()}`;

  await signIn(page, DEMO.employee);
  await page.goto('/okrs');

  await page.locator('.pl-hdr').getByRole('button', { name: 'New objective' }).click();

  const modal = page.getByRole('dialog', { name: 'New objective' });

  await modal.getByRole('button', { name: 'Create objective' }).click();
  await expect(modal).toContainText('Write the objective.');
  await expect(modal).toContainText('Every key result needs a description.');

  await modal.getByRole('textbox', { name: 'Objective', exact: true }).fill(name);
  await modal.getByRole('textbox', { name: 'Key result 1', exact: true }).fill('Ship the thing');
  await modal.getByRole('button', { name: 'Create objective' }).click();

  await expect(toast(page, 'Objective created')).toBeVisible();

  const created = card(page, name);

  await expect(created).toContainText('Ship the thing');

  await created.getByRole('button', { name: 'Edit' }).click();

  const edit = page.getByRole('dialog', { name: 'Edit objective' });

  await edit.getByRole('textbox', { name: 'Objective', exact: true }).fill(`${name} (edited)`);
  await edit.getByRole('button', { name: 'Save changes' }).click();
  await expect(toast(page, 'Objective updated')).toBeVisible();

  const edited = card(page, `${name} (edited)`);

  await edited.getByRole('button', { name: 'Delete' }).click();

  const confirm = page.getByRole('dialog', { name: 'Delete objective?' });

  await expect(confirm).toContainText('and its 1 key result will be deleted. This cannot be undone.');
  await confirm.getByRole('button', { name: 'Delete', exact: true }).click();

  await expect(toast(page, 'Objective deleted')).toBeVisible();
  await expect(card(page, `${name} (edited)`)).toHaveCount(0);
});

test('the Team tab shows the team objectives to everyone', async ({ page }) => {
  await signIn(page, DEMO.employee);
  await page.goto('/okrs?tab=team');

  await expect(h1(page, 'Platform team OKRs')).toBeVisible();
  await expect(card(page, 'Reliable, predictable Platform delivery')).toContainText('Owner Platform team');
  await expect(page.locator('.pl-hdr').getByRole('button', { name: 'New objective' })).toHaveCount(0);
});

test("Rahul opens Priya's OKRs and may change them", async ({ page }) => {
  await signIn(page, DEMO.manager);
  await page.goto('/okrs');

  await page.getByRole('combobox', { name: 'Person' }).selectOption({ label: 'Priya Shah' });

  await expect(h1(page, 'Priya Shah · OKRs')).toBeVisible();

  const objective = card(page, 'Make ingestion safe for noisy tenants');

  await expect(objective.getByRole('button', { name: 'Edit' })).toBeVisible();
  await expect(objective.getByRole('button', { name: /^Check-in/ }).first()).toBeVisible();
});
