import { reactive } from 'vue';
import { api } from '../api/client.js';
import { onViewReset } from './viewState.js';

/**
 * The bell and the menu badges (spec platform/notifications, task 6.5), read from
 * GET /api/v1/notifications and GET /api/v1/notifications/badges. The shell refreshes them when it
 * mounts, on navigation (at most every 15 s) and every minute; a screen that clears an action
 * (verifies effort, submits a review, ...) calls refreshNotifications() so its badge updates at once.
 */
export const notifications = reactive({
  items: [],
  unread: 0,
  /** { menuKey: count } for the screens in the user's menu */
  badges: {},
  loaded: false,
});

const POLL_MS = 60_000;

const NAV_MIN_MS = 15_000;

let timer = null;

let last = 0;

let inflight = null;

/** Reloads the list, the unread count and the badges; failures keep the previous values. */
export function refreshNotifications() {
  if (inflight) {
    return inflight;
  }

  last = Date.now();

  inflight = Promise.allSettled([
    api.get('/notifications', { quiet: true }),
    api.get('/notifications/badges', { quiet: true }),
  ])
    .then(([list, badges]) => {
      if (list.status === 'fulfilled') {
        notifications.items = list.value.items ?? [];
        notifications.unread = list.value.unread ?? 0;
        notifications.loaded = true;
      }

      if (badges.status === 'fulfilled') {
        notifications.badges = badges.value.badges ?? {};
      }
    })
    .finally(() => {
      inflight = null;
    });

  return inflight;
}

/** Refreshes on navigation, but not more often than every 15 s. */
export function refreshOnNavigation() {
  if (Date.now() - last >= NAV_MIN_MS) {
    refreshNotifications();
  }
}

export function startNotificationPolling() {
  stopNotificationPolling();
  refreshNotifications();
  timer = setInterval(refreshNotifications, POLL_MS);
}

export function stopNotificationPolling() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
}

/** Marks one item read (opening it). Optimistic; the server's count wins when it answers. */
export async function markNotificationRead(item) {
  if (!item.read) {
    item.read = true;
    notifications.unread = Math.max(0, notifications.unread - 1);
  }

  try {
    const res = await api.post(`/notifications/${item.id}/read`, undefined, { quiet: true });

    notifications.unread = res?.unread ?? notifications.unread;
  } catch {
    // the item opens anyway; the next refresh corrects the count
  }
}

export async function markAllNotificationsRead() {
  await api.post('/notifications/read-all');

  notifications.items.forEach((item) => {
    item.read = true;
  });
  notifications.unread = 0;
}

/** The badge count for a menu key, or 0. */
export function badgeFor(key) {
  const n = notifications.badges[key];

  return Number.isFinite(n) && n > 0 ? n : 0;
}

onViewReset(() => {
  notifications.items = [];
  notifications.unread = 0;
  notifications.badges = {};
  notifications.loaded = false;
  last = 0;
});
