// Overview and Work items (task 8.4, reference 7.1 and 7.2). Backend: /api/v1/work (tasks 8.2 and 8.3).
export default {
  id: 'work',
  routes: [
    {
      path: '/overview',
      name: 'work.overview',
      component: () => import('./OverviewView.vue'),
      meta: { screen: 'overview' },
    },
    {
      path: '/work-items',
      name: 'work.items',
      component: () => import('./WorkItemsView.vue'),
      meta: { screen: 'work-items' },
    },
  ],
};
