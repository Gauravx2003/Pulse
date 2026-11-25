import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { DEMO, DEMO_ACCOUNTS, DEMO_PASSWORD, distinctIp, emailOf, signInViaApi } from './helpers.js';

/**
 * The shared UI audit (tasks 6.6 and 23.4, docs/ui-audit.md). For every account and viewport it opens
 * every route in that account's menu (from GET /me) plus any extra routes, moves through every tab of
 * each page with the keyboard, and reports:
 *
 *   - literal NaN, undefined, null or Infinity in the visible text of the page,
 *   - empty cards (a card with a title but no body: not even an empty-state line),
 *   - horizontal page overflow,
 *   - uncaught page errors,
 *   - axe-core violations (WCAG 2.1 A and AA) on the screen and on every dialog it opens,
 *   - keyboard problems: a control Tab never reaches, a clickable element that is not focusable, a
 *     focused control with no visible focus ring, and a dialog that does not take focus, lets Tab leave,
 *     leaves a control unreachable, does not close on Esc or does not hand focus back to its opener.
 *
 * The keyboard pass opens every distinct button of a screen with Enter. While it runs, every request
 * that is not a GET is aborted, so no button can change data. A separate test per account signs in
 * through the sign-in screen with the keyboard only and fails when the first screen takes 2 s or more.
 *
 * Configure with environment variables:
 *
 *   AUDIT_ACCOUNTS  comma-separated roles (admin, manager, employee), emails, or "all" (every demo
 *                   account); default the three roles
 *   AUDIT_ROUTES    only audit paths starting with one of these, e.g. "/users,/rules"
 *   AUDIT_EXTRA     extra paths to audit for every account, e.g. "/kit,/kit/primitives"
 *   AUDIT_WIDTHS    viewport widths; default "390,1024,1440"
 *   AUDIT_AXE       "0" skips the axe scan
 *   AUDIT_KEYBOARD  "0" skips the keyboard pass
 */
const list = (value, fallback) =>
  (value ?? fallback)
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);

const ACCOUNTS = (() => {
  const raw = list(process.env.AUDIT_ACCOUNTS, 'admin,manager,employee');

  return raw.includes('all') ? DEMO_ACCOUNTS : raw;
})();
const ONLY = list(process.env.AUDIT_ROUTES, '');
const EXTRA = list(process.env.AUDIT_EXTRA, '');
const WIDTHS = list(process.env.AUDIT_WIDTHS, '390,1024,1440').map(Number);
const AXE = process.env.AUDIT_AXE !== '0';
const KEYBOARD = process.env.AUDIT_KEYBOARD !== '0';

const HEIGHT = 900;
const FIRST_SCREEN_BUDGET_MS = 2_000;

/** Words that mean a value failed to render. Word-bounded, case-sensitive, so "nullable" or "Null" text is fine. */
const BAD_WORDS = /\b(NaN|undefined|null|Infinity)\b/;

/** Buttons the keyboard pass never presses: they leave the screen or the session. */
const NEVER_PRESS = /^(sign out|log out)$/i;

const label = (account) => DEMO[account]?.role ?? emailOf(account);

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

/**
 * Runs in the page: tags every visible control under `rootSelector` that Tab should reach with a
 * `data-kb` id and returns them, plus the clickable elements no keyboard can reach. Roving-tabindex
 * members (tabs) and unchecked radios of a group with a checked one are reached by arrow keys, not
 * Tab, so they are left out.
 */
