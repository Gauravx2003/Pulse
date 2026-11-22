import { onApiError } from '../api/client.js';
import { showToast } from '../ui/toast.js';
import { clear, session } from './session.js';

/**
 * The shell's answers to 401, 403, 409 and 429 (spec platform/app-shell-ui, "Feedback patterns"):
 * a 401 sends the user to sign in and back to this page afterwards; the others show a clear toast.
 */
export function installApiHooks(router) {
  onApiError(401, () => {
    clear();
    session.notice = 'Your session has ended. Sign in again.';

    const current = router.currentRoute.value;

    if (current.name !== 'sign-in') {
      router.push({ name: 'sign-in', query: { next: current.fullPath } });
    }
  });

  onApiError(403, (err) => {
    showToast(err.code === 'csrf_invalid' ? err.message : "You don't have access to do that.");
  });

  onApiError(409, (err) => {
    showToast(err.message || 'Someone else changed this record. Reload to see the latest version.');
  });

  onApiError(429, (err) => {
    const wait = err.retryAfter ? ` Try again in ${err.retryAfter} s.` : ' Try again shortly.';

    showToast(`Too many requests.${wait}`);
  });
}
