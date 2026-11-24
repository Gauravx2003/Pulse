// Road map (task 15.3, reference 9.2). Backend: /api/v1/roadmap (task 15.2).
export default {
  id: 'roadmap',
  routes: [
    {
      path: '/roadmap',
      name: 'roadmap.home',
      component: () => import('./RoadmapView.vue'),
      meta: { screen: 'roadmap' },
    },
  ],
};
