// Team utilization (task 9.2, reference 6.3). Backend: /api/v1/utilization/team (task 9.1).
export default {
  id: 'utilization',
  routes: [
    {
      path: '/team-utilization',
      name: 'utilization.team',
      component: () => import('./TeamUtilizationView.vue'),
      meta: { screen: 'team-utilization' },
    },
  ],
};