function tagControls(rootSelector) {
  const root = [...document.querySelectorAll(rootSelector)].pop();

  if (!root) {
    return { controls: [], unfocusable: [] };
  }

  const SEL = 'a[href], button, input:not([type="hidden"]), select, textarea, [tabindex], [contenteditable="true"]';
  const visible = (el) => {
    if (el.closest('[inert], [aria-hidden="true"]')) return false;
    const r = el.getBoundingClientRect();
    const st = getComputedStyle(el);
    return r.width > 0 && r.height > 0 && st.visibility !== 'hidden' && st.display !== 'none';
  };
  const describe = (el) => {
    const name = (el.getAttribute('aria-label') || el.innerText || el.getAttribute('placeholder') || el.getAttribute('name') || el.id || '')
      .trim()
      .replace(/\s+/g, ' ')
      .slice(0, 60);
    return `${el.tagName.toLowerCase()}${el.getAttribute('role') ? `[role=${el.getAttribute('role')}]` : ''} "${name}"`;
  };

  window.__kbSeq = window.__kbSeq ?? 0;

  const controls = [];

  for (const el of root.querySelectorAll(SEL)) {
    if (!visible(el) || el.disabled || el.getAttribute('tabindex') === '-1') continue;
    if (el.type === 'radio' && !el.checked && root.querySelector(`input[type="radio"][name="${CSS.escape(el.name)}"]:checked`)) continue;
    // a file input is usually visually replaced by its label; the label is the target then
    if (!el.dataset.kb) {
      window.__kbSeq += 1;
      el.dataset.kb = String(window.__kbSeq);
    }
    controls.push({
      id: el.dataset.kb,
      what: describe(el),
      button: el.tagName === 'BUTTON' && el.getAttribute('role') !== 'tab' && !el.closest('[role="tablist"]'),
      name: describe(el),
      // the buttons of a table row repeat on every row ("Edit Anil", "Edit Priya"): one per verb is enough
      key: el.closest('tr, [role="row"], li') ? `row ${describe(el).split(' ').slice(0, 2).join(' ')}` : describe(el),
    });
  }

  // clickable but not focusable: a pointer cursor set on an element that is not a control and does
  // not sit inside one (only the outermost such element is reported; cursor is inherited)
  const isControl = (el) => el.matches(SEL) || el.matches('label, summary') || /^(button|link|tab|checkbox|radio|switch|option|menuitem)$/.test(el.getAttribute('role') ?? '');
  const unfocusable = [];

  for (const el of root.querySelectorAll('*')) {
    if (!visible(el) || isControl(el) || el.closest(SEL) || el.closest('label, summary')) continue;
    if (getComputedStyle(el).cursor !== 'pointer') continue;
    if (el.parentElement && getComputedStyle(el.parentElement).cursor === 'pointer' && !isControl(el.parentElement)) continue;
    if (el.querySelector(SEL)) continue; // a wrapper whose control child is the real target
    unfocusable.push(describe(el));
  }

  return { controls, unfocusable };
}

