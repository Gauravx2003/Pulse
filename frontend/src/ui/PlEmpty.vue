<template>
  <div class="pl-empty">
    <p>{{ text }}</p>
    <slot />
  </div>
</template>

<script setup>
// Empty state: centered muted text with an optional action; never a blank card.
defineProps({
  text: { type: String, default: 'Nothing to show yet.' },
});
</script>
