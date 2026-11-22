/**
 * Presentation of the Users screens: badge tones, the built-in role descriptions shown in the KPI row,
 * initials and the department choices of the prototype (reference 11.1). No rule lives here.
 */
export const ROLE_TONES = { Admin: 'danger', Manager: 'brand' };

export const STATUS_TONES = { Active: 'ok', Invited: 'accent' };

/** The built-in roles in KPI order with the prototype's descriptions. */
export const BUILT_IN_ROLES = [
  { name: 'Admin', key: 'admins', description: 'HR / system admin — everything' },
  { name: 'Manager', key: 'managers', description: 'People manager — own team' },
  { name: 'Employee', key: 'employees', description: 'Individual contributor — own data only' },
];

export const DEPARTMENTS = [
  'Engineering · Platform',
  'Product',
  'QA',
  'Customer Support',
  'DevOps',
  'Sales Engineering',
  'People & HR',
];

export function initials(name) {
  return String(name ?? '')
    .split(/\s+/)
    .filter(Boolean)
    .map((w) => w[0])
    .join('')
    .slice(0, 2)
    .toUpperCase();
}
