/**
 * Period kinds of the screen-map screens (reference section 3, "Screens"), used by the placeholder a
 * screen shows until its module registers a route. Attendance keeps its own period choice.
 */
export const PERIOD_SCREENS = {
  overview: ['sprint', 'month', 'release'],
  'work-items': ['sprint', 'month', 'release'],
  'pull-requests': ['sprint', 'month'],
  effort: ['sprint', 'month'],
  'bugs-support': ['sprint', 'month'],
  'team-utilization': ['sprint', 'month', 'release'],
};

export const ATTENDANCE_SCREEN = 'attendance';
