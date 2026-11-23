/** Period keys of spec platform/period-calendar: s:S43, m:2026-09, r:8.2, q:Q3, all. */
export const KIND_LABELS = { sprint: 'Sprint', month: 'Month', release: 'Release' };

const PREFIX = { s: 'sprint', m: 'month', r: 'release', q: 'quarter' };

export function kindOf(key) {
  if (typeof key !== 'string' || !key) {
    return null;
  }

  if (key === 'all') {
    return 'all';
  }

  return PREFIX[key.split(':')[0]] ?? null;
}

/**
 * The period a kind opens on: the active sprint, the current month or the live release, else the
 * newest one. Lists from GET /periods are newest first. When no sprint is active (today is after the
 * last sprint), the server sends `activeSprint: { notApplicable: true }` instead of a key.
 */
export function defaultKey(periods, kind) {
  if (!periods) {
    return null;
  }

  if (kind === 'sprint' && typeof periods.activeSprint === 'string' && periods.activeSprint) {
    return periods.activeSprint;
  }

  const list = periods[`${kind}s`] ?? [];
  const live = list.filter((p) => p.live);

  return (live.length ? live[live.length - 1] : list[0])?.key ?? null;
}

/** True when {@code key} names a period of the given kinds that exists in {@code periods}. */
export function isKnown(periods, key, kinds) {
  const kind = kindOf(key);

  if (!periods || !kinds.includes(kind)) {
    return false;
  }

  return (periods[`${kind}s`] ?? []).some((p) => p.key === key);
}
