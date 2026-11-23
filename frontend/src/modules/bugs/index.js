// Bugs & support (task 12.2, reference 7.5). Backend: /api/v1/bugs (task 12.1).
export default {
  id: 'bugs',
  routes: [
    {
      path: '/bugs-support',
      name: 'bugs.home',
      component: () => import('./BugsView.vue'),
      meta: { screen: 'bugs-support' },
    },
  ],
};
