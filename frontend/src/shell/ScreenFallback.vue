<template>
  <ScreenPlaceholder v-if="item" :key="item.key" :item="item" />
  <NoAccess v-else />
</template>

<script setup>
// Any address no module route matches: a screen of the user's menu shows its placeholder, anything
// else the no-access card (forbidden and nonexistent look the same).
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import NoAccess from './NoAccess.vue';
import ScreenPlaceholder from './ScreenPlaceholder.vue';
import { menuItemFor } from './session.js';

const route = useRoute();

const item = computed(() => menuItemFor(route.path));
</script>
