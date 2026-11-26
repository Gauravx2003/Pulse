import { randomBytes } from 'node:crypto';
import { defineConfig, devices } from '@playwright/test';

/**
 * Live-connector end-to-end flows (tasks 26.3 and 26.6, change pulse-v2-live-connectors):
 * `npm run test:live-flows`. They run apart from the main suite, on their own servers and their own
 * database, because they connect a live tool and sync it:
 *  - the database `pulse_e2e_live` is dropped and recreated, so every run starts from the demo seed
 *  - "today" is 26 Nov 2026, inside Jira sprint 147 of the e2e fixtures (live-fixtures/jira-e2e/),
 *    so the synced sprint is the live sprint every screen reads
 *  - the mock tool listens on 127.0.0.1, so loopback origins are allowed (never in production)
 *  - a fresh PULSE_SECRET_KEY per run encrypts the saved credentials
 * Build the backend first: `mvn -f ../backend package -DskipTests`.
 */
const WEB_PORT = Number(process.env.E2E_LIVE_WEB_PORT ?? 5176);
const API_PORT = Number(process.env.E2E_LIVE_API_PORT ?? 8091);
const DB = 'pulse_e2e_live';

process.env.PULSE_E2E_DB = DB;

export default defineConfig({
  testDir: './e2e/live-flows',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 180_000,
  reporter: [['list']],
  use: {
    baseURL: `http://localhost:${WEB_PORT}`,
    channel: 'chrome',
    trace: 'retain-on-failure',
    ...devices['Desktop Chrome'],
  },
  webServer: [
    {
      command: `bash e2e/start-backend.sh ${API_PORT} http://localhost:${WEB_PORT}`,
      url: `http://localhost:${API_PORT}/health`,
      env: {
        PULSE_E2E_DB: DB,
        PULSE_E2E_FRESH_DB: 'true',
        PULSE_TODAY: '2026-11-26',
        PULSE_LIVE_ALLOW_LOOPBACK: 'true',
        PULSE_SECRET_KEY: randomBytes(32).toString('base64'),
      },
      reuseExistingServer: false,
      timeout: 180_000,
    },
    {
      command: `npx vite --port ${WEB_PORT} --strictPort`,
      url: `http://localhost:${WEB_PORT}`,
      env: { PULSE_API_TARGET: `http://localhost:${API_PORT}` },
      reuseExistingServer: false,
      timeout: 180_000,
    },
  ],
});
