// Presentation helpers for the Training screens. Stages, ratings and bands come from the server
// (TrainingLifecycle, calc/people); nothing here decides them.
import { formatDate } from '@/ui/format.js';

const STAGE_TONES = {
  'Not started': '',
  'In training': 'info',
  Assessment: 'accent',
  'Final review': 'accent',
  'Trainee sign-off': 'warn',
  Completed: 'ok',
  'Not completed': 'danger',
};

export function stageTone(stage) {
  return STAGE_TONES[stage] ?? '';
}

const PERIOD_TONES = { Due: 'accent', Missed: 'danger', Submitted: 'info', Verified: 'ok', 'Needs work': 'warn' };

export function periodTone(status) {
  return PERIOD_TONES[status] ?? '';
}

/** "14 Sep – 6 Dec". */
export function span(from, to) {
  return `${formatDate(from)} – ${formatDate(to)}`;
}

/** A rating number "4.2", or "—". */
export function rating(v) {
  return typeof v === 'number' ? v.toFixed(1) : '—';
}

/** Share of the plan's modules ticked, 0–100. */
export function progress(a) {
  const total = (a.modules ?? []).length;

  return total ? Math.round((Object.keys(a.moduleProgress ?? {}).length / total) * 100) : 0;
}

/** Modules grouped by week, in week order: [{ week, modules }]. */
export function byWeek(modules) {
  const weeks = new Map();

  for (const m of modules ?? []) {
    if (!weeks.has(m.week)) {
      weeks.set(m.week, []);
    }

    weeks.get(m.week).push(m);
  }

  return [...weeks.entries()].sort((x, y) => x[0] - y[0]).map(([week, list]) => ({ week, modules: list }));
}

export function initials(name) {
  return (name ?? '')
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0])
    .join('')
    .toUpperCase();
}
