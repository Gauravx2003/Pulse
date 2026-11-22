import { computed, reactive } from 'vue';
import { api, setCsrfToken } from '../api/client.js';
import { resetViewState } from './viewState.js';

/**
 * The signed-in user from GET /me (spec platform/api-conventions, "Session and menu bootstrap"): user,
 * role, permissions, menu, CSRF token and landing page. The UI derives navigation only from this.
 */
export const session = reactive({
  /** 'unknown' until the first GET /me, then 'signed-in' or 'signed-out' */
  status: 'unknown',
  me: null,
  /** Why the user was sent to sign in, shown on the sign-in screen. */
  notice: '',
});

let loading = null;

function apply(me) {
  session.me = me;
  session.status = 'signed-in';
  setCsrfToken(me.csrfToken);
}

/** Resolves once the session is known; never rejects for a signed-out user. */
export function loadSession() {
  if (session.status !== 'unknown') {
    return Promise.resolve(session);
  }

  if (!loading) {
    loading = api
      .get('/me', { quiet: true })
      .then(apply)
      .catch((e) => {
        if (e.status !== 401) {
          console.error('GET /me failed', e);
        }

        clear();
      })
      .finally(() => {
        loading = null;
      })
      .then(() => session);
  }

  return loading;
}

export async function signIn(email, password) {
  const me = await api.post('/auth/login', { email, password }, { quiet: true });

  resetViewState();
  apply(me);
  session.notice = '';

  return me;
}

export async function signOut() {
  try {
    await api.post('/auth/logout', undefined, { quiet: true });
  } finally {
    resetViewState();
    clear();
  }
}

/** Forgets the session locally, e.g. after a 401. */
export function clear() {
  session.me = null;
  session.status = 'signed-out';
  setCsrfToken(null);
}

export function can(...permissions) {
  const held = session.me?.permissions ?? [];

  return permissions.some((p) => held.includes(p));
}

/** Menu items of GET /me flattened: [{ key, label, path, group }]. */
export const menuItems = computed(() =>
  (session.me?.menu ?? []).flatMap((section) => section.items.map((item) => ({ ...item, group: section.group }))),
);

const STAFF_GROUPS = new Set(['Manager', 'Administration']);

/** The menu item a path belongs to (exact path or a sub-path of it), or null. */
export function menuItemFor(path) {
  return (
    menuItems.value.find((item) => path === item.path || path.startsWith(`${item.path}/`)) ?? null
  );
}

/** Person pages are the screens of the person group ("My work", "People" or "Feedback"). */
export function isPersonScreen(item) {
  return Boolean(item) && !STAFF_GROUPS.has(item.group);
}
