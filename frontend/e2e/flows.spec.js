import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { sql } from './db.js';
import { DEMO, distinctIp, signInViaApi } from './helpers.js';

/**
 * Task 23.3: the end-to-end flows of reference section 15 ("Test plan" 3) against the real backend (demo
 * seed, today pinned to 28 Sep 2026), each crossing screens and accounts the way a person would.
 *
 * In this file (project "flows", which runs after the module suites and one test at a time, because the
 * flows write attendance, users, visits, OKRs and appraisal rows other suites read):
 *  - extra work → confirm → culture bonus
 *  - seeded Jira work → workload → Discipline → index → appraisal score
 *  - attendance upload → rule h → undo
 *  - each CSV import with its sample file (the Template download)
 *  - appraisal propose → approve → export
 *
 * Flows that already live in their module suites, tagged @flow so `npm run test:flows` runs all of them:
 *  - 360° assign → notify → submit → publish (reviews.spec.js)
 *  - training assign → check-in → assessment → final review → sign-off (training.spec.js)
 *  - visit plan → report → feedback → verify (visits.spec.js)
 */
test.describe.configure({ mode: 'default' });

const PRIYA = 'Priya Shah';

const h1 = (page, name) => page.getByRole('heading', { level: 1, name });
const card = (page, name) =>
  page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });
const toast = (page, text) => page.getByRole('status').filter({ hasText: text });

/**
 * Signs in as `who` on a clean cookie jar (flows switch accounts inside one test). Also clears the tab's
 * per-session view state (selected person, period), as a sign-in through the screen does.
 */
async function as(page, who) {
  if (page.url().startsWith('http')) {
    await page.evaluate(() => sessionStorage.clear());
  }

  await page.context().clearCookies();
  await distinctIp(page.context());
  await signInViaApi(page, who);
}

/** The first number in a text, e.g. "3.62 / 5" → 3.62; null when there is none. */
function num(text) {
  const m = /-?\d+(?:\.\d+)?/.exec(text ?? '');

  return m ? Number(m[0]) : null;
}

/** Clicks a download button and returns the saved file's text. */
async function downloaded(page, click) {
  const [download] = await Promise.all([page.waitForEvent('download'), click()]);

  return readFileSync(await download.path(), 'utf8');
}

// Imports and approvals notify people; drop this file's notices so other suites' unread counts hold.
let notificationsSince = '';

test.beforeAll(() => {
  notificationsSince = sql('SELECT now()::text');
});

test.afterAll(() => {
  sql(`DELETE FROM notifications WHERE created_at >= '${notificationsSince}'`);
});

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

// ---------------------------------------------------------------------------------------------------------

test.describe('Extra work → confirm → culture bonus', { tag: '@flow' }, () => {
  const REF = 'PR #805'; // Priya's pending untracked PR, credited to Resourcefulness
  const TITLE = 'CI cache script — build time 14 → 6 min';
  const reset = () =>
    sql(`UPDATE extra_items SET state = 'pending', decided_by = NULL, decided_at = NULL WHERE ref = '${REF}'`);

  test.beforeAll(reset);
  test.afterAll(reset);

  test('rahul@ confirms Priya’s untracked PR and her Resourcefulness bonus rises', async ({ page }) => {
    const resourcefulness = async () => {
      await page.goto('/culture?value=Resourcefulness');
      const calc = card(page, 'How Resourcefulness is calculated');
      await expect(calc).toBeVisible();

      const formula = calc.getByRole('group', { name: 'Score formula' });

      return num(await formula.locator('> div').filter({ hasText: 'Extra-mile bonus' }).locator('b').innerText());
    };

    await as(page, DEMO.employee);
    const before = await resourcefulness();
    await expect(card(page, 'Evidence log')).not.toContainText(TITLE);

    await as(page, DEMO.manager);
    await page.goto('/effort');
    await expect(h1(page, 'Effort · Platform team')).toBeVisible();
    await page.getByRole('group', { name: 'Period type' }).getByRole('button', { name: 'Month' }).click();
    await page.getByRole('combobox', { name: 'Period' }).selectOption('m:2026-09');
    const review = card(page, 'Review extra-mile points');
    await review.getByRole('button', { name: `Confirm ${REF}` }).click();
    await expect(toast(page, /Confirmed: \+[\d.]+ pts → Resourcefulness/)).toBeVisible();

    await as(page, DEMO.employee);
    const after = await resourcefulness();
    expect(after, 'Resourcefulness extra-mile bonus after the confirm').toBeGreaterThan(before);
    await expect(card(page, 'Evidence log')).toContainText(TITLE);
  });
});

