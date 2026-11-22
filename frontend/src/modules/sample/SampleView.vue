<template>
  <section class="pl-card">
    <div class="pl-card-head">
      <h2 class="pl-h2">Sample module</h2>
      <span :class="['pl-badge', ok ? 'is-ok' : 'is-warn']">{{ ok ? 'API reachable' : 'API not reachable' }}</span>
    </div>
    <p class="pl-muted">Registered by <code>src/modules/sample/index.js</code> and <code>com.pulse.modules.sample.SampleModule</code>.</p>
  </section>
</template>

<script setup>
import { ref, onMounted } from 'vue';

const ok = ref(false);
onMounted(async () => {
  try {
    // read the body so the request completes (an unread body keeps the connection open)
    const res = await fetch('/api/v1/sample/ping');

    ok.value = res.ok && (await res.json()).pong === true;
  } catch {
    ok.value = false;
  }
});
</script>
