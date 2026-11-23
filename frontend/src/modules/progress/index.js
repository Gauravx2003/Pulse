// Progress and the weekly pulse (task 13.4, reference 6.2). Backend: /api/v1/progress (task 13.3).
export default {
  id: 'progress',
  routes: [
    {
      path: '/progress',
      name: 'progress.home',
      component: () => import('./ProgressView.vue'),
      meta: { screen: 'progress' },
    },
  ],
};
