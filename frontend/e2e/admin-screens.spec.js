import { expect, test } from '@playwright/test';
import { resetNotifications } from './db.js';
import { DEMO, distinctIp, expectNoHorizontalScroll, signIn } from './helpers.js';

/**
 * Task 6.5: Users, Roles & permissions, Rules & thresholds, Integrations and the notification panel
 * with menu badges, per role, against the real backend (demo seed, today pinned to 28 Sep 2026) and
 * the "Screen detail" blocks of reference sections 11.1 to 11.4 and 11.6. Every write is undone or
 * uses a unique name, so repeated runs on the same e2e database pass.
 */
const menu = (page) => page.getByRole('navigation', { name: 'Main' });
const noAccess = (page) => page.getByRole('heading', { name: "You don't have access" });
const h1 = (page, name) => page.getByRole('heading', { level: 1, name });
const card = (page, name) => page.locator('section.pl-card').filter({ has: page.getByRole('heading', { level: 2, name, exact: true }) });
const toast = (page, text) => page.getByRole('status').filter({ hasText: text });
const unique = () => `${Date.now().toString(36)}${Math.floor(Math.random() * 1e4)}`;

test.beforeEach(async ({ context }) => {
  await distinctIp(context);
});

// ------------------------------------------------------------------ 11.1 Users

