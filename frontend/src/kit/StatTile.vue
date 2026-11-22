<template>
  <div class="pl-stat">
    <span class="pl-eyebrow">{{ eyebrow }}</span>
    <span class="pl-stat-value">{{ value }}<small v-if="unit">{{ unit }}</small></span>
    <span class="pl-stat-foot">
      <Badge v-if="badge" :text="badge" :tone="badgeTone" />
      <slot name="foot">{{ foot }}</slot>
    </span>
  </div>
</template>

<script setup>
import Badge from './Badge.vue';

/**
 * StatTile (reference/3-design-system/components/StatTile): one headline number, already rounded,
 * with its unit and a footnote that names what it is compared against. The band goes in the badge
 * as a word; the number itself is never colored.
 */
defineProps({
  eyebrow: { type: String, required: true },
  value: { type: [String, Number], required: true },
  unit: { type: String, default: '' },
  foot: { type: String, default: '' },
  badge: { type: String, default: '' },
  badgeTone: { type: String, default: '' },
});
</script>
