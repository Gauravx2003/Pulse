<template>
  <section class="pl-card pl-no-access" aria-labelledby="no-access-title">
    <div class="pl-empty">
      <h1 id="no-access-title" class="pl-h2">You don't have access</h1>
      <p>This page isn't part of your role. Ask your manager or HR if you need it.</p>
      <RouterLink v-if="landing" class="pl-btn pl-btn-primary" :to="landing">Go to my start page</RouterLink>
    </div>
  </section>
</template>

<script setup>
// The no-access card (spec platform/app-shell-ui, "No-access page"): never an error screen, and the
// same card for a forbidden screen and an unknown address.
import { computed } from 'vue';
import { session } from './session.js';

const landing = computed(() => session.me?.landing ?? null);
</script>
