import { expect, test } from '@playwright/test';
import { sql } from './db.js';
import { DEMO, distinctIp, expectNoHorizontalScroll, signInViaApi } from './helpers.js';

/**
 * Task 21.3: Training screens (spec growth/training-mentoring) against the real backend (demo seed, today
 * pinned to 28 Sep 2026). One plan goes through its whole lifecycle across three people:
 * rahul@ assigns "Code review & PR standard" to Rohit (2 weeks from 7 Sep, so the period has ended),
 * sara@ (mentor) records the assessment and her review, rahul@ writes the manager review and decision,
 * rohit@ signs off. rohit@ also submits this week's check-in on his onboarding plan.
 */
const ROHIT = 'rohit@pulse.demo';
const SARA = 'sara@pulse.demo';
const PLAN = 'Code review & PR standard';

const h1 = (page, name) => page.getByRole('heading', { level: 1, name });
const card = (page, name) =>
  page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });

async function open(page, who, tab) {
  await signInViaApi(page, who);
  await page.goto(tab ? `/training?tab=${tab}` : '/training');
  await expect(h1(page, 'Training plans')).toBeVisible();
}

async function fillDialog(page, values) {
  const dialog = page.getByRole('dialog');

  for (const [label, value] of Object.entries(values)) {
    // a required field's label ends in "*"
    const escaped = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const field = dialog.getByLabel(new RegExp(`^${escaped}\\*?$`));
    const tag = await field.evaluate((el) => el.tagName.toLowerCase());

    if (tag === 'select') {
      // pick the option whose text contains the value
      const option = field.locator('option', { hasText: value }).first();
      await field.selectOption(await option.getAttribute('value'));
    } else {
      await field.fill(value);
    }
  }

  return dialog;
}

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test.describe.serial('Training lifecycle', { tag: '@flow' }, () => {
  // This suite's own notices would change other suites' unread counts; only its kinds, as suites run in parallel.
  let notificationsSince = '';

  test.beforeAll(() => {
    notificationsSince = sql('SELECT now()::text');
  });

  test.afterAll(() => {
    sql(`DELETE FROM notifications WHERE created_at >= '${notificationsSince}' AND (kind LIKE 'training.%' OR kind LIKE 'mentoring.%')`);
  });

  test.beforeAll(() => {
    // Start clean: Rohit's earlier run of this plan and this week's onboarding check-in.
    sql(`DELETE FROM review_requests WHERE cycle_id IN (SELECT id FROM review_cycles WHERE assignment_id IN (
           SELECT a.id FROM training_assignments a JOIN training_plan_versions v ON v.plan_id = a.plan_id AND v.plan_version = a.plan_version
           JOIN people p ON p.id = a.person_id JOIN users u ON u.id = p.user_id WHERE u.email = '${ROHIT}' AND v.name = '${PLAN}'))`);
    sql(`DELETE FROM training_assignments a USING training_plan_versions v, people p, users u
         WHERE v.plan_id = a.plan_id AND v.plan_version = a.plan_version AND p.id = a.person_id AND u.id = p.user_id
           AND u.email = '${ROHIT}' AND v.name = '${PLAN}'`);
    sql(`DELETE FROM training_checkins c USING training_assignments a, people p, users u
         WHERE a.id = c.assignment_id AND p.id = a.person_id AND u.id = p.user_id AND u.email = '${ROHIT}' AND c.period_start = '2026-09-28'`);
  });

  test('rahul@ assigns the plan from Trainees & assignments', async ({ page }) => {
    await open(page, DEMO.manager, 'trainees');

    await expect(page.getByRole('tab', { name: 'Trainees & assignments' })).toHaveAttribute('aria-selected', 'true');
    await expect(card(page, 'Mentors')).toContainText('Sara Khan');

    await page.getByRole('button', { name: 'Assign plan' }).click();
    const dialog = await fillDialog(page, { Person: 'Rohit Kulkarni', Plan: PLAN, Mentor: 'Sara Khan', 'Start date': '2026-09-07' });
    await dialog.getByRole('button', { name: 'Assign' }).click();

    await expect(page.getByText('1 plan assigned')).toBeVisible();
    await expect(card(page, 'Trainees & assignments')).toContainText(PLAN);
  });

  test('rohit@ submits this week’s check-in on his onboarding plan', async ({ page }) => {
    await open(page, ROHIT);

    await page.getByRole('tab', { name: /Software Engineer onboarding/ }).click();
    const checkins = card(page, 'Weekly check-ins');
    await checkins.getByRole('button', { name: 'Submit' }).first().click();

    const dialog = await fillDialog(page, { 'What you did': 'Fixed my first bug and paired on code review.', 'Hours spent': '20' });
    await dialog.getByRole('button', { name: 'Submit check-in' }).click();

    await expect(page.getByText('Check-in submitted')).toBeVisible();
    await expect(checkins).toContainText('Submitted');
  });

  test('sara@ records the assessment and writes the mentor review', async ({ page }) => {
    await open(page, SARA, 'mentor');

    const pending = card(page, 'Pending for you');
    await pending.getByRole('row', { name: /Assessment to take.*Rohit Kulkarni/ }).click();

    await page.getByRole('button', { name: 'Record assessment' }).click();
    let dialog = await fillDialog(page, { Date: '2026-09-25', 'Score %': '85', Notes: 'Reviewed five PRs to the standard with clear reasons.' });
    await dialog.getByRole('button', { name: 'Record assessment' }).click();
    await expect(page.getByText('Assessment recorded')).toBeVisible();

    await page.getByRole('button', { name: 'Write mentor review' }).click();
    dialog = await fillDialog(page, {
      'Rating (1–5)': '4',
      'What went well': 'Clear, labelled review comments.',
      'What to improve': 'Ask for context before suggesting rewrites.',
    });
    await dialog.getByRole('button', { name: 'Save review' }).click();
    await expect(page.getByText('Final review saved')).toBeVisible();
  });

  test('rahul@ writes the manager review and decides Completed', async ({ page }) => {
    await open(page, DEMO.manager, 'trainees');

    await card(page, 'Trainees & assignments').getByRole('row', { name: new RegExp(`Rohit Kulkarni.*${PLAN}`) }).click();
    await page.getByRole('button', { name: 'Write manager review & decision' }).click();

    const dialog = await fillDialog(page, {
      'Rating (1–5)': '4',
      'What went well': 'Review quality improved quickly.',
      'What to improve': 'Keep PRs small.',
      Decision: 'Completed',
    });
    await dialog.getByRole('button', { name: 'Save review' }).click();
    await expect(page.getByText('Final review saved')).toBeVisible();
  });

  test('rohit@ signs off and the plan is completed', async ({ page }) => {
    await open(page, ROHIT);

    await page.getByRole('tab', { name: new RegExp(PLAN) }).click();
    await page.getByRole('button', { name: 'Review & sign off feedback' }).click();

    const dialog = await fillDialog(page, { 'Rate the plan (1–5)': '5', 'Rate your mentor (1–5)': '5' });
    await dialog.getByRole('button', { name: 'Sign off' }).click();

    await expect(page.getByText('Signed off')).toBeVisible();
    await expect(page.getByRole('tab', { name: new RegExp(PLAN) })).toContainText('Completed');
  });
});

test.describe('Training screens', () => {
  test('Plan library shows pass rate, average outcome and trainee rating', async ({ page }) => {
    await open(page, DEMO.manager, 'library');

    const library = card(page, 'Plan library');
    await expect(library.getByRole('columnheader', { name: 'Pass rate' })).toBeVisible();
    await expect(library.getByRole('columnheader', { name: 'Avg outcome' })).toBeVisible();
    await expect(library.getByRole('columnheader', { name: 'Trainee rating' })).toBeVisible();
    await expect(library).toContainText('Software Engineer onboarding');

    await page.getByRole('button', { name: 'New plan' }).click();
    await expect(page.getByRole('dialog', { name: 'New training plan' })).toBeVisible();
    await page.keyboard.press('Escape');
  });

  test('An employee without a plan or mentees sees an empty My training', async ({ page }) => {
    await open(page, DEMO.employee);

    await expect(page.getByRole('tab', { name: 'Trainees & assignments' })).toHaveCount(0);
    await expectNoHorizontalScroll(page);
  });
});
