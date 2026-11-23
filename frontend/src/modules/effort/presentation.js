/**
 * Extra-mile item kinds (reference 7.4 table): their label and badge tone. Points and caps are the
 * backend's (rule `pts`); nothing here scores.
 */
export const KINDS = {
  midsprint: { label: 'Added mid-sprint', tone: 'accent' },
  incident: { label: 'Incident / hotfix', tone: 'danger' },
  takeover: { label: 'Took over', tone: 'brand' },
  help: { label: "Helped on another's issue", tone: 'ok' },
  untracked: { label: 'Untracked useful work', tone: 'info' },
  reviews: { label: 'Reviews for others', tone: 'ok' },
  support: { label: 'Extra support ticket', tone: 'info' },
  proactivefix: { label: "Fixed someone else's bug", tone: 'ok' },
  visit: { label: 'Customer visit', tone: 'brand' },
};

export function kind(key) {
  return KINDS[key] ?? { label: key, tone: '' };
}

export const STATES = {
  pending: { label: 'Awaiting manager', tone: 'accent' },
  confirmed: { label: 'Confirmed', tone: 'ok' },
  rejected: { label: 'Not counted', tone: '' },
};

export function state(item) {
  if (item.state === 'pending' && !item.isDone) {
    return { label: 'Waiting for Done', tone: 'info' };
  }

  return STATES[item.state] ?? { label: item.state, tone: '' };
}