test.describe('Users', () => {
  test('Admin sees the whole directory with KPIs, columns and row actions', async ({ page }) => {
    await signIn(page, DEMO.admin);
    await menu(page).getByRole('link', { name: 'Users' }).click();

    await expect(h1(page, 'Users')).toBeVisible();
    await expect(page.locator('.pl-hdr .pl-eyebrow')).toHaveText('Administration');
    await expect(page.locator('.pl-hdr .pl-caption')).toHaveText(/^All users in the organisation · \d+ shown$/);

    for (const name of ['Import CSV', 'Export CSV', 'Add user']) {
      await expect(page.locator('.pl-hdr').getByRole(name === 'Import CSV' ? 'link' : 'button', { name })).toBeVisible();
    }

    const kpis = page.getByRole('group', { name: 'Users by role' });

    await expect(kpis.locator('.pl-stat')).toHaveCount(4);
    await expect(kpis).toContainText('Admins');
    await expect(kpis).toContainText('HR / system admin — everything');
    await expect(kpis).toContainText('Managers');
    await expect(kpis).toContainText('Employees');
    await expect(kpis).toContainText('Waiting for first login');

    const directory = card(page, 'Directory');

    await expect(directory).toContainText('Managers can add employees to their own team · only Admin can assign Manager or Admin roles');
    await expect(directory.locator('thead th')).toHaveText(['Name', 'Email', 'Role', 'Manager', 'Status', 'Last login', 'Actions']);

    const anita = directory.locator('tbody tr').filter({ hasText: 'hr@pulse.demo' });

    await expect(anita).toContainText('(you)');
    await expect(anita).toContainText('HR Business Partner · People & HR');
    await expect(anita.locator('.pl-badge.is-danger')).toHaveText('Admin');
    await expect(anita.getByRole('button')).toHaveCount(0);

    const rahul = directory.locator('tbody tr').filter({ hasText: 'rahul@pulse.demo' });

    await expect(rahul.locator('.pl-badge.is-brand')).toHaveText('Manager');
    await expect(rahul.locator('.pl-badge.is-ok')).toHaveText('Active');
    await expect(rahul.getByRole('button', { name: 'Edit Rahul Nair' })).toBeVisible();
    await expect(rahul.getByRole('button', { name: 'Delete Rahul Nair' })).toBeVisible();

    await page.getByRole('searchbox', { name: 'Search name, email, role' }).fill('manager');
    await expect(directory.locator('tbody tr')).toHaveCount(1);
    await expect(page.locator('.pl-hdr .pl-caption')).toHaveText('All users in the organisation · 1 shown');

    await expectNoHorizontalScroll(page);
  });

  test('Admin adds a user by invite (no password field), sees the errors, then deletes them', async ({ page }) => {
    await signIn(page, DEMO.admin);
    await page.goto('/users');
    await page.locator('.pl-hdr').getByRole('button', { name: 'Add user' }).click();

    const modal = page.getByRole('dialog', { name: 'Add user' });

    await expect(modal).toBeVisible();

    for (const label of ['Full name', 'Work email', 'Role', 'Reports to', 'Job title', 'Department', 'Level']) {
      await expect(modal.getByLabel(label)).toBeVisible();
    }

    await expect(modal.getByLabel(/password/i)).toHaveCount(0);
    await expect(modal).toContainText('invite email');

    await modal.getByRole('button', { name: 'Add user' }).click();
    await expect(modal).toContainText('Enter a name.');
    await expect(modal).toContainText('Enter a valid work email.');

    await modal.getByLabel('Work email').fill('rahul@pulse.demo');
    await modal.getByLabel('Full name').fill('Duplicate Person');
    await modal.getByRole('button', { name: 'Add user' }).click();
    await expect(modal).toContainText('A user with this email already exists.');

    const email = `e2e-${unique()}@pulse.demo`;

    await modal.getByLabel('Work email').fill(email);
    await modal.getByLabel('Full name').fill('E2E Tester');
    await modal.getByLabel('Job title').fill('QA Engineer');
    await modal.getByLabel('Level').selectOption('L2');
    await modal.getByRole('button', { name: 'Add user' }).click();

    await expect(toast(page, `User added · invite sent to ${email}`)).toBeVisible();
    await expect(modal).toBeHidden();

    const row = card(page, 'Directory').locator('tbody tr').filter({ hasText: email });

    await expect(row.locator('.pl-badge.is-accent')).toHaveText('Invited');

    await row.getByRole('button', { name: 'Delete E2E Tester' }).click();

    const confirm = page.getByRole('dialog', { name: 'Delete E2E Tester?' });

    await expect(confirm).toContainText('Deactivate instead');
    await expect(confirm.getByRole('button', { name: 'Cancel' })).toBeFocused();
    await confirm.getByRole('button', { name: 'Delete user' }).click();
    await expect(toast(page, 'User deleted · E2E Tester')).toBeVisible();
    await expect(row).toHaveCount(0);
  });

  test('Admin edits a user: email locked, status shown instead of password', async ({ page }) => {
    await signIn(page, DEMO.admin);
    await page.goto('/users');
    await page.getByRole('button', { name: 'Edit Kavya Rao' }).click();

    const modal = page.getByRole('dialog', { name: 'Edit Kavya Rao' });

    await expect(modal.getByLabel('Work email')).toBeDisabled();
    await expect(modal.getByLabel('Status')).toHaveValue('Active');
    await expect(modal.getByRole('button', { name: 'Save' })).toBeVisible();
    await modal.getByRole('button', { name: 'Cancel' }).click();
    await expect(modal).toBeHidden();
  });

  test('Manager sees only their Employees and cannot pick another manager', async ({ page }) => {
    await signIn(page, DEMO.manager);
    await menu(page).getByRole('link', { name: 'Users' }).click();

    await expect(page.locator('.pl-hdr .pl-caption')).toHaveText(/^Employees who report to you · \d+ shown$/);

    const rows = card(page, 'Directory').locator('tbody tr');

    await expect(rows.filter({ hasText: 'priya@pulse.demo' })).toHaveCount(1);
    await expect(rows.filter({ hasText: 'hr@pulse.demo' })).toHaveCount(0);
    await expect(rows.filter({ hasText: 'meera@pulse.demo' })).toHaveCount(0);

    await page.locator('.pl-hdr').getByRole('button', { name: 'Add user' }).click();

    const modal = page.getByRole('dialog', { name: 'Add user' });

    await expect(modal.getByLabel('Reports to')).toBeDisabled();
    await expect(modal.getByLabel('Role').locator('option')).not.toContainText(['Admin']);
  });

  test('Employee gets the no-access card on Users', async ({ page }) => {
    await signIn(page, DEMO.employee);
    await expect(menu(page).getByRole('link', { name: 'Users' })).toHaveCount(0);
    await page.goto('/users');
    await expect(noAccess(page)).toBeVisible();
  });
});

// ------------------------------------------------------------------ 11.2 Roles & permissions

