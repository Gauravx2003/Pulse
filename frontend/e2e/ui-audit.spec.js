import { expect, test } from '@playwright/test';
import { DEMO, emailOf, signInViaApi } from './helpers.js';

/**
 * The shared UI audit (task 6.6, docs/ui-audit.md). For every role and viewport it opens every route
 * in that role's menu (from GET /me) plus any extra routes, clicks through every tab of each page, and
 * reports:
 *
 *   - literal NaN, undefined, null or Infinity in the visible text of the page,
 *   - empty cards (a card with a title but no body: not even an empty-state line),
 *   - horizontal page overflow,
 *   - uncaught page errors.
 *
 * Screens that still show the shell placeholder (their module has not landed) are skipped and listed.
 * Configure with environment variables:
 *
 *   AUDIT_ACCOUNTS  comma-separated roles (admin, manager, employee) or emails; default the three roles
 *   AUDIT_ROUTES    only audit paths starting with one of these, e.g. "/users,/rules"
 *   AUDIT_EXTRA     extra paths to audit for every account, e.g. "/kit,/kit/primitives"
 *   AUDIT_WIDTHS    viewport widths; default "390,1024,1440"
 */
const list = (value, fallback) =>
  (value ?? fallback)
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);

const ACCOUNTS = list(process.env.AUDIT_ACCOUNTS, 'admin,manager,employee');
const ONLY = list(process.env.AUDIT_ROUTES, '');
const EXTRA = list(process.env.AUDIT_EXTRA, '');
const WIDTHS = list(process.env.AUDIT_WIDTHS, '390,1024,1440').map(Number);

const HEIGHT = 900;

/** Words that mean a value failed to render. Word-bounded, case-sensitive, so "nullable" or "Null" text is fine. */
const BAD_WORDS = /\b(NaN|undefined|null|Infinity)\b/;

/** Runs in the page: what is wrong with the current view. */
function scan() {
  const issues = [];
  const main = document.querySelector('#pl-content') ?? document.body;

  // visible text only: innerText skips hidden nodes and form values
  const text = main.innerText ?? '';
  const lines = text.split('\n');

  for (const line of lines) {
    const m = /\b(NaN|undefined|null|Infinity)\b/.exec(line);

    if (m) {
      issues.push({ kind: 'bad-value', detail: `"${m[1]}" in: ${line.trim().slice(0, 120)}` });
    }
  }

  for (const card of main.querySelectorAll('.pl-card')) {
    const head = card.querySelector(':scope > .pl-card-head');
    const title = head?.querySelector('h2')?.innerText?.trim() ?? '(untitled card)';
    const body = [...card.children].filter((c) => c !== head);
    const hasContent = body.some(
      (el) => (el.innerText ?? '').trim() !== '' || el.querySelector('img, svg, canvas, input, select, textarea, table, button'),
    );

    if (!hasContent) {
      issues.push({ kind: 'empty-card', detail: title });
    }
  }

  const overflow = document.documentElement.scrollWidth - window.innerWidth;

  if (overflow > 0) {
    // name the widest offenders to make the fix easy
    const wide = [...main.querySelectorAll('*')]
      .filter((el) => el.getBoundingClientRect().right > window.innerWidth + 1)
      .filter((el) => !el.closest('.pl-tbl-wrap, .pl-tabs'))
      .slice(0, 3)
      .map((el) => `${el.tagName.toLowerCase()}${el.className && typeof el.className === 'string' ? `.${el.className.trim().split(/\s+/).join('.')}` : ''}`);

    issues.push({ kind: 'overflow', detail: `${overflow}px wider than the viewport${wide.length ? `: ${wide.join(', ')}` : ''}` });
  }

  return issues;
}

/** Waits until the screen has finished loading: network quiet and no loading state left. */
async function settle(page) {
  await page.waitForLoadState('networkidle');
  await expect(page.locator('#pl-content .pl-loading')).toHaveCount(0, { timeout: 10_000 });
}

/**
 * Opens a route inside the running app (history.pushState + popstate, which vue-router follows), so a
 * route change costs only that screen's requests and the audit stays under the per-user rate limit of
 * the security baseline. The first route loads the page.
 */
