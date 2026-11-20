<template>
  <section class="pl-card">
    <div class="pl-card-head">
      <h2 class="pl-h2">Pulse</h2>
      <span :class="['pl-badge', isUp ? 'is-ok' : 'is-warn']">Backend {{ status }}</span>
    </div>
    <p class="pl-muted">Modules register themselves; the menu on the left comes from <code>GET /api/v1/me</code>.</p>
  </section>
</template>

<script setup>
import { ref, onMounted } from 'vue';

const status = ref('checking…');
const isUp = ref(false);

onMounted(async () => {
  try {
    const res = await fetch('/health');
    isUp.value = res.ok;
    status.value = res.ok ? 'UP' : 'OFFLINE';
  } catch {
    status.value = 'NOT CONNECTED';
  }
});
</script>
