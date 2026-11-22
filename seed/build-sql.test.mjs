// Verifies the generated seed SQL is current: run with `node --test seed/build-sql.test.mjs`.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { BASE_FILE, DEMO_FILE, HISTORY_FILE, HISTORY_QUARTERS, NOTIFICATIONS_FILE, PERFORMANCE_FILE, RULE_GROUPS, SEED_SQL_DIR, buildBase, buildDemo, buildHistory, buildNotifications, buildPerformance, lit } from './build-sql.mjs';

test('committed seed SQL matches a fresh generation', () => {
  assert.equal(readFileSync(path.join(SEED_SQL_DIR, BASE_FILE), 'utf8'), buildBase(),
    'run `node seed/build-sql.mjs` and commit the result');
  assert.equal(readFileSync(path.join(SEED_SQL_DIR, DEMO_FILE), 'utf8'), buildDemo(),
    'run `node seed/build-sql.mjs` and commit the result');
  assert.equal(readFileSync(path.join(SEED_SQL_DIR, PERFORMANCE_FILE), 'utf8'), buildPerformance(),
    'run `node seed/build-sql.mjs` and commit the result');
  assert.equal(readFileSync(path.join(SEED_SQL_DIR, HISTORY_FILE), 'utf8'), buildHistory(),
    'run `node seed/build-sql.mjs` and commit the result');
  assert.equal(readFileSync(path.join(SEED_SQL_DIR, NOTIFICATIONS_FILE), 'utf8'), buildNotifications(),
    'run `node seed/build-sql.mjs` and commit the result');
});

test('demo notifications: one row per prototype notification, none with salary or 360° comment text', () => {
  const lines = buildNotifications().split('\n').filter((l) => l.startsWith('INSERT INTO notifications '));
  assert.equal(lines.length, 32);
  assert.ok(lines.some((l) => l.includes("'Share 360° feedback on Rohit Kulkarni · Rohit · Software Engineer onboarding · Day 14 · due 28 Sept'")));
  assert.ok(lines.every((l) => !l.includes('₹')));
});

test('index history has one period snapshot per closed quarter, from the prototype trend', () => {
  const sql = buildHistory();
  assert.equal(sql.split('\n').filter((l) => l.startsWith('INSERT INTO calc_snapshots ')).length, HISTORY_QUARTERS.length);
  for (const q of HISTORY_QUARTERS) assert.ok(sql.includes(`'q:${q}'`), q);
  assert.ok(sql.includes("('ps', 3.4)") && sql.includes("('ps', 3.7)"), 'trend[0] and trend[2] of ps');
});

test('performance seed has pillars, 360° ratings and 12 culture signals per person', () => {
  const sql = buildPerformance();
  const count = (table) => sql.split('\n').filter((l) => l.startsWith(`INSERT INTO ${table} `)).length;
  assert.equal(count('pillars'), 8);
  assert.equal(count('culture_signals'), 8 * 12);
  assert.ok(count('culture_ratings') >= 8 * 11);
});

test('generation is deterministic', () => {
  assert.equal(buildBase(), buildBase());
  assert.equal(buildDemo(), buildDemo());
  assert.equal(buildPerformance(), buildPerformance());
  assert.equal(buildHistory(), buildHistory());
});

test('every rule group is a Rules & thresholds card', () => {
  const cards = new Set(['staleness_capacity', 'utilization_bands', 'index_weights', 'culture_weights',
    'discipline_bands', 'effort_points', 'customer_visits', 'pr_standard', 'training', 'attendance_leave',
    'appraisal_standards']);
  for (const [key, group] of Object.entries(RULE_GROUPS)) assert.ok(cards.has(group), `${key} -> ${group}`);
});

test('SQL literals escape quotes and keep backslashes', () => {
  assert.equal(lit("O'Brien"), "'O''Brien'");
  assert.equal(lit('^[A-Z]+-\\d+'), "'^[A-Z]+-\\d+'");
  assert.equal(lit(null), 'NULL');
});
