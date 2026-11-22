<template>
  <div class="pl-alert" :class="{ 'is-danger': tone === 'danger' }">
    <span class="pl-badge" :class="tone === 'danger' ? 'is-danger' : 'is-accent'">{{ badge }}</span>
    <div><slot /></div>
    <div v-if="$slots.action" class="pl-alert-action">
      <slot name="action" />
    </div>
  </div>
</template>

<script setup>
// Alert strip (reference section 3): badge word + message + optional action.
defineProps({
  badge: { type: String, required: true },
  tone: { type: String, default: 'accent', validator: (t) => t === 'accent' || t === 'danger' },
});
</script>
