/**
 * Presentation of attendance codes and bands. Every number and band comes from the server
 * (AttendanceService / calc Attendance); this file only maps words and codes to classes.
 */

/** Day codes of GET /attendance/team grid cells and person calendar statuses. */
export const CELLS = {
  O: { cls: 'att-o', code: 'O', label: 'Office' },
  W: { cls: 'att-w', code: 'W', label: 'WFH' },
  D: { cls: 'att-d', code: 'D', label: 'On duty / customer visit' },
  L: { cls: 'att-l', code: 'L', label: 'Planned leave' },
  U: { cls: 'att-u', code: 'L', label: 'Unplanned / absent' },
  A: { cls: 'att-u', code: 'A', label: 'Absent without leave' },
  S: { cls: 'att-s', code: 'S', label: 'Sick or emergency (excused)' },
  HD: { cls: 'att-hd', code: '½', label: 'Half day' },
  H: { cls: 'att-off', code: 'H', label: 'Holiday' },
  x: { cls: 'att-x', code: '?', label: 'Not uploaded' },
  '': { cls: 'att-x', code: '?', label: 'No record' },
};

export const LEGEND = ['O', 'W', 'D', 'L', 'U', 'S', 'HD', ''].map((k) => ({
  key: k || 'none',
  ...CELLS[k],
}));

/** Leave class (stats.leaves[].class) to the calendar cell and the badge. */
export const LEAVE_CLASS = {
  planned: { cell: 'L', label: 'Planned', tone: 'ok' },
  shortNotice: { cell: 'U', label: 'Short notice', tone: 'accent' },
  excused: { cell: 'S', label: 'Excused', tone: '' },
  absent: { cell: 'A', label: 'Absent', tone: 'danger' },
};

/** Attendance band (Good / Watch / Talk / No data) to a badge tone. */
export const BAND_TONES = { Good: 'ok', Watch: 'accent', Talk: 'danger' };

export function bandTone(band) {
  return BAND_TONES[band] ?? '';
}

/** 1, 1.5 — no trailing ".0"; "—" for a missing value. */
export function fmtN(n) {
  if (n === null || n === undefined || Number.isNaN(Number(n))) {
    return '—';
  }

  return String(Math.round(Number(n) * 10) / 10);
}

export function pct(n) {
  return n === null || n === undefined ? '—' : `${n}%`;
}

/** Day of the week of an ISO date, 0 = Sunday, independent of the browser's zone. */
export function weekday(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d)).getUTCDay();
}

export function addDays(iso, n) {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + n)).toISOString().slice(0, 10);
}

/** ISO dates from {@code from} to {@code to}, both included. */
export function daysBetween(from, to) {
  const out = [];

  for (let d = from; d <= to; d = addDays(d, 1)) {
    out.push(d);
  }

  return out;
}

export const WEEKDAYS = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** '2026-09' -> 'Sep 2026' */
export function monthLabel(month) {
  const [y, m] = String(month).split('-').map(Number);
  return MONTHS[m - 1] ? `${MONTHS[m - 1]} ${y}` : String(month);
}
