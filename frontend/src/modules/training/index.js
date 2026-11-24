// Training & mentoring (task 21.3, reference "pTraining", spec growth/training-mentoring). Backend:
// /api/v1/training (tasks 21.1–21.2).
export default {
  id: 'training',
  routes: [
    {
      path: '/training',
      name: 'training.home',
      component: () => import('./TrainingView.vue'),
      meta: { screen: 'training' },
    },
  ],
};
