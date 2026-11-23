import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';
import { DEMO, DEMO_PASSWORD, distinctIp, expectNoHorizontalScroll, signIn } from './helpers.js';

/**
 * Task 6.4: the app shell against the real backend (e2e/start-backend.sh: demo seed on, today pinned
 * to 28 Sep 2026). Sign-in as the three roles, menus from GET /me, the no-access card, the 401 return
 * path, the top bar and the shared period (Sprint 42 carries from Effort to Bugs & support).
 */
const menu = (page) => page.getByRole('navigation', { name: 'Main' });

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

test('Admin signs in from the demo card and lands on Action center', async ({ page }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/sign-in$/);
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible();
  await expect(page.getByText('Performance, road maps and live workload')).toBeVisible();

  await page.getByRole('button', { name: /Use Admin demo account/ }).click();
  await expect(page.getByLabel('Work email', { exact: true })).toHaveValue(DEMO.admin.email);
  await page.getByRole('button', { name: 'Sign in' }).click();

  await expect(page).toHaveURL(/\/actions$/);
  await expect(page.getByRole('status').filter({ hasText: 'Signed in as Anita Desai · Admin' })).toBeVisible();
  await expect(menu(page).getByText('Administration', { exact: true })).toBeVisible();
  await expect(menu(page).getByRole('link', { name: 'Roles & permissions' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Breadcrumb' })).toContainText('Anita Desai · Admin');
});

test('Manager menu shows the Manager group and no Admin-only items', async ({ page }) => {
  await signIn(page, DEMO.manager);
  await expect(page).toHaveURL(/\/actions$/);

  for (const item of ['Action center', 'Team utilization', 'Appraisal & increments']) {
    await expect(menu(page).getByRole('link', { name: item })).toBeVisible();
  }

  await expect(menu(page).getByRole('link', { name: 'Roles & permissions' })).toHaveCount(0);
  await expect(menu(page).getByRole('link', { name: 'Rules & thresholds' })).toHaveCount(0);
});

test('Employee lands on Overview, has no Manager group, and gets the no-access card on Appraisal', async ({ page }) => {
  await signIn(page, DEMO.employee);
  await expect(page).toHaveURL(/\/overview$/);
  await expect(menu(page).getByText('Manager', { exact: true })).toHaveCount(0);
  await expect(menu(page).getByText('Administration', { exact: true })).toHaveCount(0);

  const requests = [];

  page.on('request', (req) => req.url().includes('/api/v1/appraisal') && requests.push(req.url()));

  await page.goto('/appraisal');
  await expect(page.getByRole('heading', { name: "You don't have access" })).toBeVisible();
  expect(requests, 'no appraisal request is made').toEqual([]);

  // an unknown address looks the same as a forbidden one
  await page.goto('/no-such-screen');
  await expect(page.getByRole('heading', { name: "You don't have access" })).toBeVisible();
});

test('wrong password shows the auth line and Enter submits', async ({ page }) => {
  await page.goto('/sign-in');
  await page.getByLabel('Work email', { exact: true }).fill(DEMO.employee.email);
  await page.getByLabel('Password', { exact: true }).fill('not-the-password');
  await page.getByLabel('Password', { exact: true }).press('Enter');
  await expect(page.getByRole('alert')).toHaveText('Email or password is incorrect.');
  await expect(page).toHaveURL(/\/sign-in$/);
});

test('Sprint 42 chosen on Effort carries to Bugs & support; Attendance keeps its own choice', async ({ page }) => {
  await signIn(page, DEMO.employee);
  await menu(page).getByRole('link', { name: 'Effort' }).click();
  // Effort is a real screen now (task 11.3): its H1 is the person, its eyebrow names the screen
  await expect(page.locator('.pl-hdr .pl-eyebrow')).toHaveText('Effort');

  const period = page.getByRole('combobox', { name: 'Period', exact: true });

  await expect(period).toHaveValue('s:S43');
  await period.selectOption('s:S42');
  await expect(period.locator('option:checked')).toHaveText(/^Sprint 42/);

  await menu(page).getByRole('link', { name: 'Bugs & support' }).click();
  // Bugs & support is a real screen now (task 12.2): its H1 is the person, its eyebrow names the screen
  await expect(page.locator('.pl-hdr .pl-eyebrow')).toHaveText('Bugs & support');
  await expect(period).toHaveValue('s:S42');

  await menu(page).getByRole('link', { name: 'Attendance' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Attendance' })).toBeVisible();
  await expect(period).not.toHaveValue('s:S42');
  // Attendance's own dropdown (months, sprints, quarters; task 17.4) opens on the last uploaded month.
  await expect(period).toHaveValue(/^m:/);

  // the choice survives a reload within the session
  await menu(page).getByRole('link', { name: 'Effort' }).click();
  await page.reload();
  await expect(period).toHaveValue('s:S42');
});

test('signing in again resets the period choice', async ({ page }) => {
  await signIn(page, DEMO.employee);
  await page.goto('/effort');
  await page.getByRole('combobox', { name: 'Period', exact: true }).selectOption('s:S42');

  await page.getByRole('banner').getByRole('button', { name: 'Sign out' }).click();
  await expect(page).toHaveURL(/\/sign-in$/);

  await signIn(page, DEMO.employee);
  await page.goto('/effort');
  await expect(page.getByRole('combobox', { name: 'Period', exact: true })).toHaveValue('s:S43');
});

test('an expired session sends the user to sign in and back to the page', async ({ page, context }) => {
  await signIn(page, DEMO.employee);
  await page.goto('/bugs-support');
  await context.clearCookies();

  await page.reload();
  await expect(page).toHaveURL(/\/sign-in\?next=(%2F|\/)bugs-support$/);

  await page.getByLabel('Work email', { exact: true }).fill(DEMO.employee.email);
  await page.getByLabel('Password', { exact: true }).fill(DEMO_PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/bugs-support$/);
});

test('a 401 from an API call returns to sign-in with a notice, then to the page', async ({ page, context }) => {
  // Action center has no period control, so GET /periods first runs on Effort, after the cookie is gone
  await signIn(page, DEMO.manager);
  await expect(page).toHaveURL(/\/actions$/);
  await context.clearCookies();

  await menu(page).getByRole('link', { name: 'Effort' }).click();
  await expect(page).toHaveURL(/\/sign-in\?next=(%2F|\/)effort$/);
  await expect(page.getByText('Your session has ended. Sign in again.')).toBeVisible();

  await page.getByLabel('Work email', { exact: true }).fill(DEMO.manager.email);
  await page.getByLabel('Password', { exact: true }).fill(DEMO_PASSWORD);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page).toHaveURL(/\/effort$/);
});

test('top bar shows sync status; person selector only for managers on person pages', async ({ page }) => {
  await signIn(page, DEMO.manager);
  await expect(page.getByRole('banner').getByRole('status')).toContainText(/Jira/);
  await expect(page.getByRole('combobox', { name: 'Person' })).toHaveCount(0);

  await menu(page).getByRole('link', { name: 'Overview' }).click();

  const person = page.getByRole('combobox', { name: 'Person' });

  await expect(person).toBeVisible();
  await expect(person.locator('option', { hasText: 'Priya Shah' })).toHaveCount(1);
  await person.selectOption({ label: 'Priya Shah' });
  await menu(page).getByRole('link', { name: 'Effort' }).click();
  await expect(person.locator('option:checked')).toHaveText('Priya Shah');
});

test('Employee sees no person selector', async ({ page }) => {
  await signIn(page, DEMO.employee);
  await expect(page.getByRole('combobox', { name: 'Person' })).toHaveCount(0);
});

test('phone width: menu behind a button, Esc closes it, no horizontal scroll', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await signIn(page, DEMO.manager);
  await expectNoHorizontalScroll(page);

  const button = page.getByRole('button', { name: 'Menu' });

  await expect(menu(page).getByRole('link', { name: 'Overview' })).toBeHidden();
  await button.click();
  await expect(menu(page).getByRole('link', { name: 'Overview' })).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(menu(page).getByRole('link', { name: 'Overview' })).toBeHidden();
  await expect(button).toBeFocused();

  await button.click();
  await menu(page).getByRole('link', { name: 'Effort' }).click();
  await expect(page).toHaveURL(/\/effort$/);
  await expect(menu(page).getByRole('link', { name: 'Overview' })).toBeHidden();
});

test('shell has no axe violations and fits at 1024 and 1440 px', async ({ page }) => {
  await signIn(page, DEMO.admin);
  await page.goto('/effort');

  for (const width of [1024, 1440]) {
    await page.setViewportSize({ width, height: 900 });
    await expectNoHorizontalScroll(page);
  }

  const { violations } = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag22aa']).analyze();

  expect(violations.map((v) => v.id)).toEqual([]);
});

test('with no active sprint (today after the last sprint) the period control still works across screens', async ({ page }) => {
  // the server's shape when today is past the last seeded sprint (an unpinned dev backend)
  await page.route('**/api/v1/periods', (route) =>
    route.fulfill({
      json: {
        today: '2026-11-22',
        activeSprint: { notApplicable: true },
        sprints: [
          { kind: 'sprint', key: 's:S43', label: 'Sprint 43 · 21 Sep – 9 Oct', live: false },
          { kind: 'sprint', key: 's:S42', label: 'Sprint 42 · 7 Sep – 18 Sep', live: false },
        ],
        months: [{ kind: 'month', key: 'm:2026-11', label: 'Nov 2026 (to date)', live: true }],
        releases: [{ kind: 'release', key: 'r:8.3', label: 'Release 8.3 (in progress)', live: false }],
      },
    }),
  );

  const errors = [];

  page.on('pageerror', (e) => errors.push(e.message));

  await signIn(page, DEMO.employee);

  const period = page.getByRole('combobox', { name: 'Period', exact: true });

  await expect(period).toHaveValue('s:S43');

  await menu(page).getByRole('link', { name: 'Progress' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Progress' })).toBeVisible();

  await menu(page).getByRole('link', { name: 'Overview' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Overview' })).toBeVisible();
  await expect(period).toHaveValue('s:S43');

  await page.getByRole('button', { name: 'Month' }).click();
  await expect(period).toHaveValue('m:2026-11');
  await page.getByRole('button', { name: 'Sprint' }).click();
  await expect(period).toHaveValue('s:S43');

  expect(errors).toEqual([]);
});
