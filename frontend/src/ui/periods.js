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

/**
 * The default of the first kind in {@code kinds} that has any period: a screen offering sprints opens on
 * the month while no sprint exists yet (a new database before its first Jira sync), instead of waiting.
 */
export function defaultKeyOf(periods, kinds) {
  for (const kind of kinds) {
    const key = defaultKey(periods, kind);

    if (key) {
      return key;
    }
  }

  return null;
}

/** True when {@code key} names a period of the given kinds that exists in {@code periods}. */
export function isKnown(periods, key, kinds) {
  const kind = kindOf(key);

  if (!periods || !kinds.includes(kind)) {
    return false;
  }

  return (periods[`${kind}s`] ?? []).some((p) => p.key === key);
}

/**
 * A release card's sprints as one line, by name (change jira-sprint-names): the server's `sprintNames`
 * ({key, name}, the calendar's label: the Jira name, else "Sprint N"), each falling back to its key without
 * `s:` when the name is missing, and to the plain `sprints` keys for a card without names. '—' for none.
 */
export function releaseSprints(release) {
  const named = Array.isArray(release?.sprintNames) ? release.sprintNames : null;
  const shown = named
    ? named.map((s) => s.name || String(s.key ?? '').replace(/^s:/, ''))
    : (release?.sprints ?? []).map((k) => String(k).replace(/^s:/, ''));

  return shown.filter(Boolean).join(', ') || '—';
}