// ---------------------------------------------------------------------------------------------------------

test.describe('Seeded Jira work → workload → Discipline → index → appraisal score', { tag: '@flow' }, () => {
  test('Priya’s numbers carry from Overview to Team utilization, Culture values, Reviews and Appraisal', async ({ page }) => {
    const tile = (group, eyebrow) =>
      group.locator('.pl-stat').filter({ has: page.locator('.pl-eyebrow', { hasText: eyebrow }) }).locator('.pl-stat-value');

    // 1 · Overview: the live sprint's Jira work, as Priya sees it.
    await as(page, DEMO.employee);
    await page.goto('/overview');
    await expect(h1(page, PRIYA)).toBeVisible();
    const live = page.getByRole('group', { name: 'Live sprint' });
    const stale = num(await tile(live, 'No update > 2 days').innerText());
    const workDays = num(await tile(live, 'Work assigned').innerText());
    const utilization = num(await tile(live, 'Utilization').innerText());
    const index = num(await tile(live, 'Performance index').innerText());
    expect([stale, workDays, utilization, index]).not.toContain(null);

    // 2 · Work items: the remaining Jira hours spread over focus hours are the same work days.
    await page.goto('/work-items');
    const remaining = page.getByRole('group', { name: 'Live sprint' }).locator('.pl-stat').filter({ hasText: 'Remaining' });
    await expect(remaining.locator('.pl-stat-foot')).toContainText(`= ${workDays} days`);

    // 3 · Culture values: Discipline from Jira update compliance and rules a–h.
    await page.goto('/culture?value=Discipline');
    const disciplineCard = page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name: /^Discipline · / }) });
    const discipline = num(await tile(disciplineCard.getByRole('group', { name: 'Discipline' }), 'Discipline score').innerText());
    expect(discipline).not.toBeNull();
    await expect(disciplineCard.getByRole('table', { name: 'Discipline rules a–h' })).toContainText('Daily update compliance');

    // 4 · Reviews: the performance index, with the culture pillar that carries Discipline.
    await page.goto('/reviews?tab=performance');
    const indexCard = card(page, 'Performance index');
    expect(num(await indexCard.locator('.rv-index').innerText())).toBeCloseTo(index, 1);
    // The last 4 quarters (3 closed and the live one), which the appraisal's performance part averages.
    const trendLabel = await page.getByRole('img', { name: /^Index trend: / }).getAttribute('aria-label');
    const quarters = [...trendLabel.matchAll(/(Q\d \d{4}) ([\d.]+)/g)].map((m) => Number(m[2])).slice(-4);
    expect(quarters, trendLabel).toHaveLength(4);
    expect(quarters[3], 'live quarter on the trend').toBeCloseTo(index, 1);

    // 5 · Team utilization (manager): the same workload, Discipline and index in Priya's row.
    await as(page, DEMO.manager);
    await page.goto('/team-utilization');
    const row = card(page, 'People').locator('tbody tr').filter({ hasText: PRIYA });
    await expect(row).toBeVisible();
    const cells = await row.locator('td').allInnerTexts();
    const [, , rowStale, rowWork, , rowUtil, , rowDiscipline, rowIndex] = cells;
    expect(num(rowStale), 'Stale').toBe(stale);
    expect(num(rowWork), 'Work').toBeCloseTo(workDays, 1);
    expect(num(rowUtil), 'Utilization').toBe(utilization);
    expect(num(rowDiscipline), 'Discipline').toBeCloseTo(discipline, 1);
    expect(num(rowIndex), 'Index').toBeCloseTo(index, 1);

    // 6 · Appraisal: Perf. is the 4-quarter index, Discipline the same number, and the score their weighted mix.
    await page.goto('/appraisal');
    await expect(h1(page, 'Appraisal & increments · 2026')).toBeVisible();
    const rules = await card(page, 'How the increment is decided').locator('p').first().innerText();
    const weight = (label) => Number(new RegExp(`${label} (\\d+)%`).exec(rules)[1]);
    const w = { perf: weight('Performance index'), r360: weight('360° feedback'), mgr: weight('Manager review'), disc: weight('Discipline') };
    const apr = card(page, 'People').locator('tbody tr').filter({ hasText: PRIYA });
    await expect(apr).toBeVisible();
    const [, perf, r360, mgr, disc, total] = (await apr.locator('td').allInnerTexts()).map(num);
    // Perf. is the index over the last 4 quarters (reference 12: "performance index 50% (last 4 quarters)").
    const yearIndex = quarters.reduce((sum, q) => sum + q, 0) / quarters.length;
    expect(Math.abs(perf - yearIndex), `Perf. ${perf} vs mean of ${quarters.join(', ')}`).toBeLessThanOrEqual(0.06);
    expect(disc, 'Discipline').toBeCloseTo(discipline, 1);

    const parts = [[perf, w.perf], [r360, w.r360], [mgr, w.mgr], [disc, w.disc]].filter(([v]) => v !== null);
    const mix = parts.reduce((s, [v, k]) => s + v * k, 0) / parts.reduce((s, [, k]) => s + k, 0);
    expect(total, 'Score = weighted parts, missing parts re-weighted').toBeCloseTo(mix, 1);
  });
});

