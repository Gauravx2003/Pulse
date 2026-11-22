<template>
  <div class="pl pl-shell">
    <a class="pl-sr-only pl-skip" href="#pl-content">Skip to content</a>
    <div class="pl-nav-scrim" :class="{ 'is-open': menuOpen }" aria-hidden="true" @click="menuOpen = false"></div>
    <nav id="pl-nav" ref="nav" class="pl-nav" :class="{ 'is-open': menuOpen }" aria-label="Main">
      <div class="logo"><i aria-hidden="true"></i>Pulse</div>
      <template v-for="section in menu" :key="section.group">
        <div class="sec pl-eyebrow">{{ section.group }}</div>
        <RouterLink v-for="item in section.items" :key="item.key" :to="item.path" active-class="is-active">
          {{ item.label }}
        </RouterLink>
      </template>
      <div class="pl-nav-foot">
        <span class="pl-caption">{{ me.user.name }} · {{ me.role }}</span>
        <button type="button" class="pl-btn pl-btn-sm" @click="emit('sign-out')">Sign out</button>
      </div>
    </nav>
    <div class="pl-body">
      <header class="pl-top">
        <button
          type="button"
          class="pl-btn pl-btn-sm pl-menu-btn"
          aria-controls="pl-nav"
          :aria-expanded="menuOpen"
          @click="menuOpen = !menuOpen"
        >
          Menu
        </button>
        <nav class="pl-crumb" aria-label="Breadcrumb">
          <ol>
            <li><b>{{ me.user.name }}</b> · {{ me.role }}</li>
            <li v-if="item">{{ item.group }}</li>
            <li v-if="item" aria-current="page">{{ item.label }}</li>
          </ol>
        </nav>
        <PersonSelector v-if="showPersonSelector" />
        <SyncStatus />
        <BellButton />
        <ThemeToggle />
        <button type="button" class="pl-btn pl-btn-sm" @click="emit('sign-out')">Sign out</button>
      </header>
      <main id="pl-content" class="pl-content" tabindex="-1">
        <slot />
      </main>
    </div>
  </div>
</template>

<script setup>
// App shell (task 6.4, spec platform/app-shell-ui "Shell and navigation" and "Responsive layout"):
// side menu from GET /me, top bar with breadcrumb, person selector, sync status, bell, theme and
// Sign out. Under 860 px the menu collapses behind the Menu button.
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import ThemeToggle from '../kit/ThemeToggle.vue';
import { useDialog } from '../ui/dialog.js';
import BellButton from './BellButton.vue';
import PersonSelector from './PersonSelector.vue';
import SyncStatus from './SyncStatus.vue';
import { can, isPersonScreen, menuItemFor, session } from './session.js';

const emit = defineEmits(['sign-out']);

const route = useRoute();

const me = computed(() => session.me);
const menu = computed(() => me.value?.menu ?? []);
const item = computed(() => menuItemFor(route.path));

const showPersonSelector = computed(() => isPersonScreen(item.value) && can('team.view', 'all.view'));

const menuOpen = ref(false);
const nav = ref(null);

// On phones the open menu behaves as a dialog: focus moves in, Esc closes, focus returns to Menu.
useDialog(menuOpen, nav, () => {
  menuOpen.value = false;
});

watch(
  () => route.fullPath,
  () => {
    menuOpen.value = false;
  },
);
</script>
