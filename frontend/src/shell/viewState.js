/**
 * Per-session view state (reference section 2: "Sign-in resets every per-session view state (tabs,
 * selected person, period, open imports)"). Stores register a reset callback; sign-in and sign-out
 * call resetViewState(). Values persist in sessionStorage under the 'pulse.view.' prefix, guarded
 * because storage can be unavailable.
 */
const PREFIX = 'pulse.view.';

const resets = new Set();

export function onViewReset(fn) {
  resets.add(fn);
}

export function resetViewState() {
  try {
    Object.keys(sessionStorage)
      .filter((k) => k.startsWith(PREFIX))
      .forEach((k) => sessionStorage.removeItem(k));
  } catch {
    // storage unavailable: the in-memory reset below still applies
  }

  resets.forEach((fn) => fn());
}

export function readView(name) {
  try {
    return sessionStorage.getItem(PREFIX + name);
  } catch {
    return null;
  }
}

export function writeView(name, value) {
  try {
    if (value === null || value === undefined) {
      sessionStorage.removeItem(PREFIX + name);
    } else {
      sessionStorage.setItem(PREFIX + name, value);
    }
  } catch {
    // not persisted; the in-memory value still applies for this page view
  }
}
