/** Review comment classes (reference 7.3) and their badge tones; the class itself is the server's. */
export const COMMENT_CLASSES = [
  { key: 'Standard', tone: 'ok', color: 'var(--ok)' },
  { key: 'No label', tone: 'accent', color: 'var(--accent)' },
  { key: 'Too short', tone: 'accent', color: 'var(--warn)' },
  { key: 'Too short · no label', tone: 'danger', color: 'var(--danger)' },
];

export function classTone(name) {
  return COMMENT_CLASSES.find((c) => c.key === name)?.tone ?? '';
}

export const STATE_LABELS = { pass: 'Pass', fail: 'Fail', pending: 'Pending' };

export const STATE_TONES = { pass: 'ok', fail: 'danger', pending: '' };

/** Approvals of a PR detail: reviewers whose vote starts with Approved. */
export function approvals(detail) {
  return (detail?.reviews ?? []).filter((r) => String(r.vote ?? '').startsWith('Approved')).length;
}

/** The conventional-comment labels of the comment standard (reference 7.3, Part 3). */
export const LABELS = [
  ['praise', 'Something done well'],
  ['suggestion', 'A change worth considering, with the reason'],
  ['issue', 'Something that must change, with the impact'],
  ['question', 'Something unclear and why it matters'],
  ['nitpick', 'A small change that does not block'],
  ['todo', 'A small required change'],
  ['thought', 'An idea, not a request'],
];