/** Runs in the page: the `data-kb` id of the focused element, whether it shows a focus ring, and whether it sits in `within`. */
function focusState(within) {
  const el = document.activeElement;

  if (!el || el === document.body) {
    return { id: null, ring: true, inside: false };
  }

  const st = getComputedStyle(el);
  // native date and time inputs move focus to their own picker button last, which Chrome rings itself
  const nativePicker = el.tagName === 'INPUT' && /^(date|time|datetime-local|month|week)$/.test(el.type);
  const ring =
    nativePicker ||
    (st.outlineStyle !== 'none' && parseFloat(st.outlineWidth) > 0) ||
    (st.boxShadow && st.boxShadow !== 'none') ||
    el.matches('[role="dialog"]');

  return { id: el.dataset.kb ?? null, ring, inside: within ? !!el.closest(within) : true, tag: el.tagName.toLowerCase() };
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

/** axe-core WCAG 2.1 A/AA violations in `include` (the screen content or an open dialog). */
async function axe(page, include) {
  const result = await new AxeBuilder({ page }).include(include).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();

  return result.violations.map((v) => ({
    kind: 'axe',
    detail: `${v.id} (${v.impact}): ${v.help} — ${v.nodes
      .slice(0, 3)
      .map((n) => n.target.join(' '))
      .join(' | ')}${v.nodes.length > 3 ? ` (+${v.nodes.length - 3} more)` : ''}`,
  }));
}

/**
 * Presses Tab and returns the `data-kb` ids reached, with focus-ring misses. Stops once every id of
 * `targets` is reached, or after `presses` (native date and time inputs take several presses each).
 */
async function tabWalk(page, presses, targets, within = null) {
  const reached = new Set();
  const noRing = new Set();
  const escaped = [];

  for (let i = 0; i < presses; i += 1) {
    await page.keyboard.press('Tab');

    const f = await page.evaluate(focusState, within);

    if (within && !f.inside) {
      escaped.push(f.tag ?? 'body');
    }

    if (f.id) {
      reached.add(f.id);

      if (!f.ring) {
        noRing.add(f.id);
      }
    }

    if (targets.every((id) => reached.has(id)) && (!within || i >= targets.length)) {
      break;
    }
  }

  return { reached, noRing, escaped };
}

/**
 * The keyboard pass of one screen state: Tab reaches every control of the content, every control shows
 * a focus ring, and each distinct button that opens a dialog does so with Enter, puts focus inside,
 * keeps Tab inside, lets Tab reach every control of the dialog, closes on Esc and returns focus. Each
 * dialog also gets an axe scan. `seen` dedupes buttons by name across the tabs of one route.
 */
async function keyboardPass(page, seen) {
  const issues = [];
  const { controls, unfocusable } = await page.evaluate(tagControls, '#pl-content');

  unfocusable.forEach((what) => issues.push({ kind: 'keyboard', detail: `clickable but not focusable: ${what}` }));

  // Tab from the top of the document through the shell into the content
  await page.evaluate(() => {
    document.activeElement?.blur?.();
    window.getSelection()?.removeAllRanges();
  });

  // enough presses to pass every focusable element of the document (shell and content) once
  const all = await page.evaluate(() => document.querySelectorAll('a[href], button, input, select, textarea, [tabindex]').length);
  const walk = await tabWalk(page, all * 4 + 5, controls.map((c) => c.id));

  for (const c of controls) {
    if (!walk.reached.has(c.id)) {
      issues.push({ kind: 'keyboard', detail: `Tab never reaches ${c.what}` });
    } else if (walk.noRing.has(c.id)) {
      issues.push({ kind: 'keyboard', detail: `no visible focus ring on ${c.what}` });
    }
  }

  // dialogs: press each distinct reachable button with Enter
  const buttons = controls.filter((c) => c.button && walk.reached.has(c.id) && !NEVER_PRESS.test(c.name.replace(/^button "|"$/g, '')));

  for (const b of buttons) {
    if (seen.has(b.key)) continue;
    seen.add(b.key);

    const handle = page.locator(`[data-kb="${b.id}"]`);

    if (!(await handle.count()) || !(await handle.isVisible()) || !(await handle.isEnabled())) continue;

    const url = page.url();

    await handle.focus();
    await page.keyboard.press('Enter');
    await page.waitForTimeout(150);

    const dialog = page.locator('[role="dialog"]').last();

    if (await dialog.count()) {
      const titled = (await dialog.getAttribute('aria-labelledby')) ? await page.locator(`#${await dialog.getAttribute('aria-labelledby')}`).innerText() : '(dialog)';
      const where = `dialog "${titled.trim()}" from ${b.what}`;
      const add = (detail) => issues.push({ kind: 'keyboard', detail: `${where}: ${detail}` });

      await page.waitForLoadState('networkidle');

      const start = await page.evaluate(focusState, '[role="dialog"]');

      if (!start.inside) add('focus does not move into the dialog');

      const inner = await page.evaluate(tagControls, '[role="dialog"]');
      const innerWalk = await tabWalk(page, inner.controls.length * 4 + 4, inner.controls.map((c) => c.id), '[role="dialog"]');

      if (innerWalk.escaped.length) add(`Tab leaves the dialog (to ${innerWalk.escaped[0]})`);

      for (const c of inner.controls) {
        if (!innerWalk.reached.has(c.id) && start.id !== c.id) add(`Tab never reaches ${c.what}`);
        else if (innerWalk.noRing.has(c.id)) add(`no visible focus ring on ${c.what}`);
      }

      await page.keyboard.press('Shift+Tab');

      if (!(await page.evaluate(focusState, '[role="dialog"]')).inside) add('Shift+Tab leaves the dialog');

      // a text field takes typing from the keyboard
      const field = dialog.locator('input[type="text"]:visible, input:not([type]):visible, textarea:visible').first();

      if (await field.count()) {
        await field.focus();
        const before = await field.inputValue();
        await page.keyboard.type('a');
        if ((await field.inputValue()) === before && !(await field.getAttribute('readonly'))) add('a text field does not take typing');
      }

      if (AXE) {
        (await axe(page, '[role="dialog"]')).forEach((v) => issues.push({ ...v, detail: `${where}: ${v.detail}` }));
      }

      await page.keyboard.press('Escape');
      await page.waitForTimeout(100);

      if (await page.locator('[role="dialog"]').count()) {
        add('Esc does not close the dialog');
        // close whatever is left so the pass can go on
        for (let i = 0; i < 3 && (await page.locator('[role="dialog"]').count()); i += 1) {
          await page.locator('[role="dialog"] button[aria-label="Close"]').last().click({ force: true }).catch(() => {});
          await page.waitForTimeout(100);
        }
      } else if ((await page.evaluate(() => document.activeElement?.dataset?.kb ?? null)) !== b.id && (await handle.count())) {
        add('focus does not return to the button that opened it');
      }
    } else {
      // not a dialog: close any popover it opened and come back if it navigated
      await page.keyboard.press('Escape');
    }

    if (page.url() !== url) {
      await open(page, new URL(url).pathname + new URL(url).search);
      return { issues, navigatedAway: true };
    }
  }

  return { issues, navigatedAway: false };
}

for (const account of ACCOUNTS) {
  // one account's tests run one after another: in parallel they would share its rate limit
  test.describe(`${label(account)}`, () => {
    test.describe.configure({ mode: 'default' });

    test(`first screen after sign-in < 2 s, keyboard only · ${label(account)}`, async ({ browser, page }) => {
      // warm the web server once in a throwaway context (a dev server compiles each module on its
      // first request); the timed run below starts with an empty browser cache
      const warm = await (await browser.newContext({ baseURL: test.info().project.use.baseURL })).newPage();

      await signInViaApi(warm, account);
      await warm.goto('/');
      await expect(warm.locator('#pl-content .pl-loading')).toHaveCount(0, { timeout: 15_000 });
      await warm.context().close();

      await distinctIp(page.context());
      await page.setViewportSize({ width: 1440, height: HEIGHT });
      await page.goto('/sign-in');
      await settle(page);

      // keyboard only: Tab into each field, type, Enter submits
      await page.getByLabel('Work email', { exact: true }).focus();
      await page.keyboard.type(emailOf(account));
      await page.keyboard.press('Tab');
      await expect(page.getByLabel('Password', { exact: true })).toBeFocused();
      await page.keyboard.type(DEMO_PASSWORD);

      const started = Date.now();

      await page.keyboard.press('Enter');
      await expect(page.locator('.pl-shell')).toBeVisible();
      await expect(page.locator('#pl-content')).toBeVisible();
      await expect(page.locator('#pl-content .pl-loading')).toHaveCount(0, { timeout: 10_000 });
      await expect(page.locator('#pl-content h1').first()).toBeVisible();

      const elapsed = Date.now() - started;

      test.info().annotations.push({ type: 'first screen', description: `${emailOf(account)} ${page.url()} in ${elapsed} ms` });
      expect(elapsed, `first screen for ${emailOf(account)} (${page.url()}) in ms`).toBeLessThan(FIRST_SCREEN_BUDGET_MS);
    });

    for (const width of WIDTHS) {
      test(`UI audit · ${label(account)} · ${width}px`, async ({ page }) => {
        test.setTimeout(900_000);

        const pageErrors = [];

        page.on('pageerror', (e) => pageErrors.push(e.message));
        // a failed or throttled request leaves a screen half-drawn: report it rather than its symptoms
        page.on('response', (r) => {
          if (r.url().includes('/api/') && (r.status() === 429 || r.status() >= 500)) {
            pageErrors.push(`${r.request().method()} ${new URL(r.url()).pathname} answered ${r.status()}`);
          }
        });

        await page.setViewportSize({ width, height: HEIGHT });

        const me = await signInViaApi(page, account);
        const paths = [...new Set([...menuPaths(me), ...EXTRA])].filter(wanted);
        const issues = [];
        const skipped = [];
        const audited = [];
        let blockWrites = false;

        // the keyboard pass presses buttons: no request that could change data reaches the server then
        await page.route('**/api/**', (route) =>
          blockWrites && route.request().method() !== 'GET' ? route.abort('blockedbyclient') : route.continue(),
        );

        for (const path of paths) {
          pageErrors.length = 0;

          await open(page, path);

          if (await page.locator('[data-screen-placeholder]').count()) {
            skipped.push(path);
            continue;
          }

          audited.push(path);

          const record = (where, found) => found.forEach((f) => issues.push({ where, ...f }));
          const seen = new Set();
          const check = async (where) => {
            record(where, await page.evaluate(scan));

            if (AXE) record(where, await axe(page, '#pl-content'));

            if (KEYBOARD) {
              blockWrites = true;
              const kb = await keyboardPass(page, seen);
              blockWrites = false;
              record(where, kb.issues);
              return kb.navigatedAway;
            }

            return false;
          };

          await check(path);

          const tabs = page.locator('#pl-content [role="tablist"]').first().locator('[role="tab"]');
          const tabCount = await tabs.count();

          for (let i = 1; i < tabCount; i += 1) {
            // keyboard: focus the previous tab and move right with the arrow key (tabs activate on focus)
            const prev = tabs.nth(i - 1);

            if ((await prev.getAttribute('aria-selected')) !== 'true') {
              await prev.click();
              await settle(page);
            }

            await prev.focus();
            await page.keyboard.press('ArrowRight');
            await settle(page);

            const tab = tabs.nth(i);
            const name = (await tab.innerText()).trim();

            if ((await tab.getAttribute('aria-selected')) !== 'true') {
              issues.push({ where: path, kind: 'keyboard', detail: `ArrowRight does not select tab "${name}"` });
              await tab.click();
              await settle(page);
            }

            await check(`${path} › tab "${name}"`);
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

test('the keyboard scanner finds clickable elements no keyboard can reach', async ({ page }) => {
  await page.setContent(`
    <main id="pl-content">
      <button>Real button</button>
      <div style="cursor: pointer" onclick="void 0"><span>Row you can only click</span></div>
      <label style="cursor: pointer"><input type="checkbox"> A label</label>
      <button tabindex="-1">Roving</button>
    </main>`);

  const { controls, unfocusable } = await page.evaluate(tagControls, '#pl-content');

  expect(controls.map((c) => c.what)).toEqual(['button "Real button"', 'input ""']);
  expect(unfocusable).toEqual(['div "Row you can only click"']);
});
