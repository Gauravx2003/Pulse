// Integrations (task 6.5, reference 11.3 and section 5). Backend: /api/v1/integrations (task 5.4).
export default {
  id: 'integrations',
  routes: [
    {
      path: '/integrations',
      name: 'integrations.home',
      component: () => import('./IntegrationsView.vue'),
      meta: { screen: 'integrations' },
    },
  ],
};
