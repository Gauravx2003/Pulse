// Reference module proving self-registration (task 1.2). Copy this shape; delete once a real module lands.
export default {
  id: 'sample',
  routes: [
    { path: '/sample', name: 'sample.home', component: () => import('./SampleView.vue') },
  ],
};
