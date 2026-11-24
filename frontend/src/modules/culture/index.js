// Culture values (task 19.2, reference "pCulture", spec culture/culture-values). Backend: /api/v1/culture
// (task 19.1); kudos go through /api/v1/reviews/kudos (task 20.2).
export default {
  id: 'culture',
  routes: [
    {
      path: '/culture',
      name: 'culture.home',
      component: () => import('./CultureView.vue'),
      meta: { screen: 'culture' },
    },
  ],
};
