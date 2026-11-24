// Customer visits (task 18.2, reference "pVisits", spec time/customer-visits). Backend: /api/v1/visits
// (task 18.1); CSV import of planned visits goes through Data imports (/imports?type=visits).
export default {
  id: 'visits',
  routes: [
    {
      path: '/visits',
      name: 'visits.home',
      component: () => import('./VisitsView.vue'),
      meta: { screen: 'visits' },
    },
  ],
};
