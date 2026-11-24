// Appraisal & increments (task 22.2, reference "pAppraisal", spec appraisal/appraisal). Backend:
// /api/v1/appraisal (task 22.1). Managers propose (appraisal.manage); HR approves and releases (appraisal.approve).
export default {
  id: 'appraisal',
  routes: [
    {
      path: '/appraisal',
      name: 'appraisal.home',
      component: () => import('./AppraisalView.vue'),
      meta: { screen: 'appraisal' },
    },
  ],
};