test.describe('Roles & permissions', () => {
  test('Admin sees the matrix, locked Admin switches and the menu each role gets', async ({ page }) => {
    await signIn(page, DEMO.admin);
    await menu(page).getByRole('link', { name: 'Roles & permissions' }).click();

    await expect(h1(page, 'Roles & permissions')).toBeVisible();
    await expect(page.locator('.pl-hdr .pl-caption')).toHaveText(
      'The menu and every screen are shown or hidden from these permissions. Built-in roles can be edited; Admin always keeps full access.',
    );
    await expect(page.locator('.pl-hdr').getByRole('button', { name: 'New role' })).toBeVisible();

    const matrix = card(page, 'Permission matrix');

    await expect(matrix).toContainText('Changes apply immediately to everyone with that role');

    for (const area of ['My work', 'People', 'OKRs', 'Feedback', 'Reviews', 'Admin', '360 reviews', 'Appraisal', 'Training', 'Attendance']) {
      await expect(matrix.locator('tr.area-row th').filter({ hasText: new RegExp(`^${area}$`) })).toBeVisible();
    }

    await expect(matrix.locator('thead th').nth(1)).toContainText(/Admin\s*1 user/);
    await expect(matrix.getByRole('switch', { name: 'Admin: users.all' })).toBeDisabled();
    await expect(matrix.getByRole('switch', { name: 'Admin: users.all' })).toBeChecked();
    await expect(matrix.getByRole('switch', { name: 'Employee: self.view' })).toBeChecked();
    await expect(matrix.getByRole('switch', { name: 'Employee: users.all' })).not.toBeChecked();
    await expect(matrix.locator('.pl-mono').filter({ hasText: /^team\.view$/ })).toBeVisible();

    const sees = card(page, 'What each role sees');

    await expect(sees.getByRole('article', { name: 'Employee menu' })).toContainText('Overview');
    await expect(sees.getByRole('article', { name: 'Admin menu' })).toContainText('Rules & thresholds');
  });

  test('Admin creates a custom role, toggles a permission on it, then deletes it', async ({ page }) => {
    await signIn(page, DEMO.admin);
    await page.goto('/roles');

    const name = `QA ${unique()}`;

    await page.locator('.pl-hdr').getByRole('button', { name: 'New role' }).click();

    const modal = page.getByRole('dialog', { name: 'New role' });

    await expect(modal.getByLabel('Role name')).toBeVisible();
    await expect(modal.getByLabel('Start from')).toBeVisible();
    await expect(modal.getByLabel('Description')).toBeVisible();
    await modal.getByLabel('Role name').fill(name);
    await modal.getByLabel('Description').fill('Created by the e2e run');
    await modal.getByRole('button', { name: 'Create role' }).click();
    await expect(toast(page, `Role created · ${name}`)).toBeVisible();

    const matrix = card(page, 'Permission matrix');
    const sw = matrix.getByRole('switch', { name: `${name}: team.view` });

    await expect(sw).not.toBeChecked();
    await sw.click();
    await expect(toast(page, `${name} · team.view granted`)).toBeVisible();
    await expect(sw).toBeChecked();
    await expect(card(page, 'What each role sees').getByRole('article', { name: `${name} menu` })).toContainText('Action center');

    await matrix.getByRole('button', { name: `Delete role ${name}` }).click();
    await page.getByRole('dialog', { name: `Delete role ${name}?` }).getByRole('button', { name: 'Delete role' }).click();
    await expect(toast(page, `Role deleted · ${name}`)).toBeVisible();
    await expect(matrix.getByRole('switch', { name: `${name}: team.view` })).toHaveCount(0);
  });

  test('Manager and Employee get the no-access card on Roles', async ({ page }) => {
    for (const who of [DEMO.manager, DEMO.employee]) {
      await signIn(page, who);
      await page.goto('/roles');
      await expect(noAccess(page)).toBeVisible();
      await page.getByRole('banner').getByRole('button', { name: 'Sign out' }).click();
    }
  });
});

// ------------------------------------------------------------------ 11.4 Rules & thresholds

