import { expect, test } from '@playwright/test';
import { DEMO, distinctIp, expectNoHorizontalScroll, signInViaApi } from './helpers.js';

/**
 * Task 22.2: Appraisal & increments screens (spec appraisal/appraisal) against the real backend (demo seed,
 * today pinned to 28 Sep 2026, cycle 2026 effective 1 Oct). The manager's cap check is rejected by the
 * server, so nothing is written; HR only opens panels.
 */
const h1 = (page, name) => page.getByRole('heading', { level: 1, name });
const card = (page, name) =>
  page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });

async function open(page, who) {
  await signInViaApi(page, who);
  await page.goto('/appraisal');
}

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test.describe('Appraisal & increments', () => {
  test('Manager: KPIs, distribution, people table with inline percent; the 20% cap is enforced', async ({ page }) => {
    await open(page, DEMO.manager);

    await expect(h1(page, 'Appraisal & increments · 2026')).toBeVisible();
    await expect(page.getByText('Manager view · your team · confidential')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Apply recommendations' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Submit to HR' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Release letters' })).toHaveCount(0);

    const kpis = page.getByRole('group', { name: 'Appraisal summary' });
    await expect(kpis.locator('.pl-stat')).toHaveCount(5);
    await expect(kpis).toContainText('Increment budget');

    await expect(card(page, 'Rating distribution')).toContainText('Outstanding');
    await expect(card(page, 'How the increment is decided')).toContainText('Set by HR');

    const people = card(page, 'People');
    await expect(people).toContainText('Priya Shah');
    await expect(people.getByRole('columnheader', { name: 'Proposed %' })).toBeVisible();

    // Spec "Cap": 22% is rejected by the server and the value stays as it was.
    const input = people.getByRole('spinbutton').first();
    if (await input.count()) {
      const before = await input.inputValue();
      await input.fill('22');
      await input.press('Tab');
      await expect(page.getByText(/between 0 and 20%/)).toBeVisible();
      await expect(input).toHaveValue(before);
    }

    // The evidence panel opens from a name.
    await people.getByRole('button', { name: /Priya Shah/ }).click();
    const dialog = page.getByRole('dialog');
    await expect(dialog.getByText('Score breakdown')).toBeVisible();
    await expect(dialog.getByText('Evidence for the year')).toBeVisible();
    await expect(dialog).not.toContainText('@pulse.demo');
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
  });

  test('HR: approve and release actions, standards modal with the weights', async ({ page }) => {
    await open(page, DEMO.admin);

    await expect(page.getByText('HR · all teams · confidential')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Approve all submitted' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Release letters' })).toBeVisible();

    await card(page, 'How the increment is decided').getByRole('button', { name: 'Edit standards' }).click();
    const dialog = page.getByRole('dialog', { name: 'Appraisal standards' });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByLabel('Performance index %')).toHaveValue('50');
    await expect(dialog.getByText('Total 100%', { exact: true })).toBeVisible();
    await dialog.getByRole('button', { name: 'Cancel' }).click();
    await expect(dialog).toHaveCount(0);

    // Releasing asks first.
    await page.getByRole('button', { name: 'Release letters' }).click();
    await expect(page.getByRole('dialog', { name: 'Release increment letters?' })).toBeVisible();
    await page.getByRole('button', { name: 'Cancel' }).click();
  });

  test('Employee: no access to the screen', async ({ page }) => {
    await open(page, DEMO.employee);

    await expect(page.getByRole('heading', { name: "You don't have access" })).toBeVisible();
  });

  test('Phone width: no page-level horizontal scroll', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await open(page, DEMO.manager);

    await expect(h1(page, 'Appraisal & increments · 2026')).toBeVisible();
    await expectNoHorizontalScroll(page);
  });
});
