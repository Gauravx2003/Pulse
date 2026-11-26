import { expect, test } from '@playwright/test';
import { DEMO, signIn } from './helpers.js';

/**
 * Task 24.10 (change pulse-v2-live-connectors): a database with no sprint yet (a live setup before its
 * first Jira sync) has months but no sprints. Screens that offer sprints first must open on the month and
 * load, instead of showing "Loading…" forever with no period chosen.
 */
test('with no sprint yet, Overview and Effort open on the month and load', async ({ page }) => {
  await page.route(/\/api\/v1\/periods$/, async (route) => {
    const res = await route.fetch();
    const json = await res.json();

    json.sprints = [];
    json.activeSprint = { notApplicable: true };
    await route.fulfill({ response: res, json });
  });

  await signIn(page, DEMO.employee);

  for (const path of ['/overview', '/effort']) {
    await page.goto(path);

    const main = page.locator('main');

    await expect(main.getByRole('group', { name: 'Period type' }).getByRole('button', { name: 'Month' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    await expect(main.getByRole('combobox', { name: 'Period' })).toHaveValue('m:2026-09');
    await expect(main.getByText('Loading…')).toHaveCount(0);
  }
});
