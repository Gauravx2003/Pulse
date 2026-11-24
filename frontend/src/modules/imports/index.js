// Data imports (task 16.4, reference "pImports", spec admin/data-imports-exports). Backend: /api/v1/imports
// (tasks 16.1–16.3). Other screens link here with ?type=<import type> to open its upload.
export default {
  id: 'imports',
  routes: [
    {
      path: '/imports',
      name: 'imports.home',
      component: () => import('./ImportsView.vue'),
      meta: { screen: 'imports' },
    },
  ],
};
