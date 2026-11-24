import { expect, test } from '@playwright/test';
import { sql } from './db.js';
import { DEMO, distinctIp, expectNoHorizontalScroll, signIn } from './helpers.js';

/**
 * Task 15.3: the Road map screen against the real backend (demo seed, today pinned to 28 Sep 2026),
 * following reference 9.2 "Screen detail — Road map": Priya's timeline, promotion readiness and skills
 * matrix, and a milestone she proposes that waits for her manager; Rahul confirming a proposed
 * milestone on Priya's road map; a peer (Arjun) getting the no-access card. Milestones made here start
 * with "E2E " and each test removes its own, so repeated runs leave the data as it was.
 */
const h1 = (page, name) => page.getByRole('heading', { level: 1, name });
const card = (page, name) => page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });
const toast = (page, text) => page.getByRole('status').filter({ hasText: text });
const unique = () => `${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}`;

const PRIYA = "(SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps')";

/** Titles of the milestones this worker's current test made; removed after each test (tests run in parallel). */
const made = [];

test.afterEach(() => {
  made.splice(0).forEach((title) => sql(`DELETE FROM milestones WHERE title = '${title}' AND person_id = ${PRIYA}`));
});

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test('Priya sees her road map and proposes a milestone that waits for her manager', async ({ page }) => {
  await signIn(page, DEMO.employee);
  await page.goto('/roadmap');

  await expect(h1(page, 'Priya Shah')).toBeVisible();
  await expect(page.locator('.pl-hdr .pl-eyebrow')).toHaveText('Road map');

  const road = card(page, 'Road map');

  await expect(road).toContainText('Agreed in 1:1s · employee proposes, manager confirms');
  await expect(road).toContainText('Senior Engineer track');
  await expect(road.locator('.pl-road-item').filter({ hasText: 'Owned alerting service end-to-end' })).toContainText('Done');

  const readiness = card(page, 'Promotion readiness');

  await expect(readiness).toContainText('Senior Engineer (L4) · Apr 2027');
  await expect(readiness.locator('.criterion')).toHaveCount(4);
  await expect(readiness).toContainText('Performance index');
  await expect(readiness).toContainText('cross-team epic');

  const skills = card(page, 'Skills matrix');

  await expect(skills).toContainText('Rated by manager against the level guide');
  await expect(skills.locator('thead th')).toHaveText(['Skill', 'Current', 'Needed for next level', 'Gap']);
  await expect(skills.locator('tbody tr').first()).toBeVisible();

  const title = `E2E outcome ${unique()}`;

  made.push(title);

  await page.getByRole('button', { name: 'Add milestone' }).click();

  const modal = page.getByRole('dialog', { name: 'Add milestone' });

  await expect(modal.getByRole('textbox', { name: 'When', exact: false })).toHaveValue('Q4 2026');
  await modal.getByRole('button', { name: 'Propose' }).click();
  await expect(modal).toContainText('Write the outcome.');

  await modal.getByRole('textbox', { name: 'Outcome', exact: false }).fill(title);
  await modal.getByRole('combobox', { name: 'Grows value' }).selectOption('Collaboration');
  await modal.getByRole('button', { name: 'Propose' }).click();

  await expect(toast(page, 'Milestone proposed — manager will confirm')).toBeVisible();
  await expect(modal).toHaveCount(0);

  const item = road.locator('.pl-road-item').filter({ hasText: title });

  await expect(item).toContainText('Proposed');
  await expect(item).toContainText('Collaboration');
  await expect(item.getByRole('button', { name: /^Confirm/ })).toHaveCount(0);
  await expectNoHorizontalScroll(page);
});

test('Rahul confirms a milestone Priya proposed', async ({ page }) => {
  const title = `E2E confirm ${unique()}`;

  made.push(title);

  sql(`INSERT INTO milestones (person_id, sort_order, title, when_label, stage, note, status)
       VALUES (${PRIYA}, 99, '${title}', 'Q1 2027', 'next', 'Planned', 'proposed')`);

  const priya = sql(`SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'`);

  await signIn(page, DEMO.manager);
  await page.goto(`/roadmap?person=${priya}`);
  await expect(h1(page, 'Priya Shah')).toBeVisible();

  const item = card(page, 'Road map').locator('.pl-road-item').filter({ hasText: title });

  await expect(item).toContainText('Proposed');
  await item.getByRole('button', { name: `Confirm: ${title}` }).click();

  await expect(toast(page, 'Milestone confirmed')).toBeVisible();
  await expect(item).toContainText('Planned');
  await expect(item.getByRole('button', { name: /^Confirm/ })).toHaveCount(0);
});

test('a peer opening another person’s road map gets the no-access card', async ({ page }) => {
  const priya = sql(`SELECT id FROM people WHERE source = 'seed' AND external_id = 'ps'`);

  await signIn(page, 'arjun@pulse.demo');
  await page.goto(`/roadmap?person=${priya}`);

  await expect(page.getByRole('heading', { name: "You don't have access" })).toBeVisible();
  await expect(page.getByText('Senior Engineer (L4)')).toHaveCount(0);
});

test('phone width: the timeline and readiness stack without horizontal scroll', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await signIn(page, DEMO.employee);
  await page.goto('/roadmap');

  await expect(card(page, 'Promotion readiness')).toBeVisible();
  await expectNoHorizontalScroll(page);
});
