// Presentation helpers for the Appraisal screens. Scores, bands and recommendations come from the server
// (core.appraisal); nothing here decides a band or a percentage.

/** "₹18.4 L" for a CTC in lakh; "No CTC" when missing. */
export function inr(lakh) {
  if (lakh === null || lakh === undefined) {
    return 'No CTC';
  }

  return `₹${Number(lakh).toFixed(2).replace(/\.00$/, '')} L`;
}

/** A score part "3.85", or "—" with no data. */
export function score(v) {
  return v === null || v === undefined ? '—' : Number(v).toFixed(2);
}

/** "12.5%" without a trailing ".0". */
export function pct(v) {
  if (v === null || v === undefined) {
    return '—';
  }

  const n = Number(v);

  return `${Number.isInteger(n) ? n : n.toFixed(1)}%`;
}

const STATUS_TONES = { Draft: '', Submitted: 'info', 'Sent back': 'warn', Approved: 'ok', Released: 'brand' };

export function statusTone(status) {
  return STATUS_TONES[status] ?? '';
}

/** The value of one score part ({ key: 'perf' | 'r360' | 'mgr' | 'disc' }) of a row, or null. */
export function part(row, key) {
  const p = (row.parts ?? []).find((x) => x.key === key);

  return p ? p.value : null;
}

/** Whether the proposal can still be changed by its manager. */
export function editable(row) {
  const status = row.proposal.status;

  return status === 'Draft' || status === 'Sent back';
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
