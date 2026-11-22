<template>
  <header class="pl-hdr">
    <div class="pl-hdr-text">
      <div v-if="eyebrow" class="pl-eyebrow">{{ eyebrow }}</div>
      <h1>{{ title }}</h1>
      <div v-if="caption" class="pl-caption">{{ caption }}</div>
    </div>
    <div v-if="$slots.actions" class="pl-hdr-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<script setup>
// Page header (reference section 3, "hdr"): eyebrow, H1, caption; tabs, period control and the
// primary action go in the actions slot.
defineProps({
  title: { type: String, required: true },
  eyebrow: { type: String, default: '' },
  caption: { type: String, default: '' },
});
</script>
