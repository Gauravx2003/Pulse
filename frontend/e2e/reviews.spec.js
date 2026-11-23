import { expect, test } from '@playwright/test';
import { sql } from './db.js';
import { DEMO, distinctIp, expectNoHorizontalScroll, signInViaApi } from './helpers.js';

/**
 * Task 20.4: Reviews screens (specs culture/one-on-ones-kudos, culture/review-360) against the real
 * backend (demo seed, today pinned to 28 Sep 2026, the Q3 2026 360° cycle running). End to end with
 * three accounts: rahul@ assigns Neha to review Priya (Neha is notified), neha@ fills and submits the form,
 * hr@ publishes the cycle and priya@ opens her results.
 */
const NEHA = 'neha@pulse.demo';
const Q3 = "name LIKE 'Q3 2026%' AND kind = '360'";

const h1 = (page) => page.getByRole('heading', { level: 1, name: 'Reviews' });
const card = (page, name) =>
  page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });

async function open(page, who, query = '') {
  await signInViaApi(page, who);
  await page.goto(`/reviews${query}`);
  await expect(h1(page)).toBeVisible();
}

/** Picks the option of a labelled select (a required label ends in "*") whose text contains `text`. */
async function choose(scope, label, text) {
  const field = scope.getByLabel(new RegExp(`^${label}\\*?$`));
  const value = await field.locator('option', { hasText: text }).first().getAttribute('value');
  await field.selectOption(value);
}

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test.describe.serial('360° assign, notify, submit and publish', () => {
  test.beforeAll(() => {
    // Q3 running again, without an earlier run's request from Neha about Priya.
    sql(`UPDATE review_cycles SET status = 'running', published_at = NULL WHERE ${Q3}`);
    sql(`DELETE FROM review_requests r USING review_cycles c, review_reviewer_keys k, users u, people p
         WHERE c.id = r.cycle_id AND ${Q3.replace(/name|kind/g, (m) => `c.${m}`)} AND k.pseudonym = r.reviewer_key AND k.user_id = u.id
           AND u.email = '${NEHA}' AND p.id = r.subject_id AND p.name = 'Priya Shah'`);
    sql(`DELETE FROM notifications n USING users u WHERE u.id = n.user_id AND u.email = '${NEHA}' AND n.kind = 'review360.assigned'`);
  });

  test('rahul@ assigns Neha as a reviewer of Priya', async ({ page }) => {
    await open(page, DEMO.manager, '?tab=360&view=team');

    await page.getByRole('button', { name: 'Assign reviewers' }).click();
    const dialog = page.getByRole('dialog', { name: 'Assign reviewers' });
    await choose(dialog, 'Subject', 'Priya Shah');
    await choose(dialog, 'Reviewer', 'Neha Joshi');
    await dialog.getByRole('button', { name: 'Assign' }).click();

    await expect(page.getByText(/1 reviewer assigned/)).toBeVisible();
    expect(Number(sql(`SELECT count(*) FROM notifications n JOIN users u ON u.id = n.user_id WHERE u.email = '${NEHA}' AND n.kind = 'review360.assigned'`))).toBe(1);
  });

  test('neha@ fills and submits the review', async ({ page }) => {
    await open(page, NEHA, '?tab=360');

    await card(page, 'To give').getByRole('row', { name: /Priya Shah.*Q3 2026/ }).click();
    const dialog = page.getByRole('dialog');
    const VALUES = ['Outcome Ownership', 'Moral & Ethics', 'Decision Making', 'Problem Solving', 'Risk Appetite', 'Adaptability',
      'Agility in Execution', 'Collaboration', 'Resilience', 'Resourcefulness'];
    for (const value of VALUES) {
      // every value needs an answer: a score, or Not observed
      await dialog.getByLabel(`${value} score`).selectOption(['Collaboration', 'Problem Solving', 'Outcome Ownership'].includes(value) ? '4' : 'na');
    }
    await dialog.getByLabel('Keep doing').fill('Clear design notes before every review.');
    await dialog.getByLabel('Start doing').fill('Share the incident write-ups with the wider team.');
    await dialog.getByRole('button', { name: 'Submit review' }).click();

    await expect(page.getByText('Review submitted · Priya Shah')).toBeVisible();
  });

  test('hr@ publishes the cycle and priya@ opens her results', async ({ page, context }) => {
    await open(page, DEMO.admin, '?tab=360&view=cycles');

    await card(page, 'Cycles').getByRole('row', { name: /Q3 2026 360° review/ }).getByRole('button', { name: 'Publish' }).click();
    await page.getByRole('dialog', { name: 'Publish this cycle?' }).getByRole('button', { name: 'Publish' }).click();
    await expect(page.getByText(/Q3 2026 360° review published/)).toBeVisible();

    await context.clearCookies();
    await distinctIp(context);
    await open(page, DEMO.employee, '?tab=360&view=mine');

    await page.getByLabel(/^Cycle/).selectOption({ label: 'Q3 2026 360° review · published' });
    const mine = card(page, 'My feedback · Q3 2026 360° review');
    await expect(mine.getByRole('table', { name: '360° scores by value' })).toBeVisible();
    await expect(mine).not.toContainText('@pulse.demo');
  });
});

test.describe('Reviews screens', () => {
  test('1:1s & feedback: notes, action items and kudos', async ({ page }) => {
    await open(page, DEMO.manager, '?tab=oneonone');

    await expect(card(page, '1:1s')).toBeVisible();
    await expect(card(page, 'Kudos')).toBeVisible();
    await page.getByRole('button', { name: 'Give kudos' }).click();
    await expect(page.getByRole('dialog', { name: /Give kudos/ })).toBeVisible();
    await page.keyboard.press('Escape');
  });

  test('Performance waits for the performance index; phone width has no page scroll', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await open(page, DEMO.employee, '?tab=performance');

    await expect(card(page, 'Performance review')).toBeVisible();
    await expectNoHorizontalScroll(page);
  });
});
