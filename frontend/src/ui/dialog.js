import { nextTick, onBeforeUnmount, watch } from 'vue';

/**
 * Keyboard behaviour of modals and drawers (spec platform/app-shell-ui, "Accessibility"): while open,
 * Tab and Shift+Tab stay inside the panel, Esc closes it, and on close focus returns to the element
 * that opened it. Dialogs stack, so only the topmost one reacts to keys.
 */
const FOCUSABLE = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',');

const stack = [];

export function focusables(root) {
  return [...root.querySelectorAll(FOCUSABLE)].filter((el) => !el.closest('[inert]') && el.offsetParent !== null);
}

function onKeydown(event) {
  const top = stack[stack.length - 1];

  if (!top) {
    return;
  }

  if (event.key === 'Escape') {
    event.preventDefault();
    event.stopPropagation();
    top.close();
    return;
  }

  if (event.key !== 'Tab') {
    return;
  }

  const panel = top.panel();

  if (!panel) {
    return;
  }

  const items = focusables(panel);

  if (items.length === 0) {
    event.preventDefault();
    panel.focus();
    return;
  }

  const first = items[0];
  const last = items[items.length - 1];
  const active = document.activeElement;

  if (event.shiftKey && (active === first || !panel.contains(active))) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && (active === last || !panel.contains(active))) {
    event.preventDefault();
    first.focus();
  }
}

/**
 * @param {import('vue').Ref<boolean>} open   whether the dialog is shown
 * @param {import('vue').Ref<HTMLElement>} panel the dialog element (gets focus when nothing inside can)
 * @param {() => void} close                  called on Esc
 * @param {{ initialFocus?: () => HTMLElement | null }} [options]
 */
export function useDialog(open, panel, close, options = {}) {
  const entry = { panel: () => panel.value, close };
  let opener = null;

  function activate() {
    opener = document.activeElement;

    if (stack.length === 0) {
      document.addEventListener('keydown', onKeydown, true);
      document.body.style.overflow = 'hidden';
    }

    stack.push(entry);

    nextTick(() => {
      const el = panel.value;

      if (!el) {
        return;
      }

      const target = options.initialFocus?.() ?? focusables(el)[0] ?? el;

      target.focus();
    });
  }

  function deactivate() {
    const i = stack.indexOf(entry);

    if (i < 0) {
      return;
    }

    stack.splice(i, 1);

    if (stack.length === 0) {
      document.removeEventListener('keydown', onKeydown, true);
      document.body.style.overflow = '';
    }

    if (opener && typeof opener.focus === 'function' && document.contains(opener)) {
      opener.focus();
    }

    opener = null;
  }

  watch(open, (value) => (value ? activate() : deactivate()), { immediate: true });

  onBeforeUnmount(deactivate);
}

let ids = 0;

/** A page-unique id for label and description links. */
export function uid(prefix = 'pl') {
  ids += 1;
  return `${prefix}-${ids}`;
}
