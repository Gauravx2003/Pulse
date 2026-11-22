import { execFileSync } from 'node:child_process';

/**
 * Direct SQL against the e2e database (the one e2e/start-backend.sh uses), for arranging state that a
 * repeated run would otherwise have changed, such as notifications marked read by an earlier run.
 * Uses the same DB_* defaults as start-backend.sh. Only for test set-up, never for assertions about
 * what the app wrote.
 */
export function sql(statement) {
  const env = {
    ...process.env,
    PGPASSWORD: process.env.DB_PASSWORD ?? 'pulse_secret_dev',
  };

  return execFileSync(
    'psql',
    [
      '-h', process.env.DB_HOST ?? 'localhost',
      '-p', process.env.DB_PORT ?? '5432',
      '-U', process.env.DB_USER ?? 'pulse',
      '-d', process.env.PULSE_E2E_DB ?? 'pulse_e2e',
      '-v', 'ON_ERROR_STOP=1',
      '-tAc', statement,
    ],
    { env, encoding: 'utf8' },
  ).trim();
}

/** Marks a demo account's seeded notifications unread again. */
export function resetNotifications(email) {
  sql(
    `UPDATE notifications SET read_at = NULL WHERE user_id = (SELECT id FROM users WHERE email = '${email.replaceAll("'", "''")}')`,
  );
}
