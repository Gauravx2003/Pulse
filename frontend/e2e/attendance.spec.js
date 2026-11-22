import { expect, test } from '@playwright/test';
import { DEMO, distinctIp, expectNoHorizontalScroll, signInViaApi } from './helpers.js';

/**
 * Task 17.4: Attendance screens (spec time/attendance "Views and privacy") against the real backend
 * (demo seed, today pinned to 28 Sep 2026: Apr–Aug uploaded, Sep not uploaded, default period Aug).
 * Read-only: uploads are covered by the Data imports tests.
 */
const h1 = (page, name) => page.getByRole('heading', { level: 1, name });
const card = (page, name) =>
  page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });
const period = (page) => page.getByRole('combobox', { name: 'Period', exact: true });

async function open(page, who) {
  await signInViaApi(page, who);
  await page.goto('/attendance');
}

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test.describe('Attendance', () => {
  test('Manager: Team tab with KPIs, people, day-by-day grid and monthly uploads; no upload', async ({ page }) => {
    await open(page, DEMO.manager);

    await expect(h1(page, 'Team attendance')).toBeVisible();
    await expect(page.getByRole('tab', { name: 'Team' })).toHaveAttribute('aria-selected', 'true');
    await expect(period(page)).toHaveValue('m:2026-08');
    await expect(period(page).locator('option[value="m:2026-09"]')).toHaveText('Sep 2026 · not uploaded');
    await expect(period(page).locator('optgroup[label="Quarter"] option')).not.toHaveCount(0);

    const kpis = page.getByRole('group', { name: 'Team attendance' });
    await expect(kpis.locator('.pl-stat')).toHaveCount(5);
    await expect(kpis).toContainText('Absent without leave');

    const people = card(page, 'People · Aug 2026');
    await expect(people.getByRole('row')).not.toHaveCount(1);
    await expect(people.getByRole('columnheader', { name: 'Late' })).toBeVisible();
    await expect(people).toContainText('Neha Joshi');

    const grid = page.getByRole('table', { name: 'Day by day · Aug 2026' });
    await expect(grid).toBeVisible();
    // Aug 2026 has 21 weekdays: one header row and one row per person, each with 21 day cells.
    await expect(grid.getByRole('row').first().getByRole('columnheader')).toHaveCount(22);
    await expect(grid.getByRole('row').nth(1).getByRole('cell')).toHaveCount(21);
    await expect(card(page, 'Day by day')).toContainText('Sick or emergency (excused)');

    const uploads = card(page, 'Monthly uploads');
    await expect(uploads).toContainText('attendance_2026-08.csv');
    await expect(uploads).toContainText('Due after month end');

    await expect(page.getByRole('link', { name: 'Upload monthly CSV' })).toHaveCount(0);
    await expect(uploads.getByRole('link', { name: /^(Upload|Replace)/ })).toHaveCount(0);
  });

  test('Not-uploaded banner: Upload CSV for HR only', async ({ page, browser }) => {
    await open(page, DEMO.admin);
    await period(page).selectOption('m:2026-09');

    const banner = page.locator('.pl-alert').filter({ hasText: 'Sep 2026 attendance has not been uploaded yet' });
    await expect(banner).toBeVisible();
    await expect(banner.getByRole('link', { name: 'Upload CSV' })).toHaveAttribute('href', '/imports?type=attendance');
    await expect(page.getByRole('link', { name: 'Upload monthly CSV' })).toBeVisible();
    await expect(card(page, 'No attendance for Sep 2026')).toBeVisible();
    await expect(card(page, 'Monthly uploads').getByRole('link', { name: 'Replace Aug 2026' })).toBeVisible();

    const ctx = await browser.newContext();
    await distinctIp(ctx);
    const other = await ctx.newPage();
    await open(other, DEMO.manager);
    await period(other).selectOption('m:2026-09');
    const managerBanner = other.locator('.pl-alert').filter({ hasText: 'HR uploads each month after it closes' });
    await expect(managerBanner).toBeVisible();
    await expect(managerBanner.getByRole('link', { name: 'Upload CSV' })).toHaveCount(0);
    await ctx.close();
  });

  test('Manager opens a direct report: calendar, score, leave, late check-ins, month by month, culture', async ({ page }) => {
    await open(page, DEMO.manager);
    await card(page, 'People · Aug 2026').getByRole('row', { name: /Arjun Mehta/ }).click();

    await expect(h1(page, 'Arjun Mehta · attendance')).toBeVisible();
    await expect(page.getByRole('tab', { name: 'Person' })).toHaveAttribute('aria-selected', 'true');

    const cal = page.getByRole('list', { name: 'Calendar · Aug 2026' });
    // Aug 2026 shows Mon 27 Jul to Sun 6 Sep (6 weeks); days outside the month are hidden from screen readers.
    await expect(cal.locator('li')).toHaveCount(42);
    await expect(cal.getByRole('listitem')).toHaveCount(31);
    await expect(cal.locator('.att-late')).toHaveCount(3);

    await expect(card(page, 'How the score is built')).toContainText('Punctuality');
    await expect(card(page, 'Leave · Aug 2026')).toContainText('Sick');
    await expect(card(page, 'Late check-ins')).toBeVisible();

    const months = card(page, 'Month by month');
    await expect(months.getByRole('columnheader', { name: 'Late' })).toBeVisible();
    await months.getByRole('row', { name: /Jul 2026/ }).click();
    await expect(period(page)).toHaveValue('m:2026-07');
    await expect(page.getByRole('list', { name: 'Calendar · Jul 2026' })).toBeVisible();

    await expect(card(page, 'How attendance feeds culture values')).toContainText('Discipline · rule h');
  });

  test('HR sees a person without check-in times (not their direct manager)', async ({ page }) => {
    await open(page, DEMO.admin);
    await card(page, 'People · Aug 2026').getByRole('row', { name: /Arjun Mehta/ }).click();

    await expect(h1(page, 'Arjun Mehta · attendance')).toBeVisible();
    await expect(card(page, 'How the score is built')).toBeVisible();
    await expect(card(page, 'How the score is built')).not.toContainText('Punctuality');
    await expect(card(page, 'Late check-ins')).toHaveCount(0);
    await expect(page.locator('.att-late')).toHaveCount(0);
    await expect(card(page, 'Month by month').getByRole('columnheader', { name: 'Late' })).toHaveCount(0);
  });

  test('Employee sees only their own record, no Team tab and no upload', async ({ page }) => {
    await open(page, DEMO.employee);

    await expect(h1(page, 'Priya Shah · my attendance')).toBeVisible();
    await expect(page.getByRole('tab')).toHaveCount(0);
    await expect(page.getByRole('link', { name: 'Upload monthly CSV' })).toHaveCount(0);
    await expect(card(page, 'Late check-ins')).toBeVisible();

    await period(page).selectOption({ label: 'Q3 2026' });
    const banner = page.locator('.pl-alert').filter({ hasText: 'Sep 2026 attendance has not been uploaded yet' });
    await expect(banner).toBeVisible();
    await expect(banner.getByRole('link')).toHaveCount(0);
    await expect(page.getByRole('list', { name: 'Calendar · Q3 2026' })).toBeVisible();
  });

  test('Phone width: the day-by-day grid fits without sideways scroll', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await open(page, DEMO.manager);

    const grid = page.getByRole('table', { name: 'Day by day · Aug 2026' });
    await expect(grid).toBeVisible();
    await expectNoHorizontalScroll(page);

    const gridBox = await grid.boundingBox();
    const cardBox = await card(page, 'Day by day').boundingBox();
    expect(gridBox.x + gridBox.width).toBeLessThanOrEqual(cardBox.x + cardBox.width + 1);

    await card(page, 'People · Aug 2026').getByRole('row', { name: /Arjun Mehta/ }).click();
    await expect(page.getByRole('list', { name: 'Calendar · Aug 2026' })).toBeVisible();
    await expectNoHorizontalScroll(page);
  });
});
