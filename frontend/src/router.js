import { createRouter, createWebHistory } from 'vue-router';
import ScreenFallback from './shell/ScreenFallback.vue';
import SignInView from './shell/SignInView.vue';
import { loadSession, session } from './shell/session.js';

/**
 * Self-registering modules: every `src/modules/<id>/index.js` default-exports
 * `{ id, routes }`. They are discovered here by glob, so adding a module never
 * edits this file (design.md, decision 10). Navigation comes from GET /api/v1/me.
 *
 * Route meta: `screen` (menu key) or `permissions` guard a route (src/shell/access.js); `public`
 * routes need no session; `bare` routes render without the shell.
 */
const found = import.meta.glob('./modules/*/index.js', { eager: true });

export const modules = Object.entries(found).map(([path, mod]) => {
  const def = mod.default;
  if (!def?.id || !Array.isArray(def.routes)) {
    throw new Error(`${path} must default-export { id, routes }`);
  }
  return def;
});

const ids = modules.map((m) => m.id);
const dup = ids.find((id, i) => ids.indexOf(id) !== i);
if (dup) throw new Error(`Duplicate frontend module id "${dup}"`);

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/sign-in', name: 'sign-in', component: SignInView, meta: { public: true, bare: true } },
    { path: '/', name: 'home', component: ScreenFallback },
    ...modules.flatMap((m) => m.routes.map((r) => ({ ...r, meta: { ...r.meta, module: m.id } }))),
    // screen-map screens no module serves yet, and unknown addresses (src/shell/ScreenFallback.vue)
    { path: '/:pathMatch(.*)*', name: 'fallback', component: ScreenFallback },
  ],
});

// Router guard: signed-out users go to sign-in and come back afterwards; '/' opens the landing page
// of the role (spec platform/auth-session: Action center for Managers and Admin, Overview otherwise).
router.beforeEach(async (to) => {
  await loadSession();

  const signedIn = session.status === 'signed-in';

  if (to.meta.public) {
    return to.name === 'sign-in' && signedIn ? session.me.landing ?? true : true;
  }

  if (!signedIn) {
    return { name: 'sign-in', query: to.fullPath === '/' ? {} : { next: to.fullPath } };
  }

  if (to.name === 'home') {
    return session.me.landing ?? true;
  }

  return true;
});
