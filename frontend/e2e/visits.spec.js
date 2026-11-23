import { expect, test } from '@playwright/test';
import { sql } from './db.js';
import { DEMO, distinctIp, expectNoHorizontalScroll, signInViaApi } from './helpers.js';

/**
 * Task 18.2: Customer visits screens (spec time/customer-visits) against the real backend (demo seed, today
 * pinned to 28 Sep 2026). rahul@ sees the team and plans a visit for Priya; priya@ logs last week's visit
 * and files the report; sana@ (account owner) records the customer's feedback; rahul@ verifies the points.
 */
const SANA = 'sana@pulse.demo';
const PLANNED = 'E2E Planned Corp';
const LOGGED = 'E2E Logistics';

const h1 = (page) => page.getByRole('heading', { level: 1, name: 'Customer visits' });
const card = (page, name) =>
  page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });

async function open(page, who) {
  await signInViaApi(page, who);
  await page.goto('/visits');
  await expect(h1(page)).toBeVisible();
}

/** Fills a dialog's fields by label (a required label ends in "*"); selects take a text the option contains. */
async function fill(dialog, values) {
  for (const [label, value] of Object.entries(values)) {
    const escaped = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const field = dialog.getByLabel(new RegExp(`^${escaped}\\*?$`));
    const tag = await field.evaluate((el) => el.tagName.toLowerCase());

    if (tag === 'select') {
      await field.selectOption(await field.locator('option', { hasText: value }).first().getAttribute('value'));
    } else {
      await field.fill(value);
    }
  }
}

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test.describe.serial('Visit lifecycle across rahul@, priya@ and sana@', () => {
  test.beforeAll(() => {
    sql(`DELETE FROM visits WHERE customer IN ('${PLANNED}', '${LOGGED}')`);
  });

  test('rahul@ sees the team and plans a visit for Priya', async ({ page }) => {
    await open(page, DEMO.manager);

    const team = card(page, 'Team');
    await expect(team).toContainText('Priya Shah');
    await expect(team.getByRole('columnheader', { name: 'Points' })).toBeVisible();

    await page.getByRole('button', { name: 'Plan visit' }).click();
    const dialog = page.getByRole('dialog', { name: 'Plan a customer visit' });
    await fill(dialog, {
      Engineer: 'Priya Shah',
      Customer: PLANNED,
      City: 'Pune',
      Purpose: 'Health check',
      From: '2026-09-29',
      To: '2026-09-30',
      'Requested by': 'Sana Mirza · account manager',
      'Account owner': 'Sana Mirza',
    });
    await dialog.getByRole('button', { name: 'Plan visit' }).click();

    await expect(page.getByText('Visit planned · the engineer is notified')).toBeVisible();
    await expect(card(page, 'Team visits')).toContainText(PLANNED);
  });

  test('priya@ logs last week’s visit and files the report', async ({ page }) => {
    await open(page, DEMO.employee);

    await page.getByRole('button', { name: 'Log visit' }).click();
    let dialog = page.getByRole('dialog', { name: 'Log a customer visit' });
    await fill(dialog, {
      Customer: LOGGED,
      City: 'Mumbai',
      Purpose: 'Troubleshooting',
      From: '2026-09-22',
      To: '2026-09-23',
      'Requested by': 'Sana Mirza · account manager',
      'Account owner': 'Sana Mirza',
    });
    await dialog.getByRole('button', { name: 'Log visit' }).click();
    await expect(page.getByText('Visit logged')).toBeVisible();

    await card(page, 'Visits · Priya Shah').getByRole('row', { name: new RegExp(LOGGED) }).click();
    await page.getByRole('button', { name: 'File report' }).click();
    dialog = page.getByRole('dialog', { name: `Visit report · ${LOGGED}` });
    await fill(dialog, { 'What was done': 'Fixed the flow exporter and tuned the collector.', 'Hours on site': '12', 'Hours of travel': '4' });
    await dialog.getByRole('button', { name: 'File report' }).click();

    await expect(page.getByText(/Report filed/)).toBeVisible();
    await expect(page.getByRole('dialog', { name: `${LOGGED} · Mumbai` })).toContainText('Feedback pending');
  });

  test('sana@ records the customer’s feedback on her account', async ({ page }) => {
    await open(page, SANA);

    // Sana has no performance profile: her only view is My accounts, so there are no tabs.
    await expect(page.getByRole('tab', { name: 'Team' })).toHaveCount(0);
    await card(page, 'Visits to my accounts').getByRole('row', { name: new RegExp(LOGGED) }).click();
    await page.getByRole('button', { name: 'Record feedback' }).click();
    const dialog = page.getByRole('dialog', { name: `Customer feedback · ${LOGGED}` });
    await fill(dialog, { 'CSAT (1–5)': '5', Date: '2026-09-25' });
    await dialog.getByRole('button', { name: 'Record feedback' }).click();

    await expect(page.getByText('Feedback recorded')).toBeVisible();
    await expect(page.getByRole('dialog', { name: `${LOGGED} · Mumbai` })).toContainText('Awaiting manager');
  });

  test('rahul@ verifies the points', async ({ page }) => {
    await open(page, DEMO.manager);

    await card(page, 'Team visits').getByRole('row', { name: new RegExp(LOGGED) }).click();
    await page.getByRole('button', { name: 'Verify points' }).click();
    await page.getByRole('dialog', { name: /Verify points/ }).getByRole('button', { name: 'Verify' }).click();

    await expect(page.getByText('Points verified')).toBeVisible();
    await expect(page.getByRole('dialog', { name: `${LOGGED} · Mumbai` })).toContainText('Verified');
  });
});

test('Phone width: no page-level horizontal scroll', async ({ page, context }) => {
  await distinctIp(context);
  await page.setViewportSize({ width: 390, height: 844 });
  await open(page, DEMO.manager);
  await expectNoHorizontalScroll(page);
});
