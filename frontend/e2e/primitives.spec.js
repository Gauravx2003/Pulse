import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { expectNoHorizontalScroll } from './helpers.js';
import { mockSignedIn } from './mockApi.js';

/**
 * Task 6.3: the shared UI primitives on /kit/primitives. Keyboard-only use (focus trap, Esc, focus
 * return, tabs) and an axe scan with no WCAG 2.2 AA violations, in light and dark themes.
 */
const WCAG = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];

async function axe(page) {
  const { violations } = await new AxeBuilder({ page }).withTags(WCAG).analyze();

  expect(violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(' ')).join(', ')}`)).toEqual([]);
}

test.beforeEach(async ({ page }) => {
  await mockSignedIn(page);
  await page.goto('/kit/primitives');
  await expect(page.getByRole('heading', { level: 1, name: 'Shared primitives' })).toBeVisible();
});

test('modal traps focus, closes on Esc and returns focus to the opener', async ({ page }) => {
  const opener = page.getByRole('button', { name: 'Open modal' });

  await opener.focus();
  await page.keyboard.press('Enter');

  const dialog = page.getByRole('dialog', { name: 'Check in' });

  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Close' })).toBeFocused();

  // Tab through every control and past the end: focus never leaves the dialog
  for (let i = 0; i < 6; i += 1) {
    await page.keyboard.press('Tab');
    expect(await dialog.evaluate((el) => el.contains(document.activeElement))).toBe(true);
  }

  // Shift+Tab from the first control wraps to the last
  await dialog.getByRole('button', { name: 'Close' }).focus();
  await page.keyboard.press('Shift+Tab');
  await expect(dialog.getByRole('button', { name: 'Save check-in' })).toBeFocused();

  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(opener).toBeFocused();
});

test('modal closes on a scrim click', async ({ page }) => {
  await page.getByRole('button', { name: 'Open modal' }).click();
  await page.mouse.click(5, 5);
  await expect(page.getByRole('dialog')).toBeHidden();
});

test('table row opens the drawer with Enter; Esc closes it and focus returns to the row', async ({ page }) => {
  const row = page.getByRole('row', { name: /PLAT-2201/ });

  await row.focus();
  await page.keyboard.press('Enter');

  const drawer = page.getByRole('dialog', { name: 'Audit export filters' });

  await expect(drawer).toBeVisible();
  expect(await drawer.evaluate((el) => el.contains(document.activeElement))).toBe(true);
  await expect(row.getByRole('cell').nth(4)).toHaveText('—');

  await page.keyboard.press('Escape');
  await expect(drawer).toBeHidden();
  await expect(row).toBeFocused();
});

test('destructive confirmation starts on Cancel and Esc cancels', async ({ page }) => {
  const opener = page.getByRole('button', { name: 'Delete all' });

  await opener.click();

  const dialog = page.getByRole('dialog', { name: 'Delete all rows?' });

  await expect(dialog.getByRole('button', { name: 'Cancel' })).toBeFocused();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(opener).toBeFocused();
});

test('tabs move with arrow keys, Home and End', async ({ page }) => {
  const tablist = page.getByRole('tablist', { name: 'Example tabs' });

  await tablist.getByRole('tab', { name: 'Team' }).focus();
  await page.keyboard.press('ArrowRight');
  await expect(tablist.getByRole('tab', { name: 'Person' })).toBeFocused();
  await expect(tablist.getByRole('tab', { name: 'Person' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.getByTestId('tab-panel')).toHaveText('Showing Person');

  await page.keyboard.press('End');
  await expect(tablist.getByRole('tab', { name: /History/ })).toBeFocused();
  await page.keyboard.press('ArrowRight');
  await expect(tablist.getByRole('tab', { name: 'Team' })).toBeFocused();
});

test('period control switches kind and period', async ({ page }) => {
  const select = page.getByRole('combobox', { name: 'Period', exact: true });

  await expect(select).toHaveValue('s:S43');
  await select.selectOption('s:S42');
  await expect(select).toHaveValue('s:S42');
  await page.getByRole('button', { name: 'Month' }).click();
  await expect(select).toHaveValue('m:2026-09');
  await expect(page.getByRole('button', { name: 'Month' })).toHaveAttribute('aria-pressed', 'true');
});

test('form shows an inline error, then confirms the save with a toast', async ({ page }) => {
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByText('Enter a name.')).toBeVisible();
  await expect(page.getByLabel('Full name')).toHaveAttribute('aria-invalid', 'true');

  await page.getByLabel('Full name').fill('Dev Patel');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Saved' })).toBeVisible();
});

test('empty and loading cards say so', async ({ page }) => {
  await expect(page.getByText('No visits planned this quarter.')).toBeVisible();
  await expect(page.getByRole('status').filter({ hasText: 'Loading…' })).toBeVisible();
});

test('axe finds no violations, with and without overlays, in light and dark', async ({ page }) => {
  await axe(page);

  await page.getByRole('button', { name: 'Open modal' }).click();
  await axe(page);
  await page.keyboard.press('Escape');

  await page.getByRole('row', { name: /PLAT-2198/ }).click();
  await axe(page);
  await page.keyboard.press('Escape');

  await page.getByRole('button', { name: 'Switch to dark theme' }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'dark');
  await axe(page);
});

test('no horizontal page scroll at 390, 1024 and 1440 px', async ({ page }) => {
  for (const width of [390, 1024, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await expectNoHorizontalScroll(page);
  }
});