test.describe('Rules & thresholds', () => {
  test('Admin sees every card of the screen detail with totals and the control pattern', async ({ page }) => {
    await signIn(page, DEMO.admin);
    await menu(page).getByRole('link', { name: 'Rules & thresholds' }).click();

    await expect(h1(page, 'Rules & thresholds')).toBeVisible();
    await expect(page.locator('.pl-hdr .pl-eyebrow')).toHaveText('Settings');
    await expect(page.locator('.pl-hdr .pl-caption')).toHaveText('Changes apply instantly across every screen.');

    for (const title of [
      'Staleness & capacity',
      'Utilization bands',
      'Monthly effort & extra-mile points',
      'Customer visits',
      'Attendance & leave',
      'Pull request standard (TFS & GitHub)',
      'Training rating & sign-off',
      'Culture value weights',
      'Discipline rating bands',
      'Performance index weights',
    ]) {
      await expect(card(page, title)).toBeVisible();
    }

    await expect(card(page, 'Appraisal standards')).toHaveCount(0);

    const stale = card(page, 'Staleness & capacity');

    await expect(stale.getByRole('slider', { name: 'Stale after more than' })).toBeVisible();
    await expect(stale).toContainText('Working days without assignee activity');
    await expect(stale.getByRole('switch', { name: 'Slack nudges' })).toBeVisible();
    await expect(card(page, 'Attendance & leave').getByLabel('Late after')).toHaveValue(/^\d{2}:\d{2}$/);
    await expect(card(page, 'Pull request standard (TFS & GitHub)').getByLabel('Title pattern')).toBeVisible();
    await expect(card(page, 'Culture value weights').locator('.pl-badge').first()).toHaveText('Total 100%');
    await expect(card(page, 'Performance index weights').locator('.pl-badge').first()).toHaveText('Total 100%');

    await expectNoHorizontalScroll(page);
  });

  test('Admin changes a rule, it persists, and changes it back', async ({ page }) => {
    await signIn(page, DEMO.admin);
    await page.goto('/rules');

    const slider = card(page, 'Discipline rating bands').getByRole('slider', { name: 'Score 2 at' });
    const before = Number(await slider.inputValue());
    const output = card(page, 'Discipline rating bands').locator('output').last();

    await slider.focus();
    await slider.press('ArrowRight');
    await expect(toast(page, 'Score 2 at · saved')).toBeVisible();
    await expect(output).toHaveText(`${before + 1}%`);

    await page.reload();
    await expect(slider).toHaveValue(String(before + 1));
    await expect(card(page, 'Discipline rating bands')).toContainText('Last changed by Anita Desai');

    await slider.focus();
    await slider.press('ArrowLeft');
    await expect(toast(page, 'Score 2 at · saved').last()).toBeVisible();
    await page.reload();
    await expect(slider).toHaveValue(String(before));
  });

  test('an unsafe title pattern is refused inline and Reset defaults asks first', async ({ page }) => {
    await signIn(page, DEMO.admin);
    await page.goto('/rules');

    const pr = card(page, 'Pull request standard (TFS & GitHub)');
    const pattern = pr.getByLabel('Title pattern');
    const original = await pattern.inputValue();

    await pattern.fill('(a+)+$');
    await pattern.press('Enter');
    await expect(pr.locator('.pl-field-error')).toBeVisible();

    await page.reload();
    await expect(pattern).toHaveValue(original);

    await pr.getByRole('button', { name: 'Preview standard' }).click();

    const preview = page.getByRole('dialog', { name: 'Pull request standard' });

    await preview.getByLabel('Try a PR title').fill('PLAT-142 feat(api): add the sync status endpoint');
    await expect(preview.getByRole('status')).toHaveText('Matches the title pattern.');
    await preview.getByLabel('Try a PR title').fill('fixed stuff.');
    await expect(preview.getByRole('status')).toHaveText('Does not match the title pattern.');
    await preview.getByRole('button', { name: 'Close' }).last().click();

    await page.locator('.pl-hdr').getByRole('button', { name: 'Reset defaults' }).click();

    const confirm = page.getByRole('dialog', { name: 'Reset to defaults?' });

    await expect(confirm).toContainText(/default/);
    await confirm.getByRole('button', { name: 'Cancel' }).click();
    await expect(confirm).toBeHidden();
  });

  test('Manager and Employee get the no-access card on Rules', async ({ page }) => {
    for (const who of [DEMO.manager, DEMO.employee]) {
      await signIn(page, who);
      await page.goto('/rules');
      await expect(noAccess(page)).toBeVisible();
      await page.getByRole('banner').getByRole('button', { name: 'Sign out' }).click();
    }
  });
});

// ------------------------------------------------------------------ 11.3 Integrations

