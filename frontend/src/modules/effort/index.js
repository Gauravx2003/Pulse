// Effort (task 11.3, reference 7.4). Backend: /api/v1/effort (tasks 11.1, 11.2 and 11.3).
export default {
  id: 'effort',
  routes: [
    {
      path: '/effort',
      name: 'effort.home',
      component: () => import('./EffortView.vue'),
      meta: { screen: 'effort' },
    },
  ],
};
