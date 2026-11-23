// Users and Roles & permissions (task 6.5, reference 11.1 and 11.2). Backend: /api/v1/users (task 5.1).
export default {
  id: 'users',
  routes: [
    { path: '/users', name: 'users.home', component: () => import('./UsersView.vue'), meta: { screen: 'users' } },
    { path: '/roles', name: 'roles.home', component: () => import('./RolesView.vue'), meta: { screen: 'roles' } },
  ],
};
