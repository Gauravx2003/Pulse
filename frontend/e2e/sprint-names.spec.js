import { expect, test } from '@playwright/test';
import { DEMO, expectNoHorizontalScroll, signIn } from './helpers.js';

/**
 * Task 2.2 (change jira-sprint-names): the screens show a sprint's name where they name a sprint, as the
 * server labels it (the Jira name, else "Sprint N"). The seed has no named sprint, so the API answers are
 * stubbed as for a live sprint with a long Jira name: the active sprint S43 is renamed in the period list,
 * on Work items rows (`sprintName`), on the release cards (`sprintNames`) and on the Action Center forecast
 * (`sprintName`). Keys stay as they are. At phone width the period control stays within the page.
 */
const LONG = 'Atlas 2.4.1 — Collector platform hardening and trap pipeline rework';

const card = (page, name) => page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });
const period = (page) => page.locator('main').getByRole('combobox', { name: 'Period' });

/** Picks a period of a kind in the page's period control, and waits for it to be the chosen one. */
async function choose(page, kind, key) {
  await page.locator('main').getByRole('group', { name: 'Period type' }).getByRole('button', { name: kind }).click();
  await period(page).selectOption(key);
  await expect(period(page)).toHaveValue(key);
}

/** Renames S43 wherever a payload names it, as the server would for a sprint named LONG. */
function rename(value) {
  if (Array.isArray(value)) {
    value.forEach(rename);
  } else if (value && typeof value === 'object') {
    if (value.key === 's:S43' && typeof value.label === 'string') {
      value.label = value.label.replace('Sprint 43', LONG);
      value.short = LONG;
    }

    if (value.key === 's:S43' && typeof value.name === 'string') {
      value.name = LONG;
    }

    if (value.sprintKey === 'S43') {
      value.sprintName = LONG;
    }

    if (value.sprint === 's:S43' && 'sprintName' in value) {
      value.sprintName = LONG;
    }

    Object.values(value).forEach(rename);
  }
}

test.beforeEach(async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });

  await page.route(/\/api\/v1\/(periods|work\/people|utilization\/team|action_center\/(forecast|overview))/, async (route) => {
    if (route.request().method() !== 'GET') {
      await route.continue();

      return;
    }

    const res = await route.fetch();
    const json = await res.json();

    rename(json);
    await route.fulfill({ response: res, json });
  });
});

test('Priya sees the long name in the period control, the release card and Work items', async ({ page }) => {
  await signIn(page, DEMO.employee);
  await page.goto('/overview');

  // the shared period is per person and other suites move Priya's: choose the active sprint explicitly. The
  // control is capped and carries the full label as its title
  await choose(page, 'Sprint', 's:S43');
  await expect(period(page)).toHaveAttribute('title', `${LONG} · 21 Sep – 9 Oct (active)`);

  const box = await period(page).boundingBox();

  expect(box.x + box.width, 'the period control ends inside the page').toBeLessThanOrEqual(390);
  await expectNoHorizontalScroll(page);

  await choose(page, 'Release', 'r:8.3');

  await expect(card(page, 'NMS 8.3 · release 8.3')).toContainText(LONG);

  await page.goto('/work-items');
  await choose(page, 'Release', 'r:8.3');

  const issues = card(page, 'Issues · Release 8.3');

  await expect(issues.locator('tbody tr').first()).toContainText(LONG);
  await expect(card(page, 'NMS 8.3 · release 8.3')).toContainText(LONG);
  await expectNoHorizontalScroll(page);
});

test('Rahul sees the long name on the team release card and the Action Center forecast', async ({ page }) => {
  await signIn(page, DEMO.manager);
  await page.goto('/actions');

  await expect(card(page, `${LONG} forecast`)).toBeVisible();
  await expectNoHorizontalScroll(page);

  await page.goto('/team-utilization');
  await choose(page, 'Release', 'r:8.3');

  await expect(card(page, 'NMS 8.3 · release 8.3')).toContainText(LONG);
  await expectNoHorizontalScroll(page);
});
