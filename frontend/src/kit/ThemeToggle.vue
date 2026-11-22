<template>
  <button
    type="button"
    class="pl-btn pl-btn-sm"
    :aria-pressed="theme === 'dark'"
    :aria-label="theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'"
    @click="toggleTheme"
  >
    {{ theme === 'dark' ? 'Light theme' : 'Dark theme' }}
  </button>
</template>

<script setup>
import { theme, toggleTheme } from '../theme.js';
</script>