async function open(page, path) {
  if (!page.url().startsWith('http')) {
    await page.goto(path);
  } else {
    await page.evaluate((to) => {
      window.history.pushState({}, '', to);
      window.dispatchEvent(new PopStateEvent('popstate'));
    }, path);
  }

  await expect(page.locator('#pl-content')).toBeVisible();
  await page.waitForURL((url) => `${url.pathname}${url.search}` === path);
  await settle(page);
}

/** Menu paths of the signed-in user, in menu order. */
function menuPaths(me) {
  return (me.menu ?? []).flatMap((section) => section.items.map((item) => item.path));
}

function wanted(path) {
  return ONLY.length === 0 || ONLY.some((prefix) => path === prefix || path.startsWith(`${prefix}/`) || path.startsWith(`${prefix}?`));
}

for (const account of ACCOUNTS) {
  // one account's widths run one after another: in parallel they would share its rate limit
  test.describe(`${DEMO[account]?.role ?? emailOf(account)}`, () => {
    test.describe.configure({ mode: 'default' });

    for (const width of WIDTHS) {
      test(`UI audit · ${DEMO[account]?.role ?? emailOf(account)} · ${width}px`, async ({ page }) => {
        test.setTimeout(240_000);

        const pageErrors = [];

        page.on('pageerror', (e) => pageErrors.push(e.message));

        await page.setViewportSize({ width, height: HEIGHT });

        const me = await signInViaApi(page, account);
        const paths = [...new Set([...menuPaths(me), ...EXTRA])].filter(wanted);
        const issues = [];
        const skipped = [];
        const audited = [];

        for (const path of paths) {
          pageErrors.length = 0;

          await open(page, path);

          if (await page.locator('[data-screen-placeholder]').count()) {
            skipped.push(path);
            continue;
          }

          audited.push(path);

          const record = (where, found) => found.forEach((f) => issues.push({ where, ...f }));

          record(path, await page.evaluate(scan));

          const tabs = page.locator('#pl-content [role="tablist"] [role="tab"]');
          const tabCount = await tabs.count();

          for (let i = 0; i < tabCount; i += 1) {
            const tab = tabs.nth(i);
            const name = (await tab.innerText()).trim();

            if (!(await tab.isVisible())) {
              await tab.scrollIntoViewIfNeeded();
            }

            await tab.click();
            await settle(page);
            record(`${path} › tab "${name}"`, await page.evaluate(scan));
          }

          pageErrors.forEach((message) => issues.push({ where: path, kind: 'page-error', detail: message }));
        }

        const report = { account: emailOf(account), width, audited, skipped, issues };

        await test.info().attach('ui-audit.json', { body: JSON.stringify(report, null, 2), contentType: 'application/json' });

        if (skipped.length) {
          test.info().annotations.push({ type: 'skipped placeholders', description: skipped.join(', ') });
        }

        expect(
          issues.map((i) => `${i.kind} at ${i.where}: ${i.detail}`),
          `UI audit issues for ${emailOf(account)} at ${width}px (audited ${audited.length}, placeholders skipped ${skipped.length})`,
        ).toEqual([]);
      });
    }
  });
}

test('the audit scanner catches each kind of problem', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: HEIGHT });
  await page.setContent(`
    <main id="pl-content">
      <p>Utilization NaN%</p>
      <p>Owner: undefined</p>
      <section class="pl-card"><div class="pl-card-head"><h2>Empty card</h2></div></section>
      <section class="pl-card"><div class="pl-card-head"><h2>Card with an empty state</h2></div><div class="pl-empty"><p>Nothing yet.</p></div></section>
      <div style="width: 800px">wide</div>
    </main>`);

  const kinds = (await page.evaluate(scan)).map((i) => `${i.kind}: ${i.detail.split(':')[0]}`);

  expect(kinds).toEqual(['bad-value: "NaN" in', 'bad-value: "undefined" in', 'empty-card: Empty card', expect.stringMatching(/^overflow: \d+px wider than the viewport/)]);
  expect(BAD_WORDS.test('Nullable fields')).toBe(false);
});
