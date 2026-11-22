import { computed, reactive, ref } from 'vue';
import { api } from '../api/client.js';
import { defaultKey, isKnown } from '../ui/periods.js';
import { onViewReset, readView, writeView } from './viewState.js';

/**
 * Shared period state (spec platform/period-calendar, "Shared period choice"): the sprint, month or
 * release chosen on one screen carries to every screen that supports it; Attendance keeps its own
 * choice and defaults to the last uploaded month. History rows open their period through openPeriod().
 */
const SHARED = 'period';
const ATTENDANCE = 'period.attendance';

const state = reactive({
  shared: readView(SHARED),
  attendance: readView(ATTENDANCE),
});

onViewReset(() => {
  state.shared = null;
  state.attendance = null;
});

/** GET /periods, loaded once per page view: { today, activeSprint, sprints, months, releases }. */
const periods = ref(null);
let loading = null;

export function loadPeriods() {
  if (!loading) {
    loading = api
      .get('/periods')
      .then((data) => {
        periods.value = data;
        return data;
      })
      .catch((e) => {
        loading = null;
        throw e;
      });
  }

  return loading;
}

function use(slot, kinds, fallback) {
  loadPeriods().catch((e) => console.error('GET /periods failed', e));

  const key = computed({
    get() {
      const chosen = state[slot];

      if (chosen && (!periods.value || isKnown(periods.value, chosen, kinds))) {
        return chosen;
      }

      return fallback?.() ?? defaultKey(periods.value, kinds[0]);
    },
    set(value) {
      setSlot(slot, value);
    },
  });

  return { key, periods, kinds };
}

function setSlot(slot, value) {
  state[slot] = typeof value === 'string' && value ? value : null;
  writeView(slot === 'shared' ? SHARED : ATTENDANCE, state[slot]);
}

/**
 * The shared period for a screen offering {@code kinds}. A carried choice of a kind the screen does
 * not offer falls back to the screen's default (the active sprint, or the current month).
 *
 * @returns {{ key: import('vue').WritableComputedRef<string|null>, periods: import('vue').Ref, kinds: string[] }}
 */
export function useSharedPeriod(kinds = ['sprint', 'month', 'release']) {
  return use('shared', kinds);
}

/**
 * Attendance's own period choice; {@code lastUploadedMonth} returns its default (e.g. 'm:2026-08').
 */
export function useAttendancePeriod(kinds = ['month', 'sprint'], lastUploadedMonth = null) {
  return use('attendance', kinds, lastUploadedMonth);
}

/** Sets the shared period, e.g. from a history row, before opening the screen that shows it. */
export function setSharedPeriod(key) {
  setSlot('shared', key);
}

/** Opens {@code location} on {@code key}: history rows open their period. */
export function openPeriod(router, location, key) {
  setSharedPeriod(key);
  return router.push(location);
}
