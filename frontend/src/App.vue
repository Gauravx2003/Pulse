<template>
  <div class="pl pl-shell">
    <nav class="pl-nav" aria-label="Main">
      <div class="logo"><i></i>Pulse</div>
      <RouterLink to="/" active-class="" exact-active-class="is-active">Home</RouterLink>
      <template v-for="section in menu" :key="section.group">
        <div class="sec pl-eyebrow">{{ section.group }}</div>
        <RouterLink v-for="item in section.items" :key="item.key" :to="item.path" active-class="is-active">
          {{ item.label }}
        </RouterLink>
      </template>
      <p v-if="error" class="pl-caption">{{ error }}</p>
    </nav>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue';
import { fetchMe } from './api/me.js';

const menu = ref([]);
const error = ref('');

onMounted(async () => {
  try {
    menu.value = (await fetchMe()).menu ?? [];
  } catch (e) {
    error.value = 'Menu unavailable';
    console.error(e);
  }
});
</script>

<style scoped>
.pl-shell {
  min-height: 100vh;
}

.app-main {
  padding: var(--space-8);
  min-width: 0;
}
</style>
