<template>
  <div class="app-container">
    <header class="app-header">
      <div class="logo-area">
        <h1>Pulse</h1>
        <span class="pl-badge pl-badge--info">v1.0 (Baseline)</span>
      </div>
      <div class="health-status">
        Backend Status:
        <span :class="['status-pill', isBackendUp ? 'status-up' : 'status-pending']">
          {{ backendStatus }}
        </span>
      </div>
    </header>

    <main class="app-main">
      <div class="welcome-card">
        <h2>Pulse Development Environment Ready</h2>
        <p>Monorepo scaffold initialized for Spec-Driven Development.</p>
        <ul>
          <li><strong>Backend</strong>: Vert.x Reactive Monolith (Java 21)</li>
          <li><strong>Frontend</strong>: Vue 3 + Vite + Design Tokens</li>
          <li><strong>Database</strong>: PostgreSQL configured</li>
        </ul>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';

const backendStatus = ref('Checking...');
const isBackendUp = ref(false);

onMounted(async () => {
  try {
    const res = await fetch('/health');
    if (res.ok) {
      const data = await res.json();
      backendStatus.value = data.status || 'UP';
      isBackendUp.value = true;
    } else {
      backendStatus.value = 'OFFLINE';
    }
  } catch {
    backendStatus.value = 'NOT CONNECTED';
  }
});
</script>

<style scoped>
.app-container {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background-color: var(--color-bg, #f8fafc);
  color: var(--color-text-primary, #0f172a);
  font-family: var(--font-sans, system-ui, -apple-system, sans-serif);
}

.app-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 1rem 2rem;
  border-bottom: 1px solid var(--color-border, #e2e8f0);
  background-color: var(--color-surface, #ffffff);
}

.logo-area {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.logo-area h1 {
  font-size: 1.5rem;
  margin: 0;
  font-weight: 700;
}

.status-pill {
  padding: 0.25rem 0.5rem;
  border-radius: 4px;
  font-weight: 600;
  font-size: 0.85rem;
}

.status-up {
  background-color: #dcfce7;
  color: #166534;
}

.status-pending {
  background-color: #fef3c7;
  color: #92400e;
}

.app-main {
  flex: 1;
  padding: 2rem;
  max-width: 800px;
  margin: 0 auto;
  width: 100%;
}

.welcome-card {
  background: var(--color-surface, #ffffff);
  border: 1px solid var(--color-border, #e2e8f0);
  border-radius: 8px;
  padding: 2rem;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.welcome-card h2 {
  margin-top: 0;
  font-size: 1.25rem;
}

.welcome-card ul {
  padding-left: 1.25rem;
  line-height: 1.8;
}
</style>
