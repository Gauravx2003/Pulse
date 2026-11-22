// Rules & thresholds (task 6.5, reference 11.4). Backend: /api/v1/rules (task 5.2).
export default {
  id: 'rules',
  routes: [{ path: '/rules', name: 'rules.home', component: () => import('./RulesView.vue'), meta: { screen: 'rules' } }],
};
