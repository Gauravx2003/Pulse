/**
 * Demo data copied from each reference/3-design-system/components/<Name>/preview.html so the /kit
 * page can be compared with the previews. Display only; real screens get these values from the API.
 */

export const badges = [
  { text: 'In Progress', tone: 'info' },
  { text: 'In Review', tone: 'brand' },
  { text: 'Done', tone: 'ok' },
  { text: 'To Do', tone: '' },
  { text: 'Stale', tone: 'accent' },
  { text: 'Overdue', tone: 'danger' },
  { text: 'Over', tone: 'warn' },
  { text: 'Healthy', tone: 'ok' },
  { text: 'Under', tone: 'info' },
];

export const stats = [
  { eyebrow: 'Tasks assigned', value: 14, foot: 'Sprint 42 · 38 story points' },
  { eyebrow: 'Not updated 2+ days', value: 4, unit: '/ 11 open', badge: 'Stale', badgeTone: 'accent', foot: 'oldest 6 working days' },
  { eyebrow: 'Work assigned', value: 11.5, unit: 'd', foot: 'vs 9.0 d capacity left' },
  { eyebrow: 'Utilization', value: 128, unit: '%', badge: 'Critical', badgeTone: 'danger', foot: 'over by 2.5 d' },
];

export const meters = [
  { label: 'Priya Shah', value: 128, band: 'danger' },
  { label: 'Kiran Rao', value: 95, band: 'accent' },
  { label: 'Arjun Mehta', value: 77, band: 'ok' },
  { label: 'Neha Joshi', value: 46, band: 'info' },
];

const B = { u: 'info', h: 'ok', c: 'accent', o: 'warn', x: 'danger' };

function cells(spec) {
  return spec.map((c) => (c === 'L' ? { label: 'Leave' } : { value: c[0], band: B[c[1]] }));
}

export const heatmap = {
  title: 'Team utilization · Sprint 42',
  caption: 'Assigned remaining effort ÷ capacity, per working day',
  days: ['Mon 16', 'Tue 17', 'Wed 18', 'Thu 19', 'Fri 20', 'Mon 23', 'Tue 24', 'Wed 25'],
  rows: [
    { id: 'ps', name: 'Priya Shah', initials: 'PS', avg: '123%', utilization: 123, cells: cells([[95, 'c'], [110, 'o'], [130, 'x'], [135, 'x'], [128, 'x'], [140, 'x'], [120, 'o'], [128, 'x']]) },
    { id: 'am', name: 'Arjun Mehta', initials: 'AM', avg: '76%', utilization: 76, cells: cells([[70, 'h'], [75, 'h'], [80, 'h'], [78, 'h'], [82, 'h'], [76, 'h'], [74, 'h'], [77, 'h']]) },
    { id: 'nj', name: 'Neha Joshi', initials: 'NJ', avg: '46%', utilization: 46, cells: cells([[40, 'u'], [45, 'u'], [50, 'u'], [38, 'u'], [42, 'u'], [55, 'u'], [48, 'u'], [46, 'u']]) },
    { id: 'kr', name: 'Kiran Rao', initials: 'KR', avg: '94%', utilization: 94, cells: cells([[88, 'c'], [92, 'c'], [96, 'c'], [98, 'c'], [94, 'c'], [90, 'c'], [99, 'c'], [95, 'c']]) },
    { id: 'dp', name: 'Dev Patel', initials: 'DP', avg: '69%', utilization: 69, cells: cells([[60, 'h'], 'L', 'L', [65, 'h'], [72, 'h'], [80, 'h'], [70, 'h'], [68, 'h']]) },
    { id: 'sk', name: 'Sara Khan', initials: 'SK', avg: '108%', utilization: 108, cells: cells([[105, 'o'], [102, 'o'], [110, 'o'], [115, 'o'], [108, 'o'], [112, 'o'], [104, 'o'], [109, 'o']]) },
  ],
  legend: [
    { band: 'info', label: 'Under <60%' },
    { band: 'ok', label: 'Healthy 60–85%' },
    { band: 'accent', label: 'At capacity 85–100%' },
    { band: 'warn', label: 'Over 100–120%' },
    { band: 'danger', label: 'Critical >120%' },
    { label: 'Leave' },
  ],
};

const STALE = { tone: 'accent', text: 'Stale' };

