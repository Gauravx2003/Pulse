/** Objective and key-result status tones (On track, At risk, Off track); the status is the server's. */
export const STATUS_TONES = { 'On track': 'ok', 'At risk': 'accent', 'Off track': 'danger' };

/** "2026-Q3" → "Q3 2026". */
export function quarterLabel(key) {
  const m = /^(\d{4})-Q([1-4])$/.exec(key ?? '');

  return m ? `Q${m[2]} ${m[1]}` : key ?? '';
}

/** The quarter key of a date string "2026-09-28". */
export function quarterOf(date) {
  const [y, mo] = String(date).split('-').map(Number);

  return `${y}-Q${Math.floor((mo - 1) / 3) + 1}`;
}

/** Quarter keys around `key`: `back` before it and `ahead` after it, newest first. */
export function quartersAround(key, back = 3, ahead = 1) {
  const m = /^(\d{4})-Q([1-4])$/.exec(key ?? '');

  if (!m) {
    return [];
  }

  const base = Number(m[1]) * 4 + Number(m[2]) - 1;
  const out = [];

  for (let i = base + ahead; i >= base - back; i -= 1) {
    out.push(`${Math.floor(i / 4)}-Q${(i % 4) + 1}`);
  }

  return out;
}

export const MAX_KEY_RESULTS = 5;