test.describe('Integrations', () => {
  test('Admin sees tiles, Jira configuration, sync log, credentials, available tools and activity', async ({ page }) => {
    await signIn(page, DEMO.admin);
    await menu(page).getByRole('link', { name: 'Integrations' }).click();

    await expect(h1(page, 'Integrations')).toBeVisible();
    await expect(page.locator('.pl-hdr .pl-eyebrow')).toHaveText('Settings');
    await expect(page.locator('.pl-hdr .pl-caption')).toHaveText(
      'Pulse reads from these tools. It only writes when you nudge, give kudos or reassign.',
    );

    const connected = card(page, 'Connected (7)');

    await expect(connected.locator('.pl-int')).toHaveCount(7);

    for (const mark of ['JI', 'GH', 'TF', 'CF', 'SL', 'GC', 'HR']) {
      await expect(connected.locator('.pl-int-mark').filter({ hasText: new RegExp(`^${mark}$`) })).toBeVisible();
    }

    await expect(connected).toContainText('Feeds: ');
    await expect(connected).toContainText('Sync failed · 401 Unauthorized');
    await expect(connected).toContainText('Token expires in 3 days');
    await expect(connected.getByRole('button', { name: 'Fix HRMS (Keka)' })).toBeVisible();
    await expect(connected.getByRole('button', { name: 'Settings Jira Software' })).toBeVisible();

    const jira = card(page, 'Jira configuration');

    for (const board of ['PLAT', 'NET', 'UI']) {
      await expect(jira.getByRole('checkbox', { name: `${board} board` })).toBeVisible();
    }

    await expect(jira.getByLabel('Story points field')).toHaveValue('customfield_10016');
    await expect(jira.getByLabel('Sync')).toHaveValue('webhooks_hourly');
    await expect(jira.getByRole('switch', { name: 'Ignore edits by bots and non-assignees when computing last activity' })).toBeVisible();
    await expect(jira).toContainText('/rest/agile/1.0/board/{id}/sprint?state=active');

    const credentials = card(page, 'Credentials');

    await expect(credentials).toContainText('Only Admins see this page · secrets are encrypted and shown as the last 4 characters');
    await expect(credentials.locator('thead th')).toHaveText(['Tool', 'Method', 'Saved', 'Rotation', 'Last test', 'Actions']);
    await expect(credentials.getByRole('button', { name: 'View Jira Software' })).toBeVisible();

    const available = card(page, 'Available (6)');

    await expect(available.getByRole('button', { name: 'Connect PagerDuty' })).toBeDisabled();
    await expect(available).toContainText('Planned: not available in this version');

    await expect(card(page, 'Credential activity')).toContainText('Scheduled sync failed: 401 Unauthorized');

    const log = card(page, 'Sync log');

    await log.getByRole('button', { name: 'Sync now' }).click();
    await expect(toast(page, 'Synced')).toBeVisible();
    await expect(log.locator('tbody tr').first()).toContainText('Jira Software');

    await expectNoHorizontalScroll(page);
  });

  test('Settings shows the three tabs; Change credentials needs a passing test before Save', async ({ page }) => {
    await signIn(page, DEMO.admin);
    await page.goto('/integrations');
    await card(page, 'Connected (7)').getByRole('button', { name: 'Settings Confluence' }).click();

    const settings = page.getByRole('dialog', { name: 'Confluence' });

    await expect(settings.getByRole('tab')).toHaveText(['Credentials', 'Data & scopes', 'Sync']);
    await expect(settings).toContainText('Secrets are shown as the last 4 characters only.');
    await settings.getByRole('tab', { name: 'Data & scopes' }).click();
    await expect(settings).toContainText('Pulse needs');
    await settings.getByRole('tab', { name: 'Sync' }).click();
    await expect(settings.getByLabel('Sync mode')).toBeVisible();

    for (const action of ['Change credentials', 'Test connection', 'Disconnect', 'Close']) {
      await expect(settings.locator('.pl-modal-foot').getByRole('button', { name: action, exact: true })).toBeVisible();
    }

    await settings.getByRole('button', { name: 'Change credentials' }).click();

    const form = page.getByRole('dialog', { name: 'Change credentials · Confluence' });
    const save = form.getByRole('button', { name: 'Save new credentials' });

    await expect(save).toBeDisabled();

    const secret = form.locator('input[type="password"]').first();

    await expect(secret).toHaveValue('');
    await form.getByRole('button', { name: 'Test connection' }).click();
    await expect(form).toContainText('is required.');

    const fields = form.locator('input.pl-input');
    const count = await fields.count();

    for (let i = 0; i < count; i += 1) {
      const input = fields.nth(i);

      if ((await input.inputValue()) === '') {
        const type = await input.getAttribute('type');

        await input.fill(type === 'url' ? 'https://e2e.atlassian.net' : type === 'email' ? 'bot@company.com' : `e2e-${unique()}-tok`);
      }
    }

    await form.getByRole('button', { name: 'Test connection' }).click();
    await expect(form.getByRole('status')).toContainText('Passed');
    await expect(save).toBeEnabled();

    await secret.fill(`changed-${unique()}`);
    await expect(save).toBeDisabled();
    await form.getByRole('button', { name: 'Test connection' }).click();
    await expect(save).toBeEnabled();
    await save.click();

    await expect(toast(page, 'Credentials changed · Confluence')).toBeVisible();
    await expect(card(page, 'Credential activity')).toContainText('Credentials changed');
  });

  test('Scheduled jobs lists every job and the Admin runs one now (task 5.5)', async ({ page }) => {
    await signIn(page, DEMO.admin);
    await page.goto('/integrations');

    const jobs = card(page, 'Scheduled jobs');

    for (const name of [
      'progress.friday-pulse',
      'progress.monday-digest',
      'attendance.month-end-reminder',
      'work.stale-signal-refresh',
      'integrations.token-expiry-check',
      'platform.retention-purge',
    ]) {
      await expect(jobs).toContainText(name);
    }

    await jobs.getByRole('button', { name: 'Run platform.retention-purge now' }).click();
    await expect(toast(page, 'platform.retention-purge · Succeeded')).toBeVisible();
    await expect(jobs.locator('tr', { hasText: 'platform.retention-purge' })).toContainText('Succeeded');

    await expectNoHorizontalScroll(page);
  });

  test('Manager and Employee get the no-access card on Integrations', async ({ page }) => {
    for (const who of [DEMO.manager, DEMO.employee]) {
      await signIn(page, who);
      await page.goto('/integrations');
      await expect(noAccess(page)).toBeVisible();
      await page.getByRole('banner').getByRole('button', { name: 'Sign out' }).click();
    }
  });
});

