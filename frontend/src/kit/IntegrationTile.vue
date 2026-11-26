<template>
  <div class="pl-int">
    <div class="pl-int-mark">{{ mark }}</div>
    <div>
      <div class="pl-int-name">
        {{ name }}
        <Badge v-if="badge" :text="badge" :tone="badgeTone" />
      </div>
      <div class="pl-caption">{{ description }}</div>
      <div v-if="detail" class="pl-caption">{{ detail }}</div>
      <div class="status"><Badge :text="statusText" :tone="statusTone" /></div>
      <div v-if="$slots.extra" class="extra"><slot name="extra" /></div>
    </div>
    <button
      type="button"
      class="pl-btn pl-btn-sm"
      :disabled="actionDisabled"
      :aria-label="`${actionLabel} ${name}`"
      @click="emit('action')"
    >
      {{ actionLabel }}
    </button>
  </div>
</template>

<script setup>
import Badge from './Badge.vue';

/**
 * IntegrationTile (reference/3-design-system/components/IntegrationTile): one tool, the data Pulse
 * reads from it and its sync state. The mark is a two-letter monogram, never a vendor logo.
 * Status tones: `ok` Connected, `accent` needs attention soon, `danger` sync failed (with the error
 * code in the text), '' Not connected. The `extra` slot holds secondary controls under the status,
 * e.g. Sync now on a live tile.
 */
defineProps({
  mark: { type: String, required: true },
  name: { type: String, required: true },
  description: { type: String, required: true },
  statusText: { type: String, required: true },
  statusTone: { type: String, default: '' },
  actionLabel: { type: String, default: 'Settings' },
  /** Optional second caption line, e.g. "Feeds: …" on the Integrations screen. */
  detail: { type: String, default: '' },
  actionDisabled: { type: Boolean, default: false },
  /** Optional badge next to the name, e.g. "Live" for a live connector. */
  badge: { type: String, default: '' },
  badgeTone: { type: String, default: 'info' },
});

const emit = defineEmits(['action']);
</script>

<style scoped>
.status {
  margin-top: 6px;
}

.pl-int-name .pl-badge {
  margin-left: 6px;
  vertical-align: middle;
}

.extra {
  margin-top: var(--space-2);
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
  align-items: center;
}
</style>
