// UI kit gallery (task 6.2): /kit shows every design-system component; /kit/<Name> shows one
// without the app shell, at the same 16px inset as its reference preview.html, for visual comparison.
// /kit/primitives shows the shared UI primitives of task 6.3 (keyboard and axe tests run on it).
export default {
  id: 'kit',
  routes: [
    { path: '/kit', name: 'kit.home', component: () => import('./KitView.vue') },
    { path: '/kit/primitives', name: 'kit.primitives', component: () => import('./PrimitivesView.vue') },
    {
      path: '/kit/:component',
      name: 'kit.component',
      component: () => import('./KitView.vue'),
      props: true,
      meta: { bare: true },
    },
  ],
};
