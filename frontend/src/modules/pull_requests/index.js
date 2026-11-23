// Pull requests (task 10.3, reference 7.3). Backend: /api/v1/pull_requests (task 10.2).
export default {
  id: 'pull_requests',
  routes: [
    {
      path: '/pull-requests',
      name: 'pull-requests.home',
      component: () => import('./PullRequestsView.vue'),
      meta: { screen: 'pull-requests' },
    },
  ],
};
