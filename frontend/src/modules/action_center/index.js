// Action center (task 13.4, reference 6.1). Backend: /api/v1/action_center (tasks 13.1 and 13.2).
export default {
  id: 'action_center',
  routes: [
    {
      path: '/actions',
      name: 'actions.home',
      component: () => import('./ActionCenterView.vue'),
      meta: { screen: 'actions' },
    },
  ],
};