// ---------------------------------------------------------------------------------------------------------

test.describe('Attendance upload → rule h → undo', { tag: '@flow' }, () => {
  const FILE = 'e2e_flow_attendance.csv';
  const DAYS = ['2026-08-25', '2026-08-26', '2026-08-27'];

  test('three unexplained absences in August lower rule h, and undo restores it', async ({ page }) => {
    const ruleH = async () => {
      await page.goto('/culture?value=Discipline');
      const row = page.getByRole('table', { name: 'Discipline rules a–h' }).getByRole('row', { name: /Attendance & leave/ });
      await expect(row).toBeVisible();

      return row.innerText();
    };

    await as(page, DEMO.employee);
    const before = await ruleH();
    expect(before).not.toContain('absent without leave');

    await as(page, DEMO.admin);
    await page.goto('/imports?type=attendance');
    const dialog = page.getByRole('dialog', { name: 'Import Attendance' });
    await dialog.getByLabel('Mode').selectOption('merge_days');
    const rows = DAYS.map((d) => `priya@pulse.demo,${d},Absent,,,,`).join('\n');
    await dialog.getByLabel('CSV file (at most 5 MB)').setInputFiles({
      name: FILE,
      mimeType: 'text/csv',
      buffer: Buffer.from(`employee_email,date,status,leave_type,applied_on,check_in,check_out\n${rows}\n`, 'utf8'),
    });
    await dialog.getByRole('button', { name: 'Check file' }).click();
    await dialog.getByRole('button', { name: 'Import 3 rows' }).click();
    await expect(dialog.getByText('3 rows imported')).toBeVisible();
    await dialog.getByRole('button', { name: 'Close' }).last().click();

    await as(page, DEMO.employee);
    const uploaded = await ruleH();
    expect(uploaded).toContain('3 absent without leave');
    expect(uploaded).toContain('Improve');

    await as(page, DEMO.admin);
    await page.goto('/imports');
    await card(page, 'Upload history').getByRole('row', { name: new RegExp(FILE) }).first().getByRole('button', { name: 'Undo' }).click();
    await page.getByRole('dialog', { name: 'Undo this import?' }).getByRole('button', { name: 'Undo import' }).click();
    await expect(page.getByText(/Import undone/)).toBeVisible();

    await as(page, DEMO.employee);
    expect(await ruleH()).toBe(before);
  });
});

// ---------------------------------------------------------------------------------------------------------

