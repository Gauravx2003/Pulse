import { expect, test } from '@playwright/test';
import { sql } from './db.js';
import { DEMO, distinctIp, expectNoHorizontalScroll, signIn } from './helpers.js';

/**
 * Task 11.3: the Effort screens against the real backend (demo seed, today pinned to 28 Sep 2026),
 * following reference 7.4 "Screen detail — Effort": the Team tab for rahul@ (KPIs, people, requesters,
 * review with Confirm and Reject, Confirm all), the evidence modal, and the Person tab for priya@
 * (planning signal, KPIs, where the time went, daily log, items, culture updates). The items a test
 * decides are set back to pending first, so repeated runs pass.
 */
const h1 = (page, name) => page.getByRole('heading', { level: 1, name });
const card = (page, name) => page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });
const toast = (page, text) => page.getByRole('status').filter({ hasText: text });
const period = (page) => page.getByRole('combobox', { name: 'Period' });

async function chooseMonth(page, key) {
  await page.getByRole('group', { name: 'Period type' }).getByRole('button', { name: 'Month' }).click();
  await period(page).selectOption(key);
}

function pending(ref) {
  sql(`UPDATE extra_items SET state = 'pending', decided_by = NULL, decided_at = NULL WHERE ref = '${ref}'`);
}

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test('Rahul reviews the team: KPIs, people, requesters and the items to confirm', async ({ page }) => {
  pending('SUP-8831');
  pending('SUP-8845');

  await signIn(page, DEMO.manager);
  await page.goto('/effort');
  await chooseMonth(page, 'm:2026-09');

  await expect(h1(page, 'Effort · Platform team')).toBeVisible();
  await expect(page.getByRole('group', { name: 'Team effort' }).locator('.pl-stat')).toHaveCount(4);

  const people = card(page, 'People · Sep 2026');

  await expect(people.locator('tbody tr')).toHaveCount(8);
  await expect(card(page, 'Who gives unplanned work').locator('li')).not.toHaveCount(0);

  const review = card(page, 'Review extra-mile points');

  await expect(review.getByRole('button', { name: 'Confirm all done items' })).toBeEnabled();

  await review.getByRole('button', { name: 'Confirm SUP-8831' }).click();
  await expect(toast(page, 'Confirmed: +1 pts → Collaboration')).toBeVisible();
  await expect(review.getByRole('button', { name: 'Confirm SUP-8831' })).toHaveCount(0);

  await review.getByRole('button', { name: 'Reject SUP-8845' }).click();
  await expect(toast(page, 'Not counted')).toBeVisible();

  await review.locator('li').filter({ hasText: 'SUP-8822' }).getByRole('button').first().click();

  const modal = page.getByRole('dialog');

  await expect(modal).toContainText('How it was detected');
  await expect(modal).toContainText('Culture values credited');
  await modal.getByRole('button', { name: 'Close', exact: true }).last().click();

  await expectNoHorizontalScroll(page);

  await people.locator('tbody tr').filter({ hasText: 'Priya Shah' }).click();
  await expect(h1(page, 'Priya Shah')).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Person' })).toHaveAttribute('aria-selected', 'true');
});

test('Priya sees her own effort without the Team tab or confirm buttons', async ({ page }) => {
  await signIn(page, DEMO.employee);
  await page.goto('/effort');
  await chooseMonth(page, 'm:2026-09');

  await expect(h1(page, 'Priya Shah')).toBeVisible();
  await expect(page.getByRole('tab', { name: 'Team' })).toHaveCount(0);

  await expect(page.locator('.pl-alert').filter({ hasText: 'Planning' })).toContainText(
    'This is a planning signal for the manager, not a performance problem.',
  );
  await expect(page.getByRole('group', { name: 'Effort · Sep 2026' }).locator('.pl-stat')).toHaveCount(5);

  await expect(card(page, 'Where the time went')).toContainText('Planned sprint work');
  await expect(card(page, 'Daily log · Sep 2026').locator('.day')).toHaveCount(30);

  const items = card(page, 'Unplanned & extra work');

  await expect(items.locator('li')).not.toHaveCount(0);
  await expect(items.getByRole('button', { name: /^Confirm / })).toHaveCount(0);

  await expect(card(page, 'Culture value updates from extra work').locator('tbody tr')).not.toHaveCount(0);

  await page.getByRole('group', { name: 'Period type' }).getByRole('button', { name: 'Sprint' }).click();
  await expect(card(page, 'Sprint by sprint')).toBeVisible();
  await expectNoHorizontalScroll(page);
});
