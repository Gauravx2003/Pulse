// Verifies task 2.2: run with `node --test seed/`.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { CULTURE_SIGNAL_KEYS, DATASETS, OUT_DIR, SEEDED_QUARTER, extract } from './extract.mjs';
import { loadPrototype } from './lib/load-prototype.mjs';

const read = (dir, name) => JSON.parse(readFileSync(path.join(dir, `${name}.json`), 'utf8'));
const snapshot = (dir) => Object.fromEntries(
  readdirSync(dir).filter((f) => f.endsWith('.json')).sort().map((f) => [f, readFileSync(path.join(dir, f), 'utf8')]));

const runA = mkdtempSync(path.join(tmpdir(), 'pulse-seed-a-'));
const runB = mkdtempSync(path.join(tmpdir(), 'pulse-seed-b-'));
extract(runA);
extract(runB);
extract(runB); // re-run into a non-empty directory
process.on('exit', () => [runA, runB].forEach((d) => rmSync(d, { recursive: true, force: true })));

test('re-runnable: two runs produce byte-identical files', () => {
  assert.deepEqual(snapshot(runA), snapshot(runB));
});

test('committed seed/extracted matches a fresh extraction', () => {
  assert.deepEqual(snapshot(OUT_DIR), snapshot(runA), 'run `node seed/extract.mjs` and commit the result');
});

test('every dataset is a non-empty array', () => {
  for (const name of Object.keys(DATASETS)) {
    const rows = read(runA, name);
    assert.ok(Array.isArray(rows) && rows.length > 0, `${name}.json is empty`);
  }
});

test('every non-empty sample dataset of the prototype is extracted', () => {
  const p = loadPrototype();
  const covered = {
    PEOPLE: 'people', USERS: 'users', ROLES: 'roles', PERMS: 'permissions', JOINED: 'joined_dates', CTC: 'ctc',
    HOLIDAYS: 'holidays', SPRINTS: 'sprints', RELEASES: 'releases', HIST: 'issue_history', PRS: 'pull_requests',
    BUGS: 'bugs', SUPPORT: 'support_tickets', EXTRA: 'extra_mile', ATT: 'attendance', ATT_UP: 'attendance_uploads',
    VISITS: 'visits', OKRS: 'okrs', TPLANS: 'training_plans', TASSIGN: 'training_assignments', CYCLES: 'review_cycles',
    REQS: 'review_requests', KUDOS: 'kudos', ONE_ON_ONES: 'one_on_ones', PULSE: 'weekly_pulse', SKILLS: 'skills',
    ROADMAP: 'roadmap', EVIDENCE: 'evidence', COVER: 'coverage', DISC: 'discipline_inputs', INTEGRATIONS: 'integrations',
    NOTIFS: 'notifications', IMPORT_LOG: 'import_log', MONTH_LEAVE: 'month_leave', WEEKEND_WORK: 'weekend_work',
  };
  for (const [global, file] of Object.entries(covered)) {
    const v = p.get(global);
    const size = Array.isArray(v) ? v.length : Object.keys(v).length;
    assert.ok(size > 0, `${global} is empty in the prototype`);
    assert.ok(read(runA, file).length > 0, `${global} -> ${file}.json missing or empty`);
  }
  assert.equal(read(runA, 'people').length, p.get('PEOPLE').length);
  assert.equal(read(runA, 'pull_requests').length, p.get('PRS').length);
});

test('culture_signals: exactly the 12 reported parameters for every person', () => {
  const rows = read(runA, 'culture_signals');
  const people = read(runA, 'people').map((x) => x.id);
  const keys = Object.values(CULTURE_SIGNAL_KEYS).sort();
  assert.equal(keys.length, 12);
  for (const person of people) {
    const mine = rows.filter((r) => r.person === person);
    assert.equal(mine.length, 12, `${person} has ${mine.length} rows`);
    assert.deepEqual(mine.map((r) => r.param_key).sort(), keys);
  }
  assert.ok(rows.every((r) => r.is_simulated === true && r.quarter === SEEDED_QUARTER && r.source_label));
});

test('culture_signals values equal the prototype valData output', () => {
  const p = loadPrototype();
  const valData = p.get('valData');
  const byPerson = new Map(p.get('PEOPLE').map((x) => [x.id, x]));
  for (const row of read(runA, 'culture_signals')) {
    const prm = valData(byPerson.get(row.person), row.culture_value).params.find((x) => x.name === row.param_name);
    assert.ok(prm, `${row.person} ${row.param_name} not in valData`);
    assert.equal(row.raw_value, prm.raw);
    assert.equal(row.display_value, prm.disp);
    assert.equal(row.source_label, prm.src);
  }
});

test('culture_signals agree with the prototype hash formula (independent check)', () => {
  // app.js: hsh(s) and r(k,a,b) = a + hsh(p.id + v + k) % (b - a + 1)
  const hsh = (s) => { let h = 0; for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) % 9973; return h; };
  const r = (id, v, k, a, b) => a + (hsh(id + v + k) % (b - a + 1));
  const spec = {
    manager_follow_ups: ['Outcome Ownership', 'fu', 0, 5],
    decisions_documented: ['Decision Making', 'dl', 1, 6],
    median_time_to_decide: ['Decision Making', 'td', 1, 5],
    decisions_reversed: ['Decision Making', 'dr', 0, 2],
    root_cause_analyses_written: ['Problem Solving', 'rca', 0, 3],
    spikes_and_experiments_run: ['Risk Appetite', 'sx', 0, 4],
    ideas_proposed_piloted: ['Risk Appetite', 'pi', 0, 3],
    risks_raised_in_planning: ['Risk Appetite', 'rl', 0, 5],
    time_to_productivity_new_tool: ['Adaptability', 'tp', 3, 15],
    cross_team_issues: ['Collaboration', 'cx', 0, 5],
    docs_templates_prompts_shared: ['Resourcefulness', 'ds', 0, 8],
    hours_saved_by_automation: ['Resourcefulness', 'hs', 0, 40],
  };
  for (const row of read(runA, 'culture_signals')) {
    const [value, k, a, b] = spec[row.param_key];
    assert.equal(row.culture_value, value);
    assert.equal(row.raw_value, r(row.person, value, k, a, b), `${row.person} ${row.param_key}`);
  }
});
