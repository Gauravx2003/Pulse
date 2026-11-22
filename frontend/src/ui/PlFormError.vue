<template>
  <p v-if="message" class="pl-form-error" role="alert">{{ message }}</p>
</template>

<script setup>
// The inline error line under a form (reference section 3, "Forms").
defineProps({
  message: { type: String, default: '' },
});
</script>
