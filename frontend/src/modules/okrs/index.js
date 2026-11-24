// OKRs (task 14.4, reference 9.1). Backend: /api/v1/okrs (tasks 14.2 and 14.3); import and export
// through Data imports (/imports, type okrs).
export default {
  id: 'okrs',
  routes: [
    {
      path: '/okrs',
      name: 'okrs.home',
      component: () => import('./OkrsView.vue'),
      meta: { screen: 'okrs' },
    },
  ],
};
