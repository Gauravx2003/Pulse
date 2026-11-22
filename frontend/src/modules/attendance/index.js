// Attendance & leave (task 17.4, reference "Attendance & leave", spec time/attendance). Backend:
// /api/v1/attendance (tasks 17.1–17.3); uploads go through Data imports (/imports?type=attendance).
export default {
  id: 'attendance',
  routes: [
    {
      path: '/attendance',
      name: 'attendance.home',
      component: () => import('./AttendanceView.vue'),
      meta: { screen: 'attendance' },
    },
  ],
};
