// Presentation helpers for the Customer visits screens. Status, points and values come from the server.
import { formatDate } from '@/ui/format.js';

export const PURPOSES = ['Deployment / installation', 'Troubleshooting', 'Escalation', 'Training / workshop', 'Demo / POC', 'Health check'];

const STATUS = {
  planned: ['Planned', 'info'],
  report_due: ['Report due', 'accent'],
  feedback_pending: ['Feedback pending', 'warn'],
  awaiting_manager: ['Awaiting manager', 'brand'],
  verified: ['Verified', 'ok'],
  not_counted: ['Not counted', 'danger'],
};

export function statusLabel(s) {
  return (STATUS[s] ?? [s])[0];
}

export function statusTone(s) {
  return (STATUS[s] ?? [s, ''])[1];
}

export function dates(v) {
  return v.from === v.to ? formatDate(v.from) : `${formatDate(v.from)} – ${formatDate(v.to)}`;
}

export function num(v, digits = 1) {
  return typeof v === 'number' ? (Number.isInteger(v) ? String(v) : v.toFixed(digits)) : '—';
}

/** The visits period choices for the server's today ("2026-09-28"): the current quarter or the year to date. */
export function periods(today) {
  const [year, month] = today.split('-').map(Number);
  const q = Math.floor((month - 1) / 3) + 1;

  return [
    { value: `q:Q${q}`, label: `Q${q} ${year}`, from: `${year}-${String((q - 1) * 3 + 1).padStart(2, '0')}-01`, to: quarterEnd(year, q) },
    { value: 'all', label: `Year to date ${year}`, from: `${year}-01-01`, to: `${year}-12-31` },
  ];
}

function quarterEnd(year, q) {
  return new Date(Date.UTC(year, q * 3, 0)).toISOString().slice(0, 10);
}
