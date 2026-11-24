// Reviews (task 20.4, reference "pReviews", specs culture/one-on-ones-kudos and culture/review-360). Backend:
// /api/v1/reviews (tasks 20.2–20.3). The Performance tab reads /reviews/performance (task 20.1).
export default {
  id: 'reviews',
  routes: [
    {
      path: '/reviews',
      name: 'reviews.home',
      component: () => import('./ReviewsView.vue'),
      meta: { screen: 'reviews' },
    },
  ],
};
