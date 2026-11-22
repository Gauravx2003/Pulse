<template>
  <div ref="root" class="pl-bell">
    <button
      type="button"
      class="pl-btn pl-btn-sm"
      :aria-expanded="open"
      aria-controls="pl-bell-panel"
      :aria-label="notifications.unread ? `Notifications, ${notifications.unread} unread` : 'Notifications'"
      @click="toggle"
    >
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
           stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
        <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
      </svg>
    </button>
    <span v-if="notifications.unread" class="pl-bell-cnt" aria-hidden="true">{{ count }}</span>
    <div v-if="open" id="pl-bell-panel" class="pl-bell-panel" role="dialog" aria-label="Notifications">
      <div class="pl-bell-head">
        <b>Notifications</b>
        <button
          v-if="notifications.unread"
          type="button"
          class="pl-btn pl-btn-sm"
          @click="readAll"
        >
          Mark all read
        </button>
      </div>
      <ul v-if="items.length" class="pl-bell-list">
        <li v-for="item in items" :key="item.id">
          <button type="button" class="pl-bell-item" :class="{ 'is-unread': !item.read }" @click="openItem(item)">
            <span class="dot" aria-hidden="true"></span>
            <span class="body">
              <span class="text">{{ item.text }}</span>
              <span class="pl-caption">{{ formatDate(item.createdAt) }} · {{ stateOf(item) }}</span>
              <span v-if="!item.read" class="pl-sr-only">(unread)</span>
            </span>
          </button>
        </li>
      </ul>
      <PlEmpty v-else text="No notifications." />
    </div>
  </div>
</template>

<script setup>
// The notification bell (reference 11.6, "Screen detail — Notifications and feedback"): unread count
// on the bell; the panel lists the newest items with their date and state, Mark all read clears the
// count, and clicking an item marks it read and opens its screen, form or tab.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { PlEmpty } from '../ui/index.js';
import { formatDate } from '../ui/format.js';
import { markAllNotificationsRead, markNotificationRead, notifications, refreshNotifications } from './notifications.js';

/** The bell shows at most this many items (prototype bellPanel). */
const SHOWN = 12;

/** The state caption by kind prefix (prototype: "open to review", "customer visit", "training"). */
const STATES = {
  review360: 'open to review',
  training: 'training',
  mentoring: 'mentoring',
  visit: 'customer visit',
  pulse: 'weekly pulse',
  attendance: 'attendance',
  work: 'work item',
};

const router = useRouter();

const open = ref(false);
const root = ref(null);

const items = computed(() => notifications.items.slice(0, SHOWN));
const count = computed(() => (notifications.unread > 99 ? '99+' : String(notifications.unread)));

function stateOf(item) {
  const prefix = String(item.kind ?? '').split('.')[0];

  return item.read && prefix === 'review360' ? 'read' : STATES[prefix] ?? 'update';
}

function toggle() {
  open.value = !open.value;

  if (open.value) {
    refreshNotifications();
  }
}

function close(returnFocus) {
  open.value = false;

  if (returnFocus) {
    root.value?.querySelector('button')?.focus();
  }
}

async function openItem(item) {
  close(false);
  markNotificationRead(item);

  if (item.link) {
    await router.push(item.link);
  }
}

async function readAll() {
  try {
    await markAllNotificationsRead();
  } catch {
    // the API client already showed the error
  }
}

function onDocument(event) {
  if (open.value && root.value && !root.value.contains(event.target)) {
    close(false);
  }
}

function onKey(event) {
  if (open.value && event.key === 'Escape') {
    close(true);
  }
}

onMounted(() => {
  document.addEventListener('mousedown', onDocument);
  document.addEventListener('keydown', onKey);
});

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocument);
  document.removeEventListener('keydown', onKey);
});
</script>
