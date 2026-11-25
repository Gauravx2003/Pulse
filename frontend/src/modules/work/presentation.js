/**
 * Presentation helpers of the work screens. They format what the backend computed; no threshold or band
 * limit lives here (bands and signals arrive as [tone, label] pairs from CalcService).
 */

/** A calculated value, or null when the backend marked it not applicable. */
export function val(value) {
  return value && typeof value === 'object' && value.notApplicable === true ? null : value ?? null;
}

/** A number for display: '—' when missing, whole numbers without decimals. */
export function num(value, digits = 1) {
  const v = val(value);

  if (v === null || v === undefined || Number.isNaN(Number(v))) {
    return '—';
  }

  const n = Number(v);

  return Number.isInteger(n) ? String(n) : n.toFixed(digits);
}

export function hours(value) {
  const n = num(value);

  return n === '—' ? n : `${n} h`;
}

export function pct(value) {
  const n = num(value, 0);

  return n === '—' ? n : `${n}%`;
}

/** [tone, label] band from the backend, as { tone, label }; tone '' when missing. */
export function band(value) {
  const b = val(value);

  return Array.isArray(b) ? { tone: b[0] ?? '', label: b[1] ?? '' } : { tone: '', label: '' };
}

/** The signal badges of an issue: [{ tone, text }]. */
export function flags(issue) {
  return (val(issue?.flags) ?? []).map(([tone, text]) => ({ tone, text }));
}

/** "3 working days ago", "Today", or '—'. */
export function idleText(days) {
  const d = val(days);

  if (d === null || d === undefined) {
    return '—';
  }

  return d === 0 ? 'Today' : `${d} working day${d === 1 ? '' : 's'} ago`;
}

/** Initials for an avatar. */
export function initials(name) {
  return String(name ?? '')
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0].toUpperCase())
    .join('');
}

/** "Software Engineer II · Platform · L3 · Manager Rahul Nair". */
export function identityCaption(person) {
  if (!person) {
    return '';
  }

  return [person.title, person.team, person.level, person.manager ? `Manager ${person.manager}` : null]
    .filter(Boolean)
    .join(' · ');
}

export const LIVE_FILTERS = [
  { key: 'all', label: 'All' },
  { key: 'open', label: 'Open' },
  { key: 'stale', label: 'Stale / not started' },
  { key: 'overdue', label: 'Overdue' },
  { key: 'review', label: 'In review' },
  { key: 'done', label: 'Done' },
];

export const HISTORY_FILTERS = [
  { key: 'all', label: 'All' },
  { key: 'done', label: 'Done' },
  { key: 'carried', label: 'Carried over' },
  { key: 'bugs', label: 'Bugs' },
  { key: 'open', label: 'Open now' },
];

export const LIVE_SORTS = [
  { key: '-signals', label: 'Signals' },
  { key: 'key', label: 'Key' },
  { key: 'status', label: 'Status' },
  { key: '-sp', label: 'Story points' },
  { key: '-remaining', label: 'Remaining' },
  { key: 'due', label: 'Due date' },
  { key: 'last', label: 'Last activity' },
];

export const HISTORY_SORTS = [
  { key: '-resolved', label: 'Resolved' },
  { key: 'key', label: 'Key' },
  { key: 'status', label: 'Status' },
  { key: '-sp', label: 'Story points' },
];

/** Source filter of the work item lists (task 25.2): the server accepts only these keys. */
export const SOURCES = [
  { key: 'all', label: 'All sources' },
  { key: 'seed', label: 'Seed' },
  { key: 'jira', label: 'Jira' },
];

/** Badge of an issue's source: `{ text, tone }`; a source the list does not know shows its own name. */
export function sourceBadge(source) {
  if (source === 'jira') {
    return { text: 'Jira', tone: 'info' };
  }

  if (!source || source === 'seed') {
    return { text: 'Seed', tone: '' };
  }

  return { text: source, tone: '' };
}

/**
 * The issue's link in its tool, or null. Only http(s) links are used, so a stored value can never run
 * script when clicked.
 */
export function toolUrl(issue) {
  const url = val(issue?.htmlUrl);

  return typeof url === 'string' && /^https?:\/\//i.test(url) ? url : null;
}
