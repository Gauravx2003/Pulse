import { can, menuItems } from './session.js';

/**
 * Route access (task 6.4). A module route declares what it needs in its meta:
 * - `screen: '<menu key>'`: allowed when GET /me put that screen in the user's menu;
 * - `permissions: ['a', 'b']`: allowed when the user holds any of them.
 * A route with neither is open to every signed-in user. The server checks every call regardless
 * (reference section 13: "the UI hiding a button is never the only check").
 */
export function allowed(route) {
  const meta = route.meta ?? {};

  if (meta.public) {
    return true;
  }

  if (meta.screen && !menuItems.value.some((item) => item.key === meta.screen)) {
    return false;
  }

  if (Array.isArray(meta.permissions) && meta.permissions.length && !can(...meta.permissions)) {
    return false;
  }

  return true;
}
