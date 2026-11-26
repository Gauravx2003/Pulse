import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests (tasks 6.3 and 6.4). `npm run test:e2e` starts its own Vite dev server on 5174
 * and its own backend on 8089 against the database `pulse_e2e` (e2e/start-backend.sh), so it never
 * touches a dev server or backend you already run. Build the backend first:
 * `mvn -f ../backend package -DskipTests`. Uses the installed Google Chrome (no browser download).
 * Set E2E_WEB_PORT, E2E_API_PORT and PULSE_E2E_DB to run a second suite (e.g. the UI audit) beside
 * one already running, on its own servers and database.
 */
const WEB_PORT = Number(process.env.E2E_WEB_PORT ?? 5174);
const API_PORT = Number(process.env.E2E_API_PORT ?? 8089);

export default defineConfig({
  testDir: './e2e',
  // the live-connector flows run on their own servers and database: playwright.live.config.js
  testIgnore: /live-flows\//,
  fullyParallel: true,
  workers: 2,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: `http://localhost:${WEB_PORT}`,
    channel: 'chrome',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'chrome', testIgnore: [/flows\.spec\.js/, /live-flows\//], use: { ...devices['Desktop Chrome'], channel: 'chrome' } },
    // Task 23.3: the cross-module flows write rows the module suites read, so they run after them.
    { name: 'flows', testMatch: /flows\.spec\.js/, dependencies: ['chrome'], use: { ...devices['Desktop Chrome'], channel: 'chrome' } },
  ],
  webServer: [
    {
      command: `bash e2e/start-backend.sh ${API_PORT} http://localhost:${WEB_PORT}`,
      url: `http://localhost:${API_PORT}/health`,
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
    {
      // E2E_PREVIEW_DIR serves a production build (built into that directory) instead of the dev
      // server: the UI audit's first-screen timing is only meaningful on the built bundle
      command: process.env.E2E_PREVIEW_DIR
        ? `npx vite build --outDir ${process.env.E2E_PREVIEW_DIR} --emptyOutDir && npx vite preview --outDir ${process.env.E2E_PREVIEW_DIR} --port ${WEB_PORT} --strictPort`
        : `npx vite --port ${WEB_PORT} --strictPort`,
      url: `http://localhost:${WEB_PORT}`,
      env: { PULSE_API_TARGET: `http://localhost:${API_PORT}` },
      reuseExistingServer: !process.env.CI,
      timeout: 180_000,
    },
  ],
});
