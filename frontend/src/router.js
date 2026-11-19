import { createRouter, createWebHistory } from 'vue-router';
import HomeView from './views/HomeView.vue';

/**
 * Self-registering modules: every `src/modules/<id>/index.js` default-exports
 * `{ id, routes }`. They are discovered here by glob, so adding a module never
 * edits this file (design.md, decision 10). Navigation comes from GET /api/v1/me.
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
    { path: '/', name: 'home', component: HomeView },
    ...modules.flatMap((m) => m.routes.map((r) => ({ ...r, meta: { ...r.meta, module: m.id } }))),
  ],
});
