// Verifies the generated seed SQL is current: run with `node --test seed/build-sql.test.mjs`.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { BASE_FILE, DEMO_FILE, RULE_GROUPS, SEED_SQL_DIR, buildBase, buildDemo, lit } from './build-sql.mjs';

test('committed seed SQL matches a fresh generation', () => {
  assert.equal(readFileSync(path.join(SEED_SQL_DIR, BASE_FILE), 'utf8'), buildBase(),
    'run `node seed/build-sql.mjs` and commit the result');
  assert.equal(readFileSync(path.join(SEED_SQL_DIR, DEMO_FILE), 'utf8'), buildDemo(),
    'run `node seed/build-sql.mjs` and commit the result');
});

test('generation is deterministic', () => {
  assert.equal(buildBase(), buildBase());
  assert.equal(buildDemo(), buildDemo());
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
