import { ref } from 'vue';

/**
 * Light / dark theme, remembered per browser (spec platform/app-shell-ui, "Design tokens and theming").
 *
 * tokens.css follows `prefers-color-scheme` until `<html data-theme>` is set, so a browser with no
 * stored choice keeps the system theme. Toggling writes the choice to localStorage and sets the
 * attribute; storage can be unavailable (private mode, blocked site data), so every access is guarded.
 */
const STORAGE_KEY = 'pulse.theme';

function readStored() {
  try {
    const value = localStorage.getItem(STORAGE_KEY);
    return value === 'light' || value === 'dark' ? value : null;
  } catch {
    return null;
  }
}

function systemTheme() {
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

const stored = readStored();

/** The theme currently shown: the stored choice, else the system theme. */
export const theme = ref(stored ?? systemTheme());

/** Applies the stored choice to `<html>`; call once before mounting the app. */
export function initTheme() {
  if (stored) {
    document.documentElement.dataset.theme = stored;
  }
}

export function setTheme(value) {
  theme.value = value;
  document.documentElement.dataset.theme = value;
  try {
    localStorage.setItem(STORAGE_KEY, value);
  } catch {
    // Not persisted; the theme still applies for this page view.
  }
}

export function toggleTheme() {
  setTheme(theme.value === 'dark' ? 'light' : 'dark');
}
