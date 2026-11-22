<template>
  <div class="pl-loading" role="status">{{ text }}</div>
</template>

<script setup>
defineProps({
  text: { type: String, default: 'Loading…' },
});
</script>
