/**
 * Date and time display (spec platform/period-calendar "Clock and time zone"): dates as "28 Sep"
 * (no leading zero, "Sep" not "Sept"), times as 24-hour "09:05", always in the company time zone
 * Asia/Kolkata whatever the browser's zone. Mirrors the backend's CalendarFormat.
 */
export const COMPANY_ZONE = 'Asia/Kolkata';

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

const PARTS = new Intl.DateTimeFormat('en-GB', {
  timeZone: COMPANY_ZONE,
  year: 'numeric',
  month: 'numeric',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

/** { year, month (1-12), day, hour, minute } in the company zone, or null for a missing or bad value. */
function parts(value) {
  if (value === null || value === undefined || value === '') {
    return null;
  }

  // a plain date ("2026-09-28") is a calendar day, not an instant
  const plain = typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.exec(value);

  if (plain) {
    const [y, m, d] = value.split('-').map(Number);

    return { year: y, month: m, day: d, hour: 0, minute: 0 };
  }

  const date = value instanceof Date ? value : new Date(value);

  if (Number.isNaN(date.getTime())) {
    return null;
  }

  const out = {};

  PARTS.formatToParts(date).forEach((p) => {
    if (p.type !== 'literal') {
      out[p.type] = Number(p.value);
    }
  });

  return out;
}

/** "28 Sep"; "—" when missing. */
export function formatDate(value) {
  const p = parts(value);

  return p ? `${p.day} ${MONTHS[p.month - 1]}` : '—';
}

/** "28 Sep 2026"; "—" when missing. */
export function formatDateWithYear(value) {
  const p = parts(value);

  return p ? `${p.day} ${MONTHS[p.month - 1]} ${p.year}` : '—';
}

/** "28 Sep, 09:05"; "—" when missing. */
export function formatDateTime(value) {
  const p = parts(value);

  if (!p) {
    return '—';
  }

  return `${p.day} ${MONTHS[p.month - 1]}, ${String(p.hour).padStart(2, '0')}:${String(p.minute).padStart(2, '0')}`;
}