export const sprint = {
  title: 'Sprint 42 · Platform',
  caption: '16 Sep – 29 Sep · 6 of 14 issues shown · synced from Jira 4 min ago',
  rows: [
    { key: 'PLAT-2231', summary: 'Rate-limit ingestion API per tenant', status: 'In Progress', storyPoints: 5, remainingHours: 16, lastUpdated: '3 working days ago', signal: STALE },
    { key: 'PLAT-2240', summary: 'Alert rule editor: threshold preview', status: 'In Progress', storyPoints: 3, remainingHours: 10, lastUpdated: 'Today, 10:42' },
    { key: 'PLAT-2198', summary: 'Migrate metric store to v2 schema', status: 'To Do', storyPoints: 8, remainingHours: 24, lastUpdated: '6 working days ago', signal: { tone: 'danger', text: 'Overdue' } },
    { key: 'PLAT-2252', summary: 'Fix flaky dashboard export test', status: 'In Review', storyPoints: 2, remainingHours: 4, lastUpdated: 'Yesterday, 17:05' },
    { key: 'PLAT-2217', summary: 'SNMP trap parser — v3 auth', status: 'In Progress', storyPoints: 5, remainingHours: 14, lastUpdated: '4 working days ago', signal: STALE },
    { key: 'PLAT-2260', summary: 'Docs: tenant quota settings', status: 'Done', storyPoints: 1, remainingHours: 0, lastUpdated: 'Today, 09:15' },
  ],
};

export const culture = {
  title: 'DO — 11 culture values · Q3 2026',
  caption: '1–5 · manager + 3 peers + self',
  seriesLabel: 'Priya (Q3)',
  comparisonLabel: 'Team average',
  scores: [4.2, 4.8, 3.6, 4.4, 3.1, 3.9, 3.4, 4.6, 4.0, 3.8, 2.0],
  comparison: [3.7, 4.5, 3.5, 3.8, 3.3, 3.6, 3.6, 3.9, 3.7, 3.5, 3.9],
  weights: [15, 10, 10, 10, 5, 5, 10, 10, 5, 10, 10],
};

export const integrations = [
  { mark: 'JI', name: 'Jira Software', description: 'Sprints, issues, worklogs, status history', statusText: 'Connected · synced 4 min ago', statusTone: 'ok', actionLabel: 'Settings' },
  { mark: 'GH', name: 'GitHub', description: 'PRs, reviews, commits linked to issue keys', statusText: 'Connected · synced 12 min ago', statusTone: 'ok', actionLabel: 'Settings' },
  { mark: 'CF', name: 'Confluence', description: 'Design docs & RFC authorship', statusText: 'Connected', statusTone: 'ok', actionLabel: 'Settings' },
  { mark: 'SL', name: 'Slack', description: 'Kudos tagged by culture value, stale-task nudges', statusText: 'Token expires in 3 days', statusTone: 'accent', actionLabel: 'Fix' },
  { mark: 'GC', name: 'Google Calendar', description: 'Leave & meetings → real capacity', statusText: 'Connected', statusTone: 'ok', actionLabel: 'Settings' },
  { mark: 'AZ', name: 'Azure DevOps', description: 'Boards & pipelines (alternative to Jira)', statusText: 'Not connected', statusTone: '', actionLabel: 'Connect' },
  { mark: 'HR', name: 'HRMS / Keka', description: 'Holidays, leave, reporting lines', statusText: 'Sync failed · 401', statusTone: 'danger', actionLabel: 'Fix' },
  { mark: 'GL', name: 'GitLab', description: 'Merge requests & CI', statusText: 'Not connected', statusTone: '', actionLabel: 'Connect' },
];

export const roadmap = {
  title: 'Road map · Priya Shah',
  track: 'Senior Engineer track',
  items: [
    { state: 'done', title: 'Joined Platform team · Engineer II', when: 'Jan 2025', note: 'Done' },
    { state: 'done', title: 'Owned alerting service end-to-end', when: 'Q2 2025', note: 'Done' },
    { state: 'now', title: 'Senior Engineer track — lead a cross-team epic', when: 'Q3–Q4 2026', progress: 62, note: '62% · on track' },
    { state: 'next', title: 'Mentor 2 engineers · run design reviews', when: 'Q1 2027', progress: 15, note: 'Planned' },
    { state: 'next', title: 'Promotion review: Senior Engineer', when: 'Apr 2027', note: 'Target' },
  ],
};

export const teamKpis = [
  { eyebrow: 'Team utilization', value: 86, unit: '%', badge: 'At capacity', badgeTone: 'accent', foot: 'vs 100% capacity' },
  { eyebrow: 'Over capacity', value: 2, unit: '/ 6', badge: 'Over', badgeTone: 'warn', foot: 'Priya 123%, Sara 108%' },
  { eyebrow: 'Not updated 2+ days', value: 4, unit: '/ 11 open', badge: 'Stale', badgeTone: 'accent', foot: 'Priya 3, Kiran 1' },
  { eyebrow: 'Spare capacity', value: 1, unit: '/ 6', badge: 'Under', badgeTone: 'info', foot: 'Neha 46%' },
];

export const employee = {
  person: { name: 'Priya Shah', initials: 'PS', role: 'Engineer II · Platform', manager: 'Rahul Verma' },
  periods: ['Sprint', 'Month', 'Release'],
  stats: [
    ...stats,
    { eyebrow: 'Performance index', value: 3.9, unit: '/ 5', foot: 'vs 3.6 team average' },
  ],
};
