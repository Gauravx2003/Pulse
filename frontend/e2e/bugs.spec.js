import { expect, test } from '@playwright/test';
import { sql } from './db.js';
import { DEMO, distinctIp, expectNoHorizontalScroll, signIn } from './helpers.js';

/**
 * Task 12.2: Bugs & support against the real backend (demo seed, today pinned to 28 Sep 2026), following
 * reference 7.5 "Screen detail": Priya disputes an attribution and Rahul removes it from her count; Priya
 * takes the fix of a bug in her code; Priya takes a queue ticket and resolves it in the modal; Rahul's
 * Team tab. The rows a test changes are put back first, so repeated runs pass.
 */
const h1 = (page, name) => page.getByRole('heading', { level: 1, name });
const card = (page, name) => page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });
const toast = (page, text) => page.getByRole('status').filter({ hasText: text });

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test('Priya disputes an attribution and Rahul removes it from her count', async ({ page }) => {
  sql("DELETE FROM bug_disputes WHERE bug_id IN (SELECT id FROM bugs WHERE key = 'BUG-4101')");

  await signIn(page, DEMO.employee);
  await page.goto('/bugs-support');

  await expect(h1(page, 'Priya Shah')).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Team' })).toHaveCount(0);

  const open = card(page, 'Open bugs in my code');

  await expect(page.locator('section.pl-card').filter({ hasText: 'a learning signal, not blame' }).first()).toBeVisible();

  await open.locator('li').filter({ hasText: 'BUG-4101' }).getByRole('button', { name: 'Dispute' }).click();

  const modal = page.getByRole('dialog', { name: 'Dispute attribution' });

  await modal.getByLabel('Why is this not from your code?').fill('not me');
  await modal.getByRole('button', { name: 'Send dispute' }).click();
  await expect(modal).toContainText('at least 10 characters');

  await modal.getByLabel('Why is this not from your code?').fill('The regression came from a config change in another PR');
  await modal.getByRole('button', { name: 'Send dispute' }).click();

  await expect(toast(page, 'Dispute sent to your manager')).toBeVisible();
  await expect(open.locator('li').filter({ hasText: 'BUG-4101' })).toContainText('Attribution disputed');

  await page.context().clearCookies();
  await signIn(page, DEMO.manager);
  await page.goto('/bugs-support');

  const disputes = card(page, 'Disputes to decide');
  const row = disputes.locator('li').filter({ hasText: 'BUG-4101' });

  await expect(row).toContainText('Priya Shah');
  await row.getByRole('button', { name: 'Remove from their count' }).click();

  await expect(toast(page, 'BUG-4101 no longer counted against Priya Shah')).toBeVisible();
  await expect(disputes.locator('li').filter({ hasText: 'BUG-4101' })).toHaveCount(0);
});

test('Priya takes the fix of a bug in her code that Neha is fixing', async ({ page }) => {
  sql("DELETE FROM bug_work WHERE bug_id IN (SELECT id FROM bugs WHERE key = 'BUG-4050')");

  await signIn(page, DEMO.employee);
  await page.goto('/bugs-support');

  const bug = card(page, 'Open bugs in my code').locator('li').filter({ hasText: 'BUG-4050' });

  await expect(bug).toContainText('Fixing: Neha Joshi');
  await bug.getByRole('button', { name: 'Take it' }).click();

  await expect(toast(page, 'BUG-4050 assigned to you in Jira')).toBeVisible();
  await expect(bug).toContainText('Fixing: Priya Shah (author)');
  await expect(bug.getByRole('button', { name: 'Mark resolved' })).toBeVisible();
});

test('Priya takes a queue ticket and resolves it with hours and a note', async ({ page }) => {
  sql("DELETE FROM ticket_work WHERE ticket_id IN (SELECT id FROM support_tickets WHERE key = 'SUP-8872')");
  sql("DELETE FROM extra_items WHERE detection_key LIKE 'support:SUP-8872:%'");

  await signIn(page, DEMO.employee);
  await page.goto('/bugs-support');

  await card(page, 'Support queue').locator('li').filter({ hasText: 'SUP-8872' }).getByRole('button', { name: 'Take ticket' }).click();
  await expect(toast(page, 'SUP-8872 is yours')).toBeVisible();

  const ticket = page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name: /^Support tickets taken/ }) })
    .locator('li').filter({ hasText: 'SUP-8872' });

  await ticket.getByRole('button', { name: 'Resolve' }).click();

  const modal = page.getByRole('dialog', { name: 'Resolve SUP-8872' });

  await modal.getByLabel('Hours spent').fill('2');
  await modal.getByRole('button', { name: 'Resolve ticket' }).click();
  await expect(modal).toContainText('Add a resolution note.');

  await modal.getByLabel('Resolution note').fill('Moved the alert mailer to the priority queue');
  await modal.getByRole('button', { name: 'Resolve ticket' }).click();

  await expect(toast(page, 'SUP-8872 resolved · 2 h · points sent for manager confirmation')).toBeVisible();
  await expect(ticket).toContainText('Resolved');
  await expectNoHorizontalScroll(page);
});

test('Rahul sees the team: KPIs, people and the period table', async ({ page }) => {
  await signIn(page, DEMO.manager);
  await page.goto('/bugs-support');

  await expect(h1(page, 'Bugs & support · Platform team')).toBeVisible();
  await expect(page.getByRole('group', { name: 'Team bugs and support' }).locator('.pl-stat')).toHaveCount(4);
  await expect(card(page, 'People').locator('tbody tr')).toHaveCount(8);
  await expect(card(page, 'Sprint by sprint').locator('tbody tr')).toHaveCount(6);

  await card(page, 'People').locator('tbody tr').filter({ hasText: 'Priya Shah' }).click();
  await expect(h1(page, 'Priya Shah')).toBeVisible();
  await expect(card(page, 'Culture credit')).toBeVisible();
  await expectNoHorizontalScroll(page);
});
