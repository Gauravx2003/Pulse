// Presentation helpers for the Reviews screens. Group states, averages and anonymity come from the server.

/** The 10 rated values of the 360° form, in the server's order (Discipline is not rated). */
export const RATED_VALUES = [
  'Outcome Ownership',
  'Moral & Ethics',
  'Decision Making',
  'Problem Solving',
  'Risk Appetite',
  'Adaptability',
  'Agility in Execution',
  'Collaboration',
  'Resilience',
  'Resourcefulness',
];

/** The 11 culture values a kudos can be for. */
export const CULTURE_VALUES = [...RATED_VALUES, 'Discipline'];

const GROUPS = { self: 'Self', manager: 'Manager', internal: 'Peers', cross: 'Cross-department' };

export function groupLabel(group) {
  return GROUPS[group] ?? group;
}

const STATUS = { not_started: ['Not started', ''], in_progress: ['In progress', 'info'], submitted: ['Submitted', 'ok'], declined: ['Declined', 'warn'] };

export function statusLabel(status) {
  return (STATUS[status] ?? [status])[0];
}

export function statusTone(status) {
  return (STATUS[status] ?? [status, ''])[1];
}

const CYCLE = { running: 'info', closed: '', published: 'ok' };

export function cycleTone(status) {
  return CYCLE[status] ?? '';
}

export function score(v) {
  return typeof v === 'number' ? v.toFixed(1) : '—';
}
