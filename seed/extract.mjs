#!/usr/bin/env node
// Seed extraction tool (task 2.2).
//
// Loads reference/2-prototype/src/app.js in a sandbox (lib/load-prototype.mjs) and writes its
// sample datasets as JSON arrays under seed/extracted/, one file per dataset. Keyed maps from the
// prototype (for example ATT[person][date]) are flattened into rows. It also runs the prototype's
// valData for every person and writes the 12 hash-generated culture parameters to
// culture_signals.json (loaded into culture_signals with is_simulated = true).
//
// Re-runnable: the output directory is rewritten and the output is deterministic.
// Usage: node seed/extract.mjs [outDir]
import { mkdirSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { REPO_ROOT, loadPrototype } from './lib/load-prototype.mjs';

export const OUT_DIR = path.join(REPO_ROOT, 'seed', 'extracted');

/** The seeded quarter: valData only computes Q3 2026 (app.js Q3P). */
export const SEEDED_QUARTER = '2026-Q3';

/** The 12 culture parameters the prototype generates with hsh() (design.md decision 6). */
export const CULTURE_SIGNAL_KEYS = {
  'Follow-ups needed from the manager': 'manager_follow_ups',
  'Decisions documented': 'decisions_documented',
  'Median time to decide': 'median_time_to_decide',
  'Decisions reversed': 'decisions_reversed',
  'Root-cause analyses written': 'root_cause_analyses_written',
  'Spikes and experiments run': 'spikes_and_experiments_run',
  'Ideas proposed → piloted': 'ideas_proposed_piloted',
  'Risks raised in planning': 'risks_raised_in_planning',
  'Time to productivity on a new tool': 'time_to_productivity_new_tool',
  'Cross-team issues': 'cross_team_issues',
  'Docs, templates and prompts shared': 'docs_templates_prompts_shared',
  'Hours saved by automation': 'hours_saved_by_automation',
};

const entries = (obj) => Object.entries(obj ?? {});
const perPerson = (obj, field = 'value') => entries(obj).map(([person, value]) => ({ person, [field]: value }));
const perPersonList = (obj) => entries(obj).flatMap(([person, list]) => list.map((item) => ({ person, ...item })));

/** dataset file name -> function(get) returning an array of rows. */
export const DATASETS = {
  // people, accounts, org
  people: (p) => p.json('PEOPLE'),
  users: (p) => p.json('USERS'),
  roles: (p) => entries(p.json('ROLES')).map(([name, r]) => ({
    name, built_in: r.builtin, description: r.desc, permissions: r.perms,
  })),
  permissions: (p) => p.json('PERMS').map(([key, area, description]) => ({ key, area, description })),
  departments: (p) => p.json('DEPTS').map((name) => ({ name })),
  joined_dates: (p) => perPerson(p.json('JOINED'), 'joined_on'),
  ctc: (p) => perPerson(p.json('CTC'), 'ctc_lakh'),
  base_hours: (p) => perPerson(p.json('BASE_H'), 'hours_per_day'),
  scale: (p) => perPerson(p.json('SCALE')),
  skills: (p) => entries(p.json('SKILLS')).flatMap(([person, list]) =>
    list.map(([skill, level, target]) => ({ person, skill, level, target }))),
  roadmap: (p) => perPersonList(p.json('ROADMAP')),
  evidence: (p) => p.json('EVIDENCE'),

  // calendar
  holidays: (p) => entries(p.json('HOLIDAYS')).map(([date, name]) => ({ date, name })),
  sprints: (p) => p.json('SPRINTS'),
  next_sprint: (p) => [p.json('NEXT_SPRINT')],
  releases: (p) => p.json('RELEASES'),
  month_leave: (p) => entries(p.json('MONTH_LEAVE')).flatMap(([person, dates]) => dates.map((date) => ({ person, date }))),
  weekend_work: (p) => entries(p.json('WEEKEND_WORK')).map(([k, hours]) => {
    const [person, date] = k.split('|');
    return { person, date, hours };
  }),

  // work
  issues: (p) => p.json('PEOPLE').flatMap((person) => (person.tasks ?? []).map((t) => ({ person: person.id, ...t }))),
  issue_history: (p) => p.json('HIST'),
  pull_requests: (p) => p.json('PRS'),
  bugs: (p) => p.json('BUGS'),
  support_tickets: (p) => p.json('SUPPORT'),
  extra_mile: (p) => p.json('EXTRA'),
  // day by day from the prototype's dayInfo (task 7.2): hours logged and days with commits, from the
  // first seeded month to TODAY; customer-visit days are left out (their on-site hours are Track 3's)
  worklogs: (p) => workDays(p).filter((d) => d.hours > 0).map(({ person, date, hours }) => ({ person, date, hours })),
  commit_days: (p) => workDays(p).filter((d) => d.commits).map(({ person, date }) => ({ person, date })),
  coverage: (p) => entries(p.json('COVER')).flatMap(([area, byPerson]) =>
    entries(byPerson).map(([person, count]) => ({ area, person, count }))),
  areas: (p) => p.json('AREAS').map((name) => ({ name })),
  discipline_inputs: (p) => entries(p.json('DISC')).map(([person, d]) => ({ person, ...d })),

  // time
  attendance: (p) => entries(p.json('ATT')).flatMap(([person, days]) =>
    entries(days).map(([date, rec]) => ({ person, date, ...rec }))),
  attendance_uploads: (p) => entries(p.json('ATT_UP')).map(([month, u]) => ({ month, ...u })),
  visits: (p) => p.json('VISITS'),

  // goals and growth
  okrs: (p) => p.json('OKRS'),
  training_plans: (p) => p.json('TPLANS'),
  training_assignments: (p) => p.json('TASSIGN'),

  // culture and reviews
  culture_values: (p) => p.json('VALUES').map((name, i) => ({
    name, order: i, description: p.json('VALUE_DATA')[name] ?? null,
    signals: p.json('SIGNALS')[name] ?? null, anchors: p.json('ANCHORS')[name] ?? null,
  })),
  review_cycles: (p) => p.json('CYCLES'),
  review_requests: (p) => p.json('REQS'),
  kudos: (p) => p.json('KUDOS'),
  one_on_ones: (p) => perPersonList(p.json('ONE_ON_ONES')),
  weekly_pulse: (p) => perPersonList(p.json('PULSE')),

  // admin
  integrations: (p) => p.json('INTEGRATIONS'),
  integration_credentials: (p) => entries(p.json('CREDS')).map(([tool, c]) => ({ tool, ...c })),
  integration_auth_methods: (p) => entries(p.json('AUTH')).map(([tool, a]) => ({ tool, ...a })),
  sync_log: (p) => p.json('SYNCLOG').map(([time, tool, message, outcome]) => ({ time, tool, message, outcome })),
  credential_log: (p) => p.json('CREDLOG').map(([at, tool, message, by]) => ({ at, tool, message, by })),
  import_log: (p) => p.json('IMPORT_LOG'),
  notifications: (p) => p.json('NOTIFS'),

  // rule defaults (reference section 12)
  rule_defaults: (p) => [
    ...entries(p.json('S.rules')).map(([key, value]) => ({ key, value })),
    { key: 'appraisal', value: p.json('APR_DEFAULT') },
    { key: 'valueWeightsDefault', value: p.json('VALUE_W_DEFAULT') },
    { key: 'prTitlePattern', value: p.get('PR_TITLE_DEFAULT') },
  ],

  culture_signals: (p) => cultureSignals(p),
};

/** First day of the day-by-day work data: the first month of the prototype's history (PER_MONTHS). */
export const WORK_FROM = '2026-04-01';

/** dayInfo(person, day) of every person and day from WORK_FROM to TODAY, visit days left out. */
export function workDays(p) {
  const dayInfo = p.get('dayInfo');
  const days = p.get('days');
  const visitOn = p.get('visitOn');
  const today = p.get('TODAY');
  return p.get('PEOPLE').flatMap((person) => days(WORK_FROM, today)
    .filter((date) => !visitOn(person.id, date))
    .map((date) => {
      const x = dayInfo(person, date);
      return { person: person.id, date, hours: x.h, commits: !!x.gh };
    }));
}

export function cultureSignals(p) {
  const valData = p.get('valData');
  const values = p.json('VALUES');
  const people = p.get('PEOPLE');
  const rows = [];
  for (const person of people) {
    for (const value of values) {
      for (const prm of valData(person, value).params) {
        const key = CULTURE_SIGNAL_KEYS[prm.name];
        if (!key) continue;
        rows.push({
          person: person.id,
          quarter: SEEDED_QUARTER,
          param_key: key,
          param_name: prm.name,
          culture_value: value,
          raw_value: prm.raw,
          display_value: prm.disp,
          source_label: prm.src,
          is_simulated: true,
        });
      }
    }
  }
  return rows;
}

export function extract(outDir = OUT_DIR) {
  const p = loadPrototype();
  mkdirSync(outDir, { recursive: true });
  for (const f of readdirSync(outDir)) {
    if (f.endsWith('.json')) rmSync(path.join(outDir, f));
  }
  const summary = {};
  for (const [name, build] of Object.entries(DATASETS)) {
    const rows = build(p);
    if (!Array.isArray(rows) || rows.length === 0) {
      throw new Error(`dataset ${name} is empty - the prototype changed or the extractor is wrong`);
    }
    writeFileSync(path.join(outDir, `${name}.json`), JSON.stringify(rows, null, 2) + '\n');
    summary[name] = rows.length;
  }
  return summary;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const outDir = process.argv[2] ? path.resolve(process.argv[2]) : OUT_DIR;
  const summary = extract(outDir);
  for (const [name, n] of Object.entries(summary)) console.log(`${name.padEnd(26)} ${n}`);
  console.log(`Wrote ${Object.keys(summary).length} datasets to ${path.relative(process.cwd(), outDir) || '.'}`);
}