// ------------------------------------------------------------------ 11.6 Notifications and menu badges

test.describe('Notifications', () => {
  test('the bell shows the unread count; an item opens its screen and is marked read', async ({ page }) => {
    resetNotifications(DEMO.employee.email);
    await signIn(page, DEMO.employee);

    const bell = page.getByRole('button', { name: /^Notifications, 1 unread$/ });

    await expect(bell).toBeVisible();
    await expect(page.locator('.pl-bell-cnt')).toHaveText('1');
    await bell.click();

    const panel = page.getByRole('dialog', { name: 'Notifications' });

    await expect(panel.getByText('Notifications', { exact: true })).toBeVisible();
    await expect(panel.getByRole('button', { name: 'Mark all read' })).toBeVisible();

    const item = panel.getByRole('button', { name: /Share 360° feedback on Rohit Kulkarni/ });

    await expect(item).toContainText('26 Sep · open to review');
    await item.click();

    await expect(page).toHaveURL(/\/reviews\?tab=360$/);
    await expect(page.locator('.pl-bell-cnt')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Notifications', exact: true })).toBeVisible();
  });

  test('Mark all read clears the count; Esc closes the panel and returns focus', async ({ page }) => {
    resetNotifications('rohit@pulse.demo');
    await signIn(page, 'rohit@pulse.demo');

    const bell = page.getByRole('button', { name: /^Notifications, \d+ unread$/ });

    await bell.click();

    const panel = page.getByRole('dialog', { name: 'Notifications' });

    await expect(panel.locator('.pl-bell-item.is-unread').first()).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(panel).toBeHidden();
    await expect(bell).toBeFocused();

    await bell.click();
    await panel.getByRole('button', { name: 'Mark all read' }).click();
    await expect(page.locator('.pl-bell-cnt')).toHaveCount(0);
    await expect(panel.locator('.pl-bell-item.is-unread')).toHaveCount(0);
    await expect(panel.getByRole('button', { name: 'Mark all read' })).toHaveCount(0);
  });

  test('menu badges repeat the counts that need action, for menu items only', async ({ page }) => {
    await page.route('**/api/v1/notifications/badges', (route) =>
      route.fulfill({ json: { badges: { actions: 3, users: 2, 'work-items': 0, 'not-in-menu': 9 } } }),
    );
    await signIn(page, DEMO.manager);

    await expect(menu(page).getByRole('link', { name: /^Action center/ }).locator('.cnt')).toHaveText('3');
    await expect(menu(page).getByRole('link', { name: /^Users/ }).locator('.cnt')).toHaveText('2');
    await expect(menu(page).getByRole('link', { name: /^Work items/ }).locator('.cnt')).toHaveCount(0);
    await expect(menu(page).locator('.cnt')).toHaveCount(2);
  });
});
