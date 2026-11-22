import { reactive } from 'vue';

/**
 * Toasts confirm every save (spec platform/app-shell-ui, "Feedback patterns"; reference section 3:
 * bottom center, 2.4 s). One at a time: a new toast replaces the current one.
 */
export const TOAST_MS = 2400;

export const toastState = reactive({ text: '', visible: false, seq: 0 });

let timer = null;

export function showToast(text) {
  if (!text) {
    return;
  }

  clearTimeout(timer);
  toastState.text = String(text);
  toastState.visible = true;
  toastState.seq += 1;
  timer = setTimeout(() => {
    toastState.visible = false;
  }, TOAST_MS);
}
