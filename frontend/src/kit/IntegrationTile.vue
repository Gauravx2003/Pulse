<template>
  <div class="pl-int">
    <div class="pl-int-mark">{{ mark }}</div>
    <div>
      <div class="pl-int-name">{{ name }}</div>
      <div class="pl-caption">{{ description }}</div>
      <div class="status"><Badge :text="statusText" :tone="statusTone" /></div>
    </div>
    <button type="button" class="pl-btn pl-btn-sm" @click="emit('action')">{{ actionLabel }}</button>
  </div>
</template>

<script setup>
import Badge from './Badge.vue';

/**
 * IntegrationTile (reference/3-design-system/components/IntegrationTile): one tool, the data Pulse
 * reads from it and its sync state. The mark is a two-letter monogram, never a vendor logo.
 * Status tones: `ok` Connected, `accent` needs attention soon, `danger` sync failed (with the error
 * code in the text), '' Not connected.
 */
defineProps({
  mark: { type: String, required: true },
  name: { type: String, required: true },
  description: { type: String, required: true },
  statusText: { type: String, required: true },
  statusTone: { type: String, default: '' },
  actionLabel: { type: String, default: 'Settings' },
});

const emit = defineEmits(['action']);
</script>

<style scoped>
.status {
  margin-top: 6px;
}
</style>
