import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests (tasks 6.3 and 6.4). `npm run test:e2e` starts its own Vite dev server on 5174
 * and its own backend on 8089 against the database `pulse_e2e` (e2e/start-backend.sh), so it never
 * touches a dev server or backend you already run. Build the backend first:
 * `mvn -f ../backend package -DskipTests`. Uses the installed Google Chrome (no browser download).
 */
const WEB_PORT = 5174;
const API_PORT = 8089;

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  workers: 2,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: `http://localhost:${WEB_PORT}`,
    channel: 'chrome',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chrome', use: { ...devices['Desktop Chrome'], channel: 'chrome' } }],
  webServer: [
    {
      command: `bash e2e/start-backend.sh ${API_PORT} http://localhost:${WEB_PORT}`,
      url: `http://localhost:${API_PORT}/health`,
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
    },
    {
      command: `npx vite --port ${WEB_PORT} --strictPort`,
      url: `http://localhost:${WEB_PORT}`,
      env: { PULSE_API_TARGET: `http://localhost:${API_PORT}` },
      reuseExistingServer: !process.env.CI,
      timeout: 60_000,
    },
  ],
});
