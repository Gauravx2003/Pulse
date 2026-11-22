import { expect, test } from '@playwright/test';

/** Demo accounts (password demo123) of the three built-in roles. */
export const DEMO = {
  admin: { email: 'hr@pulse.demo', name: 'Anita Desai', role: 'Admin', landing: '/actions' },
  manager: { email: 'rahul@pulse.demo', name: 'Rahul Nair', role: 'Manager', landing: '/actions' },
  employee: { email: 'priya@pulse.demo', name: 'Priya Shah', role: 'Employee', landing: '/overview' },
};

export const DEMO_PASSWORD = 'demo123';

/** Every demo account of the seed (reference handoff table), for tests that run as any of them. */
export const DEMO_ACCOUNTS = [
  'hr@pulse.demo',
  'rahul@pulse.demo',
  'priya@pulse.demo',
  'arjun@pulse.demo',
  'neha@pulse.demo',
  'kiran@pulse.demo',
  'dev@pulse.demo',
  'sara@pulse.demo',
  'meera@pulse.demo',
  'anil@pulse.demo',
  'kavya@pulse.demo',
  'rohan@pulse.demo',
  'sana@pulse.demo',
  'rohit@pulse.demo',
  'anjali@pulse.demo',
];

/** A DEMO entry ('admin', 'manager', 'employee'), a DEMO object or any email, as an email. */
export function emailOf(who) {
  if (typeof who === 'string') {
    return DEMO[who]?.email ?? who;
  }

  return who.email;
}

let ipSeq = 0;

/** A distinct client IP per test context, so the per-IP sign-in throttle never trips in a run. */
export async function distinctIp(context) {
  ipSeq += 1;
  const n = (process.pid * 97 + ipSeq) % 65_000;

  await context.setExtraHTTPHeaders({ 'X-Real-IP': `10.${(n >> 8) & 255}.${n & 255}.${(ipSeq % 250) + 1}` });
}

/** Signs in through the sign-in screen as any demo account (an email, a DEMO entry or its key). */
export async function signIn(page, who, password = DEMO_PASSWORD) {
  const email = emailOf(who);

  await distinctIp(page.context());
  await page.goto('/sign-in');
  await page.getByLabel('Work email', { exact: true }).fill(email);
  await page.getByLabel('Password', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.locator('.pl-shell')).toBeVisible();
}

/** Fails when the page scrolls sideways (spec platform/app-shell-ui, "Responsive layout"). */
export async function expectNoHorizontalScroll(page) {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);

  expect(overflow, 'horizontal page scroll in px').toBeLessThanOrEqual(0);
}

/**
 * Signs in through the API instead of the sign-in screen (faster, for tests that visit many pages,
 * such as the UI audit). The session cookie lands in the page's context; the app reads GET /me on
 * the next navigation. Fails the test when the sign-in is refused.
 */
export async function signInViaApi(page, who, password = DEMO_PASSWORD) {
  const email = emailOf(who);
  const baseURL = new URL(test.info().project.use.baseURL).origin;

  await distinctIp(page.context());

  const res = await page.request.post('/api/v1/auth/login', {
    data: { email, password },
    headers: { Origin: baseURL },
  });

  expect(res.status(), `sign-in as ${email}`).toBe(200);

  return res.json();
}
