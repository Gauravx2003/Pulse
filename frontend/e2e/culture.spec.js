import { expect, test } from '@playwright/test';
import { sql } from './db.js';
import { DEMO, distinctIp, expectNoHorizontalScroll, signInViaApi } from './helpers.js';

/**
 * Task 19.2: Culture values screens (spec culture/culture-values) against the real backend (demo seed,
 * today pinned to 28 Sep 2026, Q3 2026 live). priya@ reads her values, the calculation detail, Moral &
 * Ethics, Discipline and the rules table; rahul@ overrides Priya's Discipline and reverts it, and gives
 * kudos that land in her evidence log.
 */
const KUDOS = 'E2E culture: paired on the sync bug all afternoon';

const h1 = (page) => page.getByRole('heading', { level: 1, name: 'Culture values' });
const card = (page, name) =>
  page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });
const value = (page, name) => page.locator('.pl-value-row', { hasText: name }).first();

async function open(page, who, query = '') {
  await signInViaApi(page, who);
  await page.goto(`/culture${query}`);
  await expect(h1(page)).toBeVisible();
}

const cleanUp = () => {
  sql(`DELETE FROM discipline_overrides o USING people p WHERE p.id = o.person_id AND p.name = 'Priya Shah'`);
  sql(`DELETE FROM kudos WHERE message = '${KUDOS}'`);
};

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test.describe('Culture values as priya@', () => {
  test('values, radar, calculation detail with anchors, and the rules table', async ({ page }) => {
    await open(page, DEMO.employee);

    await expect(page.getByRole('img', { name: /Culture values: Priya vs Team average/ })).toBeVisible();
    await expect(page.locator('.pl-value-row')).toHaveCount(11);

    await value(page, 'Decision Making').click();
    const calc = card(page, 'How Decision Making is calculated');
    await expect(calc.getByRole('group', { name: 'Score formula' })).toContainText('360° rating');
    await expect(calc.getByRole('row', { name: /Decisions documented/ })).toContainText('Confluence · Q3');
    await expect(calc.getByRole('row', { name: /Manager/ })).toBeVisible();
    await expect(page.locator('.cv-anchors li.is-current')).toHaveCount(1);
    await expect(card(page, 'Evidence log')).toBeVisible();

    await value(page, 'Moral & Ethics').click();
    await expect(card(page, 'How Moral & Ethics is calculated')).toContainText('360° only');

    const rules = card(page, 'Calculation rules for all 11 values');
    await expect(rules.getByRole('row')).toHaveCount(12);
    await rules.getByRole('row', { name: /Problem Solving/ }).click();
    await expect(card(page, 'How Problem Solving is calculated')).toBeVisible();
  });

  test('Discipline shows rules a–h without the override control; Q2 has no live scores; phone width', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await open(page, DEMO.employee, '?value=Discipline');

    const discipline = page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name: /^Discipline · / }) });
    await expect(discipline.getByRole('table', { name: 'Discipline rules a–h' })).toBeVisible();
    await expect(discipline.getByRole('button', { name: 'Override score' })).toHaveCount(0);
    await expectNoHorizontalScroll(page);

    await page.getByLabel('Period').selectOption({ label: 'Q2 2026' });
    await expect(page.getByText('No culture scores for Q2 2026')).toBeVisible();
  });
});

test.describe.serial('Culture values as rahul@', () => {
  test.beforeAll(cleanUp);
  test.afterAll(cleanUp);

  test('overrides Priya’s Discipline with a reason and returns to the automatic score', async ({ page }) => {
    await open(page, DEMO.manager, '?value=Discipline');
    await page.getByLabel('Person').selectOption({ label: 'Priya Shah' });

    const discipline = page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name: /^Discipline · / }) });
    await discipline.getByRole('button', { name: 'Override score' }).click();

    const dialog = page.getByRole('dialog', { name: /Override Discipline score/ });
    await dialog.getByLabel(/^Score/).selectOption('4.25');
    await dialog.getByLabel(/^Reason/).fill('Ran the release rota for the whole sprint');
    await dialog.getByRole('button', { name: 'Save override' }).click();

    await expect(page.getByText('Discipline override saved')).toBeVisible();
    await expect(discipline).toContainText('Manager override to 4.25');
    await expect(value(page, 'Discipline')).toContainText('4.3');

    await discipline.getByRole('button', { name: 'Use auto score' }).click();
    await page.getByRole('dialog', { name: 'Use the automatic score?' }).getByRole('button', { name: 'Use auto score' }).click();
    await expect(page.getByText('Discipline uses the automatic score again')).toBeVisible();
    await expect(discipline).not.toContainText('Manager override');
  });

  test('gives Priya kudos that appear in her evidence log', async ({ page }) => {
    await open(page, DEMO.manager, '?value=Collaboration');
    await page.getByLabel('Person').selectOption({ label: 'Priya Shah' });

    await card(page, 'Evidence log').getByRole('button', { name: 'Give kudos' }).click();
    const dialog = page.getByRole('dialog', { name: 'Give kudos' });
    await dialog.getByLabel(/^To/).selectOption({ label: 'Priya Shah' });
    await dialog.getByLabel(/^What did they do/).fill(KUDOS);
    await dialog.getByRole('button', { name: 'Give kudos' }).click();
    await expect(page.getByText('Kudos given')).toBeVisible();

    // Kudos carry the database time; place this one inside the pinned quarter and reload.
    sql(`UPDATE kudos SET given_at = '2026-09-27T10:00:00+05:30' WHERE message = '${KUDOS}'`);
    await page.reload();
    await expect(card(page, 'Evidence log')).toContainText(KUDOS);
  });
});
