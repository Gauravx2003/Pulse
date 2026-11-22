<template>
  <div class="pl-toast" :class="{ 'is-on': toastState.visible }" role="status" aria-live="polite" aria-atomic="true">
    {{ toastState.visible ? toastState.text : '' }}
  </div>
</template>

<script setup>
import { toastState } from './toast.js';
</script>
