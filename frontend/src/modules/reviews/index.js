// Reviews (task 20.4, reference "pReviews", specs culture/one-on-ones-kudos and culture/review-360). Backend:
// /api/v1/reviews (tasks 20.2–20.3). The Performance tab waits for the performance index (Track 1, 4.3).
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
