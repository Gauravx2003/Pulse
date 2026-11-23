import { expect, test } from '@playwright/test';
import { sql } from './db.js';
import { DEMO, distinctIp, signInViaApi } from './helpers.js';

/**
 * Task 16.4: Data imports screen (spec admin/data-imports-exports) against the real backend (demo seed).
 * hr@ sees every card, the payroll export, the sources map and the history; uploads a holiday through the
 * three steps and undoes it; a malformed CSV is refused with its message. rahul@ sees only his cards.
 */
const HOLIDAY = 'E2E Founders Day';

const h1 = (page) => page.getByRole('heading', { level: 1, name: 'Data imports' });
const card = (page, name) =>
  page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });

async function open(page, who, query = '') {
  await signInViaApi(page, who);
  await page.goto(`/imports${query}`);
  await expect(h1(page)).toBeVisible();
}

function csv(text, name = 'holidays.csv') {
  return { name, mimeType: 'text/csv', buffer: Buffer.from(text, 'utf8') };
}

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test.describe.serial('Data imports as hr@', () => {
  test.beforeAll(() => {
    sql(`DELETE FROM holidays WHERE name = '${HOLIDAY}'`);
  });

  test('cards, payroll export, sources map and history', async ({ page }) => {
    await open(page, DEMO.admin);

    for (const name of ['Users', 'OKRs', 'Attendance', 'Customer visits', 'Holiday calendar', 'Appraisal proposals']) {
      await expect(card(page, name)).toBeVisible();
    }

    await expect(card(page, 'Holiday calendar')).toContainText('Required: date, name');
    await expect(card(page, 'Where each module gets its data')).toContainText('HR system / biometric');
    await expect(card(page, 'Upload history')).toBeVisible();
  });

  test('a holiday goes through choose, check and done, then is undone', async ({ page }) => {
    await open(page, DEMO.admin);

    await card(page, 'Holiday calendar').getByRole('button', { name: 'Upload CSV' }).click();
    const dialog = page.getByRole('dialog', { name: 'Import Holiday calendar' });
    await dialog.getByLabel('CSV file (at most 5 MB)').setInputFiles(csv(`date,name\n2026-12-28,${HOLIDAY}\n`, 'e2e_holiday.csv'));
    await dialog.getByRole('button', { name: 'Check file' }).click();

    await expect(dialog.getByText(/1 ready/)).toBeVisible();
    await dialog.getByRole('button', { name: 'Import 1 row' }).click();
    await expect(dialog.getByText('1 row imported')).toBeVisible();
    await dialog.getByRole('button', { name: 'Close' }).last().click();

    const row = card(page, 'Upload history').getByRole('row', { name: /e2e_holiday\.csv/ }).first();
    await expect(row).toBeVisible();
    expect(sql(`SELECT count(*) FROM holidays WHERE name = '${HOLIDAY}'`)).toBe('1');

    await row.getByRole('button', { name: 'Undo' }).click();
    await page.getByRole('dialog', { name: 'Undo this import?' }).getByRole('button', { name: 'Undo import' }).click();
    await expect(page.getByText(/Import undone/)).toBeVisible();
    expect(sql(`SELECT count(*) FROM holidays WHERE name = '${HOLIDAY}'`)).toBe('0');
  });

  test('a malformed CSV is refused with its message', async ({ page }) => {
    await open(page, DEMO.admin);

    await card(page, 'Holiday calendar').getByRole('button', { name: 'Upload CSV' }).click();
    const dialog = page.getByRole('dialog', { name: 'Import Holiday calendar' });
    await dialog.getByLabel('CSV file (at most 5 MB)').setInputFiles(csv('when,what\n2026-12-28,Nothing\n', 'broken.csv'));
    await dialog.getByRole('button', { name: 'Check file' }).click();

    await expect(dialog.getByRole('alert')).toContainText(/date/i);
    await expect(dialog.getByRole('button', { name: 'Check file' })).toBeVisible();
  });
});

test('rahul@ sees only OKRs, Users and Customer visits; ?type opens the upload', async ({ page }) => {
  await open(page, DEMO.manager, '?type=visits');

  await expect(page.getByRole('dialog', { name: 'Import Customer visits' })).toBeVisible();
  await page.keyboard.press('Escape');

  for (const name of ['OKRs', 'Users', 'Customer visits']) {
    await expect(card(page, name)).toBeVisible();
  }

  await expect(card(page, 'Attendance')).toHaveCount(0);
  await expect(card(page, 'Holiday calendar')).toHaveCount(0);
});

test('Rules & thresholds links to the Holiday calendar import', async ({ page, context }) => {
  await distinctIp(context);
  await signInViaApi(page, DEMO.admin);
  await page.goto('/rules');

  await page.getByRole('link', { name: 'Import holiday calendar' }).click();
  await expect(page.getByRole('dialog', { name: 'Import Holiday calendar' })).toBeVisible();
});
