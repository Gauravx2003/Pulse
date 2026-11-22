import { expect } from '@playwright/test';

/** Demo accounts (password demo123) of the three built-in roles. */
export const DEMO = {
  admin: { email: 'hr@pulse.demo', name: 'Anita Desai', role: 'Admin', landing: '/actions' },
  manager: { email: 'rahul@pulse.demo', name: 'Rahul Nair', role: 'Manager', landing: '/actions' },
  employee: { email: 'priya@pulse.demo', name: 'Priya Shah', role: 'Employee', landing: '/overview' },
};

export const DEMO_PASSWORD = 'demo123';

let ipSeq = 0;

/** A distinct client IP per test context, so the per-IP sign-in throttle never trips in a run. */
export async function distinctIp(context) {
  ipSeq += 1;
  const n = (process.pid * 97 + ipSeq) % 65_000;

  await context.setExtraHTTPHeaders({ 'X-Real-IP': `10.${(n >> 8) & 255}.${n & 255}.${(ipSeq % 250) + 1}` });
}

/** Signs in through the sign-in screen as any demo account (an email or a DEMO entry). */
export async function signIn(page, who, password = DEMO_PASSWORD) {
  const email = typeof who === 'string' ? who : who.email;

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