test.describe('Each CSV import with its sample file', { tag: '@flow' }, () => {
  const TYPES = ['Users', 'OKRs', 'Attendance', 'Customer visits', 'Holiday calendar'];

  for (const name of TYPES) {
    test(`${name}: the Template file checks, imports and undoes`, async ({ page }) => {
      await as(page, DEMO.admin);
      await page.goto('/imports');
      const typeCard = card(page, name);

      const [download] = await Promise.all([page.waitForEvent('download'), typeCard.getByRole('button', { name: 'Template' }).click()]);
      const file = download.suggestedFilename();
      const buffer = readFileSync(await download.path());
      expect(buffer.toString('utf8').trim().split('\n'), 'header and one example row').toHaveLength(2);

      await typeCard.getByRole('button', { name: 'Upload CSV' }).click();
      const dialog = page.getByRole('dialog', { name: `Import ${name}` });
      await dialog.getByLabel('CSV file (at most 5 MB)').setInputFiles({ name: file, mimeType: 'text/csv', buffer });
      await dialog.getByRole('button', { name: 'Check file' }).click();
      await expect(dialog.getByText(/^1 ready .* 0 with errors/)).toBeVisible();
      await dialog.getByRole('button', { name: 'Import 1 row' }).click();
      await expect(dialog.getByText('1 row imported')).toBeVisible();
      await dialog.getByRole('button', { name: 'Close' }).last().click();

      const row = card(page, 'Upload history').getByRole('row', { name: new RegExp(file.replace('.', '\\.')) }).first();
      await row.getByRole('button', { name: 'Undo' }).click();
      await page.getByRole('dialog', { name: 'Undo this import?' }).getByRole('button', { name: 'Undo import' }).click();
      await expect(page.getByText(/Import undone/)).toBeVisible();
      await expect(row.getByText('Undone')).toBeVisible();
    });
  }
});

// ---------------------------------------------------------------------------------------------------------

test.describe('Appraisal propose → approve → export', { tag: '@flow' }, () => {
  const reset = () =>
    sql(`DELETE FROM appraisal_proposals a USING appraisal_cycles c WHERE c.id = a.cycle_id AND c.year = 2026`);

  test.beforeAll(reset);
  test.afterAll(reset);

  test('rahul@ applies the recommendations and submits; hr@ approves Priya; the payroll export carries her', async ({ page }) => {
    await as(page, DEMO.manager);
    await page.goto('/appraisal');
    await expect(h1(page, 'Appraisal & increments · 2026')).toBeVisible();

    await page.getByRole('button', { name: 'Apply recommendations' }).click();
    await expect(toast(page, /Recommendations applied to \d+ people/)).toBeVisible();
    const row = card(page, 'People').locator('tbody tr').filter({ hasText: PRIYA });
    const proposed = await row.getByRole('spinbutton', { name: `Proposed increment for ${PRIYA}` }).inputValue();
    expect(Number(proposed)).toBeGreaterThan(0);

    await page.getByRole('button', { name: 'Submit to HR' }).click();
    await expect(toast(page, /\d+ proposals submitted to HR/)).toBeVisible();
    await expect(row).toContainText('Submitted');

    await as(page, DEMO.admin);
    await page.goto('/appraisal');
    const hrRow = card(page, 'People').locator('tbody tr').filter({ hasText: PRIYA });
    await hrRow.getByRole('button', { name: `Approve ${PRIYA}` }).click();
    await expect(toast(page, `Approved · ${PRIYA}`)).toBeVisible();
    await expect(hrRow).toContainText('Approved');

    await page.goto('/imports');
    const csv = await downloaded(page, () =>
      card(page, 'Appraisal proposals').getByRole('button', { name: 'Export for payroll' }).click(),
    );
    const lines = csv.trim().split('\n');
    const header = lines[0].split(',');
    const priya = lines.find((l) => l.includes(PRIYA))?.split(',');
    expect(priya, 'Priya in the payroll export').toBeTruthy();
    expect(Number(priya[header.indexOf('increment_pct')])).toBe(Number(proposed));
    expect(priya[header.indexOf('status')]).toBe('Approved');
    expect(lines.filter((l) => l.includes(',Submitted,'))).toHaveLength(0);
  });
});
